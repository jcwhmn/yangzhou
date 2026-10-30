package yangzhou.api.business.grouptype

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * YPJ-48 ItemGroupType 运行时定义(spec docs/spec/0056-v73-item-group-type.md)。
 * 黑盒走 REST:建项目种子内建类型、类型 CRUD、通用组 CRUD、内建封闭、删除保护。
 */
class GroupTypeApiTest : AbstractApiTest() {
    private fun post(
        authed: RestTestClient,
        uri: String,
        body: Map<String, Any?>,
    ): Pair<Int, String?> {
        val r =
            authed
                .post()
                .uri(uri)
                .body(body)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return r.status.value() to r.responseBody
    }

    private fun getJson(
        authed: RestTestClient,
        uri: String,
    ): JsonNode = json.readTree(
        authed
            .get()
            .uri(uri)
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!,
    )

    @Test
    fun `建项目种子内建类型 且既有 sprint 端点照常`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "SEED")
        val types = getJson(authed, "/api/projects/SEED/group-types")
        val keys = types.map { it["typeKey"].asText() }.toSet()
        assertTrue("sprint" in keys && "release" in keys)
        assertTrue(types.all { it["builtin"].asBoolean() })

        // 内建类型照常走专属端点(sprint 建组),通用端点对其封闭
        val (sprintStatus, _) = post(authed, "/api/projects/SEED/sprints", mapOf("name" to "S1"))
        assertEquals(201, sprintStatus)
        post(authed, "/api/projects/SEED/groups", mapOf("typeKey" to "sprint", "name" to "绕道"))
            .let { (status, _) -> assertEquals(400, status) }
    }

    @Test
    fun `自定义类型与通用组 全链路`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "GTY")

        // 非法 typeKey
        assertEquals(400, post(authed, "/api/projects/GTY/group-types", mapOf("typeKey" to "Q1", "name" to "x")).first)

        val (typeStatus, _) = post(authed, "/api/projects/GTY/group-types", mapOf("typeKey" to "quarter", "name" to "季度"))
        assertEquals(201, typeStatus)
        assertEquals(409, post(authed, "/api/projects/GTY/group-types", mapOf("typeKey" to "quarter", "name" to "季度")).first)

        // 通用组:建(带日期)→ 列 → 改 → 删
        val (gs, gb) =
            post(
                authed,
                "/api/projects/GTY/groups",
                mapOf(
                    "typeKey" to "quarter",
                    "name" to "2026-Q1",
                    "startDate" to "2026-01-01",
                    "endDate" to "2026-03-31",
                ),
            )
        assertEquals(201, gs)
        val group = json.readTree(gb!!)
        assertEquals("planned", group["status"].asText())
        assertEquals("2026-01-01", group["startDate"].asText())
        val gid = group["groupId"].asText()

        assertEquals(
            400,
            post(authed, "/api/projects/GTY/groups", mapOf("typeKey" to "quarter", "name" to "x", "status" to "released")).first,
        )

        val list = getJson(authed, "/api/projects/GTY/groups?type=quarter")
        assertEquals(1, list.size())
        assertEquals("2026-Q1", list[0]["name"].asText())

        val patched =
            json.readTree(
                authed
                    .patch()
                    .uri("/api/projects/GTY/groups/$gid")
                    .body(mapOf("status" to "active"))
                    .exchange()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )
        assertEquals("active", patched["status"].asText())

        // 类型下有组 → 拒删类型;删组后再删成功
        assertEquals(
            409,
            authed
                .delete()
                .uri("/api/projects/GTY/group-types/quarter")
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
                .status
                .value(),
        )
        authed
            .delete()
            .uri("/api/projects/GTY/groups/$gid")
            .exchange()
            .expectStatus()
            .isNoContent()
        authed
            .delete()
            .uri("/api/projects/GTY/group-types/quarter")
            .exchange()
            .expectStatus()
            .isNoContent()
        // 类型已删 → 列组 404
        assertEquals(
            404,
            authed
                .get()
                .uri("/api/projects/GTY/groups?type=quarter")
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
                .status
                .value(),
        )
    }

    @Test
    fun `内建类型不可删 但可改名`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "BUILTIN")
        assertEquals(
            409,
            authed
                .delete()
                .uri("/api/projects/BUILTIN/group-types/sprint")
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
                .status
                .value(),
        )

        val renamed =
            json.readTree(
                authed
                    .patch()
                    .uri("/api/projects/BUILTIN/group-types/sprint")
                    .body(mapOf("name" to "冲刺"))
                    .exchange()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )
        assertEquals("冲刺", renamed["name"].asText())
        assertTrue(renamed["builtin"].asBoolean())
    }
}
