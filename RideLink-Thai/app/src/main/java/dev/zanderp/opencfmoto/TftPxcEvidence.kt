package dev.zanderp.opencfmoto

/** Metadata only: never retain control-frame payload bytes or credential values. */
internal object TftPxcEvidence {
    enum class Channel { UNKNOWN, CAR_CTRL, CAR_DATA }
    enum class PayloadKind { EMPTY, JSON, INVALID_JSON, NON_JSON, TOO_LARGE }

    private val usefulFields = setOf(
        "port", "ftpPort", "serverPort", "sockPort", "host", "ip", "url", "path",
        "file", "fileName", "type", "protocol", "version", "firmwareVersion",
        "dashboardFirmwareVersion", "tftVersion", "supportOTAUpdate", "supportRVForAdb",
        "supportThirdPartyApp",
    )
    private val authFields = setOf(
        "username", "user", "pwd", "password", "token", "accesstoken", "refreshtoken", "secret", "apikey", "psk",
    )

    fun safePort(value: Any?): Int? {
        val number = when (value) {
            is Byte, is Short, is Int, is Long -> (value as Number).toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
        return number?.takeIf { it in 1..65535 }?.toInt()
    }

    fun format(
        cmd: Int,
        channel: Channel,
        payloadSize: Int,
        kind: PayloadKind,
        keys: Iterable<String> = emptyList(),
        port: Int? = null,
    ): String {
        val observedKeys = keys.toList()
        val names = observedKeys.filter { it in usefulFields }.distinct().sorted()
        val hasAuth = observedKeys.any { it.lowercase().replace("_", "") in authFields }
        val authStatus = when {
            kind != PayloadKind.JSON -> "unexamined"
            hasAuth -> "present"
            else -> "not_detected"
        }
        val validPort = safePort(port)
        return "pxc_frame=0x${cmd.toUInt().toString(16)} channel=${channel.name}" +
            " bytes=$payloadSize payload=${kind.name}" +
            " fields=${names.joinToString(",").ifEmpty { "none" }}" +
            " auth_fields=$authStatus advertised_port=${validPort ?: "unreported"}"
    }
}

/** Keeps a bounded number of distinct safe summaries per command/channel and session. */
internal class TftPxcFrameGate(private val maxFrames: Int = 64) {
    enum class Decision { RECORD, SKIP, LIMIT }

    private val seen = mutableMapOf<Pair<Int, TftPxcEvidence.Channel>, MutableSet<String>>()
    private var recordedFrames = 0
    private var limitReported = false

    @Synchronized fun reset() {
        seen.clear()
        recordedFrames = 0
        limitReported = false
    }

    @Synchronized fun accept(cmd: Int, channel: TftPxcEvidence.Channel, summary: String): Decision {
        if (cmd == PxcFrame.CMD_HEARTBEAT || cmd == PxcFrame.CMD_HEARTBEAT_ACK) return Decision.SKIP
        val key = cmd to channel
        val variants = seen[key]
        if (variants != null && (summary in variants || variants.size >= 3)) return Decision.SKIP
        val priority = cmd == PxcFrame.CMD_OTA_FTP_INFO || cmd == PxcFrame.CMD_SOCK_SERVER_INFO
        if (recordedFrames >= maxFrames && !priority) {
            if (limitReported) return Decision.SKIP
            limitReported = true
            return Decision.LIMIT
        }
        seen.getOrPut(key) { mutableSetOf() }.add(summary)
        recordedFrames++
        return Decision.RECORD
    }
}
