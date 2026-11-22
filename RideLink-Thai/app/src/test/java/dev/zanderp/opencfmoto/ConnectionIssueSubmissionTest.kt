package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class ConnectionIssueSubmissionTest {
    @Test
    fun `model and multiline report are encoded as separate UTF-8 fields`() {
        val model = "Zontes 350E รุ่นทดสอบ"
        val report = "อาการ: จอไม่ขึ้น\n400 ms: Head Unit พร้อม & Wi-Fi + A=B"
        val values = ConnectionIssueSubmission.encode(model, report).split('&').associate { field ->
            field.substringBefore('=') to URLDecoder.decode(field.substringAfter('='), Charsets.UTF_8.name())
        }

        assertEquals(mapOf("entry.17275562" to model, "entry.1178618371" to report, "fvv" to "1", "pageHistory" to "0"), values)
    }

    @Test
    fun `Google confirmation text in the response body is accepted`() {
        assertTrue(ConnectionIssueSubmission.isAccepted(200, "<div>เราได้บันทึกคำตอบของคุณไว้แล้ว</div>"))
        assertTrue(ConnectionIssueSubmission.isAccepted(200, "<div>Your response has been recorded.</div>"))
    }

    @Test
    fun `form validation and hidden confirmation text are not accepted`() {
        val message = "เราได้บันทึกคำตอบของคุณไว้แล้ว"
        assertFalse(ConnectionIssueSubmission.isAccepted(200, "<form><textarea>$message</textarea></form>"))
        assertFalse(ConnectionIssueSubmission.isAccepted(200, "<script>var content = '<div>$message</div>'</script>"))
        assertFalse(ConnectionIssueSubmission.isAccepted(200, "<p>ฟอร์มไม่เปิดรับคำตอบ</p>"))
        assertFalse(ConnectionIssueSubmission.isAccepted(503, "<div>$message</div>"))
    }
}
