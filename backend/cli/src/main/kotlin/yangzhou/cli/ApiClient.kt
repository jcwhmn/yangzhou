package yangzhou.cli

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** 会话:server + token,存 ~/.yangzhou/session.json;login 后免登。 */
data class Session(val server: String, val token: String)

class ApiClient private constructor(
    private val http: HttpClient,
    private val mapper: ObjectMapper,
    private var session: Session?,
) {

    companion object {
        private val mapper = jacksonObjectMapper()

        fun sessionFile(): File =
            File(System.getProperty("user.home"), ".yangzhou/session.json")

        fun loadSession(): Session? = runCatching {
            val node = mapper.readTree(sessionFile())
            Session(node["server"].asString(), node["token"].asString())
        }.getOrNull()

        fun create(): ApiClient = ApiClient(HttpClient.newHttpClient(), mapper, loadSession())

        /** bootstrap(全新服务器)或 login(已有账号),成功后保存会话。 */
        fun login(server: String, username: String, password: String): ApiClient {
            val http = HttpClient.newHttpClient()
            val normalized = server.trimEnd('/')
            fun post(path: String): HttpResponse<String> = http.send(
                HttpRequest.newBuilder(URI("$normalized$path"))
                    .header("Content-Type", "application/json")
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(mapOf("username" to username, "password" to password)),
                        ),
                    )
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

            val response = post("/api/auth/bootstrap").let { r ->
                if (r.statusCode() == 409) post("/api/auth/login") else r
            }
            if (response.statusCode() !in 200..299) {
                error("登录失败(${response.statusCode()}):${response.body().take(200)}")
            }
            val token = mapper.readTree(response.body())["token"].asString()
            val session = Session(normalized, token)
            sessionFile().parentFile.mkdirs()
            sessionFile().writeText(mapper.writeValueAsString(session))
            return ApiClient(http, mapper, session)
        }
    }

    fun requireSession(): Session = session ?: error("未登录,先运行:yz login --server <URL> -u <用户名> -p <密码>")

    private fun request(method: String, path: String, body: Any? = null): HttpResponse<String> {
        val s = requireSession()
        val builder = HttpRequest.newBuilder(URI("${s.server}$path"))
            .header("Authorization", "Bearer ${s.token}")
            .header("Content-Type", "application/json")
        when {
            body != null -> builder.method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            method == "GET" -> builder.GET()
            else -> builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** 返回解析后的 JSON;非 2xx 抛用户可读错误。 */
    fun json(method: String, path: String, body: Any? = null): JsonNode {
        val response = request(method, path, body)
        if (response.statusCode() !in 200..299) {
            val msg = runCatching { mapper.readTree(response.body())["message"].asString() }.getOrElse { response.body().take(200) }
            error("[$method $path] ${response.statusCode()}:$msg")
        }
        return mapper.readTree(response.body())
    }

    fun rawJson(method: String, path: String, body: Any? = null): String =
        request(method, path, body).body()
}
