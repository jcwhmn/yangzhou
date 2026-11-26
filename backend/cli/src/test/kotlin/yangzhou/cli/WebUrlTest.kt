package yangzhou.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** YPJ-135(spec 0176):items open web 基址推导——profile 端口约定(8080→3000/8081→3001),--web 显式覆盖,推导不出 fail-fast。 */
class WebUrlTest {
    @Test
    fun `profile 端口自动推导`() {
        assertEquals("http://localhost:3000", webBase("http://localhost:8080", emptyMap()))
        assertEquals("http://localhost:3001", webBase("http://localhost:8081", emptyMap()))
    }

    @Test
    fun `--web 显式覆盖优先且去尾斜杠`() {
        assertEquals("http://192.168.1.5:3000", webBase("http://localhost:8081", mapOf("web" to "http://192.168.1.5:3000/")))
    }

    @Test
    fun `推导不出时 fail-fast 引导 --web`() {
        for (server in listOf("http://localhost:8082", "http://localhost:9090", "not-a-url")) {
            val e = runCatching { webBase(server, emptyMap()) }.exceptionOrNull()
            assertTrue(e is IllegalStateException && e.message?.contains("--web") == true, "$server 应报错引导 --web,得到:${e?.message}")
        }
    }
}
