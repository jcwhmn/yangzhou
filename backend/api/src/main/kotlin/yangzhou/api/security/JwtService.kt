package yangzhou.api.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Date

@Component
class JwtService(
    @Value("\${yangzhou.jwt.secret}") secret: String,
    @Value("\${yangzhou.jwt.ttl-hours}") ttlHours: Long,
    profiles: Environment,
) {
    init {
        // 默认密钥防护(spec 0051):检测到内置开发密钥 → 启动即拒;test/dev(scratch 库)profile 豁免(YPJ-117)
        if (profiles.activeProfiles.none { it == "test" || it == "dev" } && secret == DEFAULT_DEV_SECRET) {
            throw IllegalStateException("检测到内置默认 JWT 密钥:请设置 YANGZHOU_JWT_SECRET(test profile 豁免)")
        }
    }

    private val key = Keys.hmacShaKeyFor(secret.toByteArray())
    private val ttl: Duration = Duration.ofHours(ttlHours)

    fun generate(subject: String): String =
        Jwts
            .builder()
            .subject(subject)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + ttl.toMillis()))
            .signWith(key)
            .compact()

    /** 校验并返回 subject;无效/过期返回 null。 */
    fun verify(token: String): String? =
        try {
            Jwts
                .parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .payload.subject
        } catch (_: Exception) {
            null
        }

    companion object {
        /** application.yml 的内置开发密钥;与 yml 保持一致。 */
        const val DEFAULT_DEV_SECRET = "yangzhou-dev-secret-0123456789abcdef0123456789abcdef"
    }
}
