package yangzhou.api.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.Date

/** 客户端分型(spec 0177 §4.1):web 8h / cli 30d;yz CLI 恒传 cli。 */
enum class ClientType {
    WEB,
    CLI,
    ;

    companion object {
        /** 缺省 web;非法值按 web(TTL 兜底,无攻击面)。 */
        fun from(value: String?): ClientType = if (value == "cli") CLI else WEB
    }
}

/** 验签结果:subject + 签发时刻(黑名单按 iat 分新旧 token,spec 0177 §4.2/§4.3 语义精化)。 */
data class VerifiedToken(
    val subject: String,
    val issuedAt: Instant,
)

@Component
class JwtService(
    @Value("\${yangzhou.jwt.secret}") secret: String,
    @Value("\${yangzhou.jwt.ttl-hours-web:\${yangzhou.jwt.ttl-hours}}") webTtlHours: Long,
    @Value("\${yangzhou.jwt.ttl-hours-cli:\${yangzhou.jwt.ttl-hours}}") cliTtlHours: Long,
    profiles: Environment,
) {
    init {
        // 默认密钥防护(spec 0051):检测到内置开发密钥 → 启动即拒;test/dev(scratch 库)profile 豁免(YPJ-117)
        if (profiles.activeProfiles.none { it == "test" || it == "dev" } && secret == DEFAULT_DEV_SECRET) {
            throw IllegalStateException("检测到内置默认 JWT 密钥:请设置 YANGZHOU_JWT_SECRET(test profile 豁免)")
        }
    }

    private val key = Keys.hmacShaKeyFor(secret.toByteArray())
    private val ttls = mapOf(ClientType.WEB to Duration.ofHours(webTtlHours), ClientType.CLI to Duration.ofHours(cliTtlHours))

    fun generate(
        subject: String,
        clientType: ClientType,
    ): String = Jwts
        .builder()
        .subject(subject)
        .claim("ct", clientType.name.lowercase())
        .issuedAt(Date())
        .expiration(Date(System.currentTimeMillis() + ttls.getValue(clientType).toMillis()))
        .signWith(key)
        .compact()

    /** 校验并返回 subject+iat;无效/过期返回 null。 */
    fun verify(token: String): VerifiedToken? = try {
        Jwts
            .parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload
            .let { VerifiedToken(it.subject, it.issuedAt.toInstant()) }
    } catch (_: Exception) {
        null
    }

    companion object {
        /** application.yml 的内置开发密钥;与 yml 保持一致。 */
        const val DEFAULT_DEV_SECRET = "yangzhou-dev-secret-0123456789abcdef0123456789abcdef"
    }
}
