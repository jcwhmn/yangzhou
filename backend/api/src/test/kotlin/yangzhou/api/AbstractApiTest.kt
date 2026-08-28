package yangzhou.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals

/**
 * 唯一 seam = REST API 黑盒(Testcontainers 真 Postgres,不 mock 领域/仓储)。
 * 容器为 JVM 级单例,跨测试类复用;每测试自清全部表。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class AbstractApiTest {

    companion object {
        private val postgres = PostgreSQLContainer("postgres:17-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun datasource(registry: DynamicPropertyRegistry) {
            postgres.start()
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    // Boot 4 = Jackson 3(tools.jackson);测试自读响应用独立的 Jackson 2 mapper,不依赖容器
    protected val json: ObjectMapper = ObjectMapper()

    protected lateinit var rest: RestTestClient

    @BeforeEach
    fun cleanDatabase() {
        listOf(
            "requirement", "item", "status", "project",
            "capability", "attribute_definition", "team_member", "team", "member", "workspace",
        ).forEach { jdbc.execute("delete from $it") }
        rest = RestTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }

    // ---------- 测试助手(domain 语言,不暴露实现) ----------

    protected fun bootstrapAndAuth(): RestTestClient {
        rest.post().uri("/api/auth/bootstrap")
            .body(mapOf("username" to "me", "password" to "secret"))
            .exchange()
        val token = login("me", "secret")["token"].asText()
        return rest.mutate().defaultHeaders { it.setBearerAuth(token) }.build()
    }

    protected fun login(username: String, password: String): JsonNode =
        rest.post().uri("/api/auth/login")
            .body(mapOf("username" to username, "password" to password))
            .exchange()
            .expectStatus().isOk()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .let { json.readTree(it) }

    protected fun createSkillAttribute(
        authed: RestTestClient,
        name: String,
        leveled: Boolean = true,
    ): JsonNode = authed.post().uri("/api/attributes")
        .body(mapOf("name" to name, "kind" to "skill", "leveled" to leveled))
        .exchange()
        .expectStatus().isCreated()
        .expectBody(String::class.java).returnResult()
        .let { r ->
            if (r.status.value() != 201) println("CREATE_FAIL=" + r.responseBody)
            json.readTree(r.responseBody!!)
        }

    protected fun setCapability(authed: RestTestClient, attribute: String, level: Int?) =
        authed.put().uri("/api/capabilities")
            .body(mapOf("attribute" to attribute, "level" to level))
            .exchange()
            .expectStatus().isOk()

    protected fun createProject(authed: RestTestClient, key: String, name: String = key): JsonNode =
        authed.post().uri("/api/projects")
            .body(mapOf("key" to key, "name" to name))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(String::class.java).returnResult().responseBody!!
            .let { json.readTree(it) }

    protected fun createItem(
        authed: RestTestClient,
        projectKey: String,
        title: String,
        requirements: List<Map<String, Any?>> = emptyList(),
    ): JsonNode {
        val r = authed.post().uri("/api/projects/$projectKey/items")
            .body(
                mapOf(
                    "title" to title,
                    "requirements" to requirements.map { req ->
                        mapOf("attribute" to req["attribute"], "minLevel" to req["minLevel"])
                    },
                ),
            )
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
        if (r.status.value() != 201) println("CREATE_FAIL body=" + r.responseBody)
        assertEquals(201, r.status.value(), "create item failed: " + r.responseBody)
        return json.readTree(r.responseBody!!)
    }
}
