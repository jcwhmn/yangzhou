package yangzhou.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 会话分存(每 server 一份 + current 指针 + 旧单文件迁移):互不覆盖、免重登切换。 */
class SessionStoreTest {
    // cli 测试依赖无 junit TempDir(仅 kotlin-test):手搭临时目录,用后删除
    private val home: File = Files.createTempDirectory("yz-session-test").toFile()

    @AfterTest
    fun cleanup() {
        home.deleteRecursively()
    }

    private val dev = "http://localhost:8081"
    private val prod = "http://localhost:8080"

    @Test
    fun `slug 去 scheme 且非法字符转连字符`() {
        assertEquals("localhost-8081", SessionStore.slug("http://localhost:8081"))
        assertEquals("localhost-8081", SessionStore.slug("localhost:8081/")) // slug 与带 scheme 同名(同机同端口)
    }

    @Test
    fun `两 server 各存一份互不覆盖_current 随 save 前移`() {
        val store = SessionStore(home)
        store.save(Session(dev, "tok-dev"))
        store.save(Session(prod, "tok-prod"))

        assertEquals("tok-prod", store.loadCurrent()?.token) // 最后 login 的是当前
        assertEquals("tok-dev", store.load(dev)?.token) // dev 未被 prod 覆盖
        assertTrue(store.listServers().size == 2)
    }

    @Test
    fun `use 切换 server 沿用已存 token`() {
        val store = SessionStore(home)
        store.save(Session(dev, "tok-dev"))
        store.save(Session(prod, "tok-prod"))

        val s = store.use(dev)

        assertEquals("tok-dev", s.token)
        assertEquals(dev, store.currentServer())
        assertEquals("tok-dev", store.loadCurrent()?.token)
    }

    @Test
    fun `use 未知 server 报错且不动 current`() {
        val store = SessionStore(home)
        store.save(Session(dev, "tok-dev"))

        assertFailsWith<IllegalStateException> { store.use(prod) }
        assertEquals(dev, store.currentServer())
    }

    @Test
    fun `旧单文件自动迁移一次且幂等`() {
        val store = SessionStore(home)
        File(home, ".yangzhou").mkdirs()
        File(home, ".yangzhou/session.json")
            .writeText("""{"server":"$dev","token":"tok-old"}""")

        assertEquals("tok-old", store.loadCurrent()?.token)
        assertTrue(store.currentServer() == dev)
        // 迁移幂等:current 已存在,旧文件后续改动不再被读
        File(home, ".yangzhou/session.json")
            .writeText("""{"server":"$prod","token":"tok-should-be-ignored"}""")
        assertEquals("tok-old", store.loadCurrent()?.token)
    }

    @Test
    fun `无任何会话 loadCurrent 为 null`() {
        assertEquals(null, SessionStore(home).loadCurrent())
    }
}
