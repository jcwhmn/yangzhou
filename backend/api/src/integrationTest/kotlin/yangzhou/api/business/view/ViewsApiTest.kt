package yangzhou.api.business.view

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** YPJ-47 视图配置(spec 0045):CRUD 主链 + 同名 409 + 非法 JSON 400 + 他项目视图 404。 */
class ViewsApiTest : AbstractApiTest() {
    private lateinit var authed: RestTestClient

    private fun get(uri: String): JsonNode {
        val r =
            authed
                .get()
                .uri(uri)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertTrue(r.status.value() < 300, "GET $uri -> ${r.status.value()} ${r.responseBody}")
        return json.readTree(r.responseBody!!)
    }

    private fun post(
        uri: String,
        body: Any,
    ): Pair<Int, JsonNode?> {
        val r =
            authed
                .post()
                .uri(uri)
                .body(body)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return r.status.value() to (r.responseBody?.let { json.readTree(it) })
    }

    private fun patch(
        uri: String,
        body: Any,
    ): Pair<Int, JsonNode?> {
        val r =
            authed
                .patch()
                .uri(uri)
                .body(body)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return r.status.value() to (r.responseBody?.let { json.readTree(it) })
    }

    private fun delete(uri: String): Int {
        val r =
            authed
                .delete()
                .uri(uri)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return r.status.value()
    }

    @Test
    fun `视图 CRUD 主链`() {
        authed = bootstrapAndAuth()
        createProject(authed, "VW1")
        // 建视图(filters + columns)
        val (code, created) =
            post(
                "/api/projects/VW1/views",
                mapOf(
                    "name" to "我的进行中",
                    "filters" to mapOf("status" to UUID.randomUUID().toString(), "assignee" to null, "priority" to null),
                    "columns" to mapOf("priority" to true, "blocked" to true),
                ),
            )
        assertEquals(201, code)
        val viewId = created!!["viewId"].asText()
        assertEquals("我的进行中", created["name"].asText())
        assertTrue(created["filters"]["status"].asText().isNotEmpty()) // filters 原样回读
        // 列表含它
        val list = get("/api/projects/VW1/views")
        assertEquals(1, list.size())
        // 改名 + 覆盖 columns
        val (pcode, patched) =
            patch(
                "/api/projects/VW1/views/$viewId",
                mapOf("name" to "进行中 v2", "columns" to mapOf("priority" to true)),
            )
        assertEquals(200, pcode)
        assertEquals("进行中 v2", patched!!["name"].asText())
        assertTrue(patched["columns"]["priority"].asBoolean())
        // 删除
        assertEquals(204, delete("/api/projects/VW1/views/$viewId"))
        assertEquals(0, get("/api/projects/VW1/views").size())
    }

    @Test
    fun `同名视图 409`() {
        authed = bootstrapAndAuth()
        createProject(authed, "VW2")
        val body = mapOf("name" to "同", "filters" to mapOf<String, String>(), "columns" to mapOf<String, String>())
        assertEquals(201, post("/api/projects/VW2/views", body).first)
        val (code, err) = post("/api/projects/VW2/views", body)
        assertEquals(409, code)
        assertTrue(err!!["message"].asText().contains("同名"))
    }

    @Test
    fun `filters 非对象 400`() {
        authed = bootstrapAndAuth()
        createProject(authed, "VW3")
        val (code, err) = post("/api/projects/VW3/views", mapOf("name" to "x", "filters" to listOf("a")))
        assertEquals(400, code)
        assertTrue(err!!["message"].asText().contains("JSON 对象"))
    }

    @Test
    fun `他项目视图经错误项目 key 访问 404`() {
        authed = bootstrapAndAuth()
        createProject(authed, "VW4A")
        createProject(authed, "VW4B")
        val (code, created) =
            post(
                "/api/projects/VW4A/views",
                mapOf(
                    "name" to "va",
                    "filters" to mapOf<String, String>(),
                    "columns" to mapOf<String, String>(),
                ),
            )
        assertEquals(201, code)
        val viewId = created!!["viewId"].asText()
        // 用 B 项目 key 访问 A 项目的视图 → 404(不泄露)
        val (pcode, _) = patch("/api/projects/VW4B/views/$viewId", mapOf("name" to "hijack"))
        assertEquals(404, pcode)
        assertEquals(404, delete("/api/projects/VW4B/views/$viewId"))
        // 正确 key 仍可删
        assertEquals(204, delete("/api/projects/VW4A/views/$viewId"))
    }
}
