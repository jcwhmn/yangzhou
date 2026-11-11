package yangzhou.api.business.itemgroup

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * spec 0115(YPJ-164):workflow 出边运行时可配置——PUT 全量替换(状态集/statusType 锁死),
 * transitions 增 label/icon(缺省 null,GET 契约兼容)。
 */
class GroupWorkflowApiTest : AbstractApiTest() {
    @Autowired
    private lateinit var encoder: PasswordEncoder

    private fun createCustomType(
        authed: RestTestClient,
        key: String,
        typeKey: String,
    ) {
        authed
            .post()
            .uri("/api/projects/$key/group-types")
            .body(mapOf("typeKey" to typeKey, "name" to typeKey))
            .exchange()
            .expectStatus()
            .isCreated()
    }

    private fun put(
        authed: RestTestClient,
        key: String,
        typeKey: String,
        body: List<Map<String, Any?>>,
    ): Pair<Int, String?> {
        val r =
            authed
                .put()
                .uri("/api/projects/$key/group-types/$typeKey/workflow")
                .body(body)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return r.status.value() to r.responseBody
    }

    private fun get(
        authed: RestTestClient,
        key: String,
        typeKey: String,
    ): JsonNode = json.readTree(
        authed
            .get()
            .uri("/api/projects/$key/group-types/$typeKey/workflow")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!,
    )

    /** 默认三态 switch 循环的自定义类型全量 body(可按用例改写)。 */
    private fun defaultBody(): List<Map<String, Any?>> = listOf(
        mapOf("name" to "planned", "transitions" to listOf(mapOf("function" to "switch", "target" to "active"))),
        mapOf("name" to "active", "transitions" to listOf(mapOf("function" to "switch", "target" to "completed"))),
        mapOf("name" to "completed", "transitions" to listOf(mapOf("function" to "switch", "target" to "planned"))),
    )

    @Test
    fun `PUT label icon 与新增边生效 GET 回读 存量类型缺省 null`() {
        val a = bootstrapAndAuth()
        createProject(a, "WF")
        createCustomType(a, "WF", "month")

        val body =
            listOf(
                mapOf(
                    "name" to "planned",
                    "transitions" to
                        listOf(
                            mapOf("function" to "switch", "target" to "active", "label" to "启动", "icon" to "▶"),
                            mapOf("function" to "complete", "target" to "completed"),
                        ),
                ),
                mapOf("name" to "active", "transitions" to listOf(mapOf("function" to "switch", "target" to "completed"))),
                mapOf("name" to "completed", "transitions" to emptyList<Map<String, Any?>>()),
            )
        val (status, err) = put(a, "WF", "month", body)
        assertEquals(200, status, err)

        val wf = get(a, "WF", "month")
        assertEquals("START", wf[0]["statusType"].asText())
        val first = wf[0]["transitions"][0]
        assertEquals("switch", first["function"].asText())
        assertEquals("启动", first["label"].asText())
        assertEquals("▶", first["icon"].asText())
        assertEquals(2, wf[0]["transitions"].size())
        assertTrue(wf[0]["transitions"][1]["label"].isNull)

        // 存量(未 PUT 过的)类型:label/icon 缺省 null,契约兼容
        val sprint = get(a, "WF", "sprint")
        assertTrue(sprint[0]["transitions"].size() > 0)
        assertTrue(sprint[0]["transitions"][0]["label"].isNull)
    }

    @Test
    fun `PUT 状态集少一个或多一个 409`() {
        val a = bootstrapAndAuth()
        createProject(a, "WF")
        createCustomType(a, "WF", "month")

        val (s1, e1) = put(a, "WF", "month", defaultBody().dropLast(1))
        assertEquals(409, s1, e1)
        val extra = defaultBody() + mapOf("name" to "ghost", "transitions" to emptyList<Map<String, Any?>>())
        val (s2, e2) = put(a, "WF", "month", extra)
        assertEquals(409, s2, e2)
    }

    @Test
    fun `PUT 出边目标越界 409`() {
        val a = bootstrapAndAuth()
        createProject(a, "WF")
        createCustomType(a, "WF", "month")
        val body =
            listOf(
                mapOf("name" to "planned", "transitions" to listOf(mapOf("function" to "switch", "target" to "nowhere"))),
                mapOf("name" to "active", "transitions" to emptyList<Map<String, Any?>>()),
                mapOf("name" to "completed", "transitions" to emptyList<Map<String, Any?>>()),
            )
        val (status, err) = put(a, "WF", "month", body)
        assertEquals(409, status, err)
    }

    @Test
    fun `PUT 未注册 function 与意图违规 409`() {
        val a = bootstrapAndAuth()
        createProject(a, "WF")
        createCustomType(a, "WF", "month")

        val unregistered =
            defaultBody().mapIndexed { i, m ->
                if (i == 0) mapOf("name" to "planned", "transitions" to listOf(mapOf("function" to "deploy", "target" to "active"))) else m
            }
        assertEquals(409, put(a, "WF", "month", unregistered).first)

        val badIntent =
            defaultBody().mapIndexed { i, m ->
                if (i == 0) mapOf("name" to "planned", "transitions" to listOf(mapOf("function" to "complete", "target" to "planned"))) else m
            }
        assertEquals(409, put(a, "WF", "month", badIntent).first)
    }

    @Test
    fun `PUT label 超长 400`() {
        val a = bootstrapAndAuth()
        createProject(a, "WF")
        createCustomType(a, "WF", "month")
        val body =
            defaultBody().mapIndexed { i, m ->
                if (i == 0) {
                    mapOf("name" to "planned", "transitions" to listOf(mapOf("function" to "switch", "target" to "active", "label" to "一二三四五六七八九十一二三四五六七八九十一")))
                } else {
                    m
                }
            }
        val (status, err) = put(a, "WF", "month", body)
        assertEquals(400, status, err)
    }

    @Test
    fun `非leader PUT 403`() {
        val a = bootstrapAndAuth()
        createProject(a, "WF")
        val objectId = UUID.randomUUID()
        val workspaceId = jdbc.queryForObject("select object_id from workspace limit 1", UUID::class.java)!!
        jdbc.update(
            "insert into member (object_id, workspace_object_id, username, password_hash, display_name, created_at, updated_at) " +
                "values (?, ?, ?, ?, ?, now(), now())",
            objectId,
            workspaceId,
            "bee",
            encoder.encode("beepass123"),
            "bee",
        )
        val token = login("bee", "beepass123")["token"].asText()
        val b = rest.mutate().defaultHeaders { it.setBearerAuth(token) }.build()
        val (status, err) = put(b, "WF", "sprint", defaultBody())
        assertEquals(403, status, err)
    }
}
