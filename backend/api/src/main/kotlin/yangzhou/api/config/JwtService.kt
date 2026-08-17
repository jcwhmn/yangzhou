package yangzhou.api.config

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Date

@Component
class JwtService(
    @Value("\${yangzhou.jwt.secret}") secret: String,
    @Value("\${yangzhou.jwt.ttl-hours}") ttlHours: Long,
) {
    private val key = Keys.hmacShaKeyFor(secret.toByteArray())
    private val ttl: Duration = Duration.ofHours(ttlHours)

    fun generate(subject: String): String =
        Jwts.builder()
            .subject(subject)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + ttl.toMillis()))
            .signWith(key)
            .compact()

    /** 校验并返回 subject;无效/过期返回 null。 */
    fun verify(token: String): String? = try {
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload.subject
    } catch (_: Exception) {
        null
    }
}
