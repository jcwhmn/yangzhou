package yangzhou.cli

import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

/** 会话:server + token。 */
data class Session(
    val server: String,
    val token: String,
)

/**
 * 会话存储:每 server 一份(~/.yangzhou/sessions/<slug>.json),current 指针定当前 server;
 * login 只写自己 server 的文件,互相不覆盖,切回旧 server 免重登(use)。
 * 旧单文件 ~/.yangzhou/session.json 首次运行自动迁移(幂等,迁移后保留作备份)。
 */
class SessionStore(
    private val homeDir: File,
) {
    private val mapper: ObjectMapper = jacksonObjectMapper()
    private val sessionsDir: File = File(homeDir, ".yangzhou/sessions")
    private val currentFile: File = File(homeDir, ".yangzhou/current")
    private val legacyFile: File = File(homeDir, ".yangzhou/session.json")

    companion object {
        /** server URL → 文件名 slug(去 scheme 与尾斜杠,非法字符转 -):http://localhost:8081 → localhost-8081 */
        fun slug(server: String): String = server.substringAfter("://").trimEnd('/').replace(Regex("[^A-Za-z0-9._-]"), "-")

        fun default(): SessionStore = SessionStore(File(System.getProperty("user.home")))
    }

    /** 当前会话(经 current 指针);无 current 先试迁移旧单文件。 */
    fun loadCurrent(): Session? {
        migrateLegacy()
        val server = currentServer() ?: return null
        return load(server)
    }

    fun load(server: String): Session? = runCatching {
        val node = mapper.readTree(File(sessionsDir, "${slug(server)}.json"))
        Session(node["server"].asString(), node["token"].asString())
    }.getOrNull()

    fun save(session: Session) {
        sessionsDir.mkdirs()
        File(sessionsDir, "${slug(session.server)}.json").writeText(mapper.writeValueAsString(session))
        setCurrent(session.server)
    }

    /** 切换当前 server(不动任何 token);目标无已存会话时报错。 */
    fun use(server: String): Session {
        val normalized = server.trimEnd('/')
        val s = load(normalized) ?: error("该 server 无已存会话:$normalized(先 login 一次)")
        setCurrent(normalized)
        return s
    }

    fun currentServer(): String? = runCatching { currentFile.readText().trim() }.getOrNull()?.ifEmpty { null }

    fun listServers(): List<String> = sessionsDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    private fun setCurrent(server: String) {
        currentFile.parentFile.mkdirs()
        currentFile.writeText(server)
    }

    /** 旧单文件一次性迁移(幂等:current 已存在即跳过;迁移后旧文件保留作备份)。 */
    private fun migrateLegacy() {
        if (currentFile.exists() || !legacyFile.exists()) return
        val s =
            runCatching {
                val node = mapper.readTree(legacyFile)
                Session(node["server"].asString(), node["token"].asString())
            }.getOrNull() ?: return
        save(s)
    }
}
