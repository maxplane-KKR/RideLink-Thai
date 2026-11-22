package dev.zanderp.opencfmoto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

internal object ConnectionIssueSubmission {
    // Google Forms ใช้ปลายทางนี้จากฟอร์มสำหรับผู้ตอบ ส่วน REST API ไม่มีเมธอดส่งคำตอบ
    private val endpoint = URI(
        "https://docs.google.com/forms/d/e/1FAIpQLSdIMwgPcmXr_3wbBzd16uLH4qhtH1WA0oU3YieROhrobjibwQ/formResponse?hl=th",
    ).toURL()
    private const val MAX_RESPONSE_CHARS = 256 * 1024
    private val invisibleHtml = Regex("""(?is)<(head|script|style)\b[^>]*>.*?</\1\s*>""")
    private val formTag = Regex("""(?i)<form\b""")
    private val confirmation = Regex(
        """>\s*(?:เราได้บันทึกคำตอบของคุณไว้แล้ว|Your response has been recorded\.)\s*<""",
    )

    suspend fun send(model: String, report: String): Boolean = withContext(Dispatchers.IO) {
        if (model.isBlank() || report.isBlank()) return@withContext false
        val payload = encode(model, report).toByteArray(Charsets.UTF_8)
        val connection = try {
            endpoint.openConnection() as HttpURLConnection
        } catch (_: IOException) {
            return@withContext false
        }
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) return@withContext false
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val body = StringBuilder()
                val buffer = CharArray(8 * 1024)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (body.length + count > MAX_RESPONSE_CHARS) return@withContext false
                    body.append(buffer, 0, count)
                }
                body.toString()
            }
            isAccepted(status, html)
        } catch (_: IOException) {
            false
        } finally {
            connection.disconnect()
        }
    }

    internal fun encode(model: String, report: String): String {
        val modelValue = URLEncoder.encode(model, Charsets.UTF_8.name())
        val reportValue = URLEncoder.encode(report, Charsets.UTF_8.name())
        return "entry.17275562=$modelValue&entry.1178618371=$reportValue&fvv=1&pageHistory=0"
    }

    internal fun isAccepted(status: Int, html: String): Boolean {
        if (status != HttpURLConnection.HTTP_OK) return false
        val visibleHtml = invisibleHtml.replace(html, "")
        return !formTag.containsMatchIn(visibleHtml) && confirmation.containsMatchIn(visibleHtml)
    }
}
