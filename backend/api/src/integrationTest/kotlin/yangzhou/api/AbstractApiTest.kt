package yangzhou.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.client.RestTestClient
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals

/**
 * 唯一 seam = REST API 黑盒(Testcontainers 真 Postgres,不 mock 领域/仓储)。
 * 容器为 JVM 级单例,跨测试类复用;每测试自清全部表。
 */
@ActiveProfiles("test")
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
    protected lateinit var jdbc: JdbcTemplate

    // Boot 4 = Jackson 3(tools.jackson);测试自读响应用独立的 Jackson 2 mapper,不依赖容器
    protected val json: ObjectMapper = ObjectMapper()

    protected lateinit var rest: RestTestClient

    @BeforeEach
    fun cleanDatabase() {
        // YPJ-106:审计列升级为 FK(member)后与业务 FK 成环,先断审计引用再按序删
        // 仅 25 张带 created_by_object_id/updated_by_object_id 审计列的表(item 只有 updated_by_object_id)
        listOf(
            "project_workflow_rule",
            "project_view",
            "item_git_ref",
            "project_repo",
            "time_entry",
            "item_group_type",
            "item_group",
            "milestone",
            "item_status_history",
            "checklist_item",
            "comment",
            "status_transition",
            "attachment",
            "item_attribute",
            "requirement",
            "status",
            "project_member",
            "project",
            "capability",
            "attribute_definition",
            "team_member",
            "team",
            "member",
            "workspace",
        ).forEach { jdbc.execute("update $it set created_by_object_id = null, updated_by_object_id = null") }
        jdbc.execute("update item set updated_by_object_id = null")
        listOf(
            "project_workflow_rule",
            "project_view",
            "item_git_ref",
            "project_repo",
            "time_entry",
            "item_group_type",
            "item_group_member_history",
            "item_group_member",
            "item_group",
            "milestone",
            "item_dependency",
            "item_activity",
            "item_status_history",
            "checklist_item",
            "comment",
            "favorite",
            "notification",
            "status_transition",
            "attachment",
            "item_attribute",
            "requirement",
            "item",
            "status",
            "project_member",
            "project",
            "capability",
            "attribute_definition",
            "team_member",
            "team",
            "member",
            "workspace",
        ).forEach { jdbc.execute("delete from $it") }
        rest = RestTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }

    // ---------- 测试助手(domain 语言,不暴露实现) ----------

    protected fun bootstrapAndAuth(): RestTestClient {
        rest
            .post()
            .uri("/api/auth/bootstrap")
            .body(mapOf("username" to "me", "password" to "secret123"))
            .exchange()
        val token = login("me", "secret123")["token"].asText()
        return rest.mutate().defaultHeaders { it.setBearerAuth(token) }.build()
    }

    protected fun login(
        username: String,
        password: String,
    ): JsonNode = rest
        .post()
        .uri("/api/auth/login")
        .body(mapOf("username" to username, "password" to password))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String::class.java)
        .returnResult()
        .responseBody!!
        .let { json.readTree(it) }

    protected fun createSkillAttribute(
        authed: RestTestClient,
        name: String,
        leveled: Boolean = true,
    ): JsonNode = authed
        .post()
        .uri("/api/attributes")
        .body(mapOf("name" to name, "kind" to "skill", "leveled" to leveled))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(String::class.java)
        .returnResult()
        .let { r ->
            if (r.status.value() != 201) println("CREATE_FAIL=" + r.responseBody)
            json.readTree(r.responseBody!!)
        }

    protected fun setCapability(
        authed: RestTestClient,
        attribute: String,
        level: Int?,
    ) = authed
        .put()
        .uri("/api/capabilities")
        .body(mapOf("attribute" to attribute, "level" to level))
        .exchange()
        .expectStatus()
        .isOk()

    protected fun createProject(
        authed: RestTestClient,
        key: String,
        name: String = key,
    ): JsonNode = authed
        .post()
        .uri("/api/projects")
        .body(mapOf("key" to key, "name" to name))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(String::class.java)
        .returnResult()
        .responseBody!!
        .let { json.readTree(it) }

    /** 0047:创建者(me)自动入池,测试常需要其 memberId。 */
    protected fun meMemberId(authed: RestTestClient): String = json
        .readTree(
            authed
                .get()
                .uri("/api/members")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!,
        ).first { !it["virtual"].asBoolean() }["memberId"]
        .asText()

    /** 0047:池语义严格化后,指派虚拟成员的用例需先入池。memberId = workspace 成员 objectId。 */
    protected fun addPoolMember(
        authed: RestTestClient,
        projectKey: String,
        memberId: String,
    ) {
        authed
            .post()
            .uri("/api/projects/$projectKey/members")
            .body(mapOf("memberId" to memberId))
            .exchange()
            .expectStatus()
            .isCreated()
    }

    protected fun createItem(
        authed: RestTestClient,
        projectKey: String,
        title: String,
        requirements: List<Map<String, Any?>> = emptyList(),
        parentItemId: String? = null,
    ): JsonNode {
        val body =
            mutableMapOf(
                "title" to title,
                "requirements" to
                    requirements.map { req ->
                        mapOf("attribute" to req["attribute"], "minLevel" to req["minLevel"])
                    },
            )
        if (parentItemId != null) body["parentItemId"] = parentItemId
        val r =
            authed
                .post()
                .uri("/api/projects/$projectKey/items")
                .body(body)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        if (r.status.value() != 201) println("CREATE_FAIL body=" + r.responseBody)
        assertEquals(201, r.status.value(), "create item failed: " + r.responseBody)
        return json.readTree(r.responseBody!!)
    }
}
