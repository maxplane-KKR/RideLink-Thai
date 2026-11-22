// SPDX-License-Identifier: AGPL-3.0-or-later
// PCM framing adapted from the OpenCfMoto microphone bridge.
package dev.zanderp.opencfmoto.aa

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import dev.zanderp.opencfmoto.aa.proto.Common
import dev.zanderp.opencfmoto.aa.proto.Media
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/** Phone microphone, armed for a single voice command from the AA tab. */
internal class AaMicrophone(private val context: Context, private val transport: AapTransport) {
    @Volatile private var recorder: AudioRecord? = null
    private var armedUntil = 0L

    @Synchronized
    fun arm() {
        armedUntil = SystemClock.elapsedRealtime() + 10_000L
    }

    @Synchronized
    fun onRequest(open: Boolean, channel: Int, sessionId: Int) {
        if (!open && recorder != null) stop()
        val started = if (open && recorder == null) openRecorder() else null
        val status = if (!open || recorder != null) Common.MessageStatus.STATUS_SUCCESS_VALUE
        else Common.MessageStatus.STATUS_INTERNAL_ERROR_VALUE
        val response = Media.MicrophoneResponse.newBuilder()
            .setStatus(status)
            .setSessionId(sessionId)
            .build()
        transport.send(AapMessage(channel, Media.MsgType.MEDIA_MESSAGE_MICROPHONE_RESPONSE_VALUE, response))
        if (started != null) thread(name = "aa-voice-mic", isDaemon = true) { stream(started) }
    }

    private fun openRecorder(): AudioRecord? {
        val allowed = SystemClock.elapsedRealtime() < armedUntil
        armedUntil = 0L
        if (!allowed) return null

        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channel, encoding).coerceAtLeast(CHUNK_SAMPLES * 8)
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        val input = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, channel, encoding, bufferSize)
        } catch (_: RuntimeException) {
            return null
        }
        try {
            input.startRecording()
            if (input.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder = input
                return input
            }
        } catch (_: RuntimeException) {
            // The microphone can become unavailable between the permission check and start.
        }
        input.release()
        return null
    }

    private fun stream(input: AudioRecord) {
        val samples = ShortArray(CHUNK_SAMPLES)
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        try {
            while (recorder === input && SystemClock.elapsedRealtime() < deadline) {
                val count = input.read(samples, 0, samples.size)
                if (count <= 0 || recorder !== input) break
                transport.send(micData(samples, count))
            }
        } catch (_: RuntimeException) {
            // Closing the session can interrupt a read.
        } finally {
            synchronized(this) {
                if (recorder === input) stop()
            }
        }
    }

    private fun micData(samples: ShortArray, count: Int): AapMessage {
        val payload = 8 + count * 2
        val offset = AapMessage.HEADER_SIZE + MsgType.SIZE
        val data = ByteArray(offset + payload)
        data[0] = Channel.ID_MIC.toByte()
        data[1] = 0x0b
        Utils.intToBytes(MsgType.SIZE + payload, 2, data)
        val buffer = ByteBuffer.wrap(data, offset, payload).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(SystemClock.elapsedRealtimeNanos() / 1000)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) buffer.putShort(samples[i])
        return AapMessage(Channel.ID_MIC, 0x0b, Media.MsgType.MEDIA_MESSAGE_DATA_VALUE, offset, data.size, data)
    }

    @Synchronized
    fun stop() {
        armedUntil = 0L
        val input = recorder ?: return
        recorder = null
        runCatching { input.stop() }
        runCatching { input.release() }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SAMPLES = SAMPLE_RATE / 50
    }
}
