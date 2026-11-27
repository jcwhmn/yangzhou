package yangzhou.api.security

import io.jsonwebtoken.Jwts
import org.springframework.mock.env.MockEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JwtServiceUnitTest {
    private fun env(vararg profiles: String): MockEnvironment = MockEnvironment().apply {
        setActiveProfiles(*profiles)
    }

    private fun service(
        webHours: Long = 8,
        cliHours: Long = 720,
    ): JwtService = JwtService(JwtService.DEFAULT_DEV_SECRET, webHours, cliHours, env("test"))

    @Test
    fun `默认密钥且非 test profile,启动即拒`() {
        val e =
            assertFailsWith<IllegalStateException> {
                JwtService(JwtService.DEFAULT_DEV_SECRET, 24, 720, env())
            }
        assertEquals(true, e.message!!.contains("YANGZHOU_JWT_SECRET"))
    }

    @Test
    fun `默认密钥但 test profile,放行`() {
        service()
    }

    @Test
    fun `自定义密钥,放行`() {
        JwtService("a".repeat(48), 24, 720, env())
    }

    // ---------- spec 0177(S1)§4.1:ct 分型与 TTL 分键 ----------

    private val key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(JwtService.DEFAULT_DEV_SECRET.toByteArray())

    private fun claims(token: String) = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload

    @Test
    fun `web token TTL 8h,ct=web`() {
        val token = service(webHours = 8).generate("sub-1", ClientType.WEB)
        val payload = claims(token)
        assertEquals("web", payload["ct"])
        assertEquals(8 * 3600, (payload.expiration.time - payload.issuedAt.time) / 1000)
    }

    @Test
    fun `cli token TTL 720h,ct=cli`() {
        val token = service(cliHours = 720).generate("sub-2", ClientType.CLI)
        val payload = claims(token)
        assertEquals("cli", payload["ct"])
        assertEquals(720 * 3600, (payload.expiration.time - payload.issuedAt.time) / 1000)
    }

    @Test
    fun `verify 返回 subject,无效 token 返回 null`() {
        val s = service()
        assertEquals("sub-3", s.verify(s.generate("sub-3", ClientType.WEB)))
        kotlin.test.assertNull(s.verify("not-a-token"))
    }
}
