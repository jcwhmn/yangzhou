package yangzhou.api.business.github

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** YPJ-85:webhook 签名纯函数——正确签名/篡改/错 secret/格式异常。 */
class WebhookSignatureTest {

    private val secret = "test-secret"

    private fun sign(body: String, secret: String = this.secret): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return "sha256=" + mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun correctSignatureVerifies() {
        val body = """{"action":"opened"}"""
        assertTrue(WebhookSignature.verify(secret, body, sign(body)))
    }

    @Test
    fun tamperedBodyFails() {
        assertFalse(WebhookSignature.verify(secret, "real-body", sign("other-body")))
    }

    @Test
    fun wrongSecretFails() {
        val body = "x"
        assertFalse(WebhookSignature.verify(secret, body, sign(body, secret = "evil")))
    }

    @Test
    fun malformedHeaderFails() {
        val body = "x"
        assertFalse(WebhookSignature.verify(secret, body, "sha256=zzzz"))
        assertFalse(WebhookSignature.verify(secret, body, "md5=deadbeef"))
        assertFalse(WebhookSignature.verify(secret, body, ""))
    }
}
