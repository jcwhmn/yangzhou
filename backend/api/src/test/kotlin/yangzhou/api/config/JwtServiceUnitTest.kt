package yangzhou.api.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.mock.env.MockEnvironment

class JwtServiceUnitTest {

    private fun env(vararg profiles: String): MockEnvironment = MockEnvironment().apply {
        setActiveProfiles(*profiles)
    }

    @Test
    fun `默认密钥且非 test profile,启动即拒`() {
        val e = assertFailsWith<IllegalStateException> {
            JwtService(JwtService.DEFAULT_DEV_SECRET, 24, env())
        }
        assertEquals(true, e.message!!.contains("YANGZHOU_JWT_SECRET"))
    }

    @Test
    fun `默认密钥但 test profile,放行`() {
        JwtService(JwtService.DEFAULT_DEV_SECRET, 24, env("test"))
    }

    @Test
    fun `自定义密钥,放行`() {
        JwtService("a".repeat(48), 24, env())
    }
}
