package yangzhou.api.business.github

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** GitHub webhook 签名校验:X-Hub-Signature-256 = "sha256=" + hex(HMAC-SHA256(secret, rawBody))。 */
object WebhookSignature {
    fun verify(
        secret: String,
        body: String,
        header: String,
    ): Boolean {
        if (!header.startsWith("sha256=")) return false
        val provided = runCatching { header.removePrefix("sha256=").hexToByteArray() }.getOrNull() ?: return false
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return MessageDigest.isEqual(mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)), provided)
    }
}
