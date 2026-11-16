package yangzhou.api.business.release

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * YPJ-191(spec 0132,US-R1):Release 独立实体 REST 黑盒。
 * 成功主链(建/发布/删)+ 关键契约边界(stage 枚举/终态冻结/删除守卫/排序)。
 */
class ReleaseApiTest : AbstractApiTest() {
    private fun create(
        authed: RestTestClient,
        key: String,
        body: Map<String, Any?>,
    ): Pair<Int, JsonNode?> {
        val r =
            authed
                .post()
                .uri("/api/projects/$key/releases")
                .body(body)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return r.status.value() to r.responseBody?.let { json.readTree(it) }
    }

    private fun patch(
        authed: RestTestClient,
        key: String,
        id: String,
        body: Map<String, Any?>,
    ): Pair<Int, JsonNode?> {
        val r =
            authed
                .patch()
                .uri("/api/projects/$key/releases/$id")
                .body(body)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return r.status.value() to r.responseBody?.let { json.readTree(it) }
    }

    private fun delete(
        authed: RestTestClient,
        key: String,
        id: String,
    ): Int {
        val r =
            authed
                .delete()
                .uri("/api/projects/$key/releases/$id")
                .exchange()
                .returnResult(Void::class.java)
        return r.status.value()
    }

    private fun list(
        authed: RestTestClient,
        key: String,
    ): JsonNode = json.readTree(
        authed
            .get()
            .uri("/api/projects/$key/releases")
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!,
    )

    @Test
    fun `CRUD 主链与发布动作`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "REL")

        val (s1, plain) = create(authed, "REL", mapOf("name" to "1.0", "targetDate" to "2026-12-31"))
        assertEquals(201, s1)
        assertEquals("planned", plain!!["status"].asText())
        assertEquals("2026-12-31", plain["targetDate"].asText())
        val rid = plain["releaseId"].asText()

        val (s2, staged) = create(authed, "REL", mapOf("name" to "1.0 RC2", "version" to "1.0", "stage" to "rc"))
        assertEquals(201, s2)
        assertEquals("rc", staged!!["stage"].asText())
        assertEquals("1.0", staged["version"].asText())
        val sid = staged["releaseId"].asText()

        // 发布:缺省 releasedDate = 今天(Clock 注入基类;断言有值即可,避免时区耦合)
        val (s3, published) = patch(authed, "REL", rid, mapOf("status" to "released"))
        assertEquals(200, s3)
        assertEquals("released", published!!["status"].asText())
        assertTrue(!published["releasedDate"].isNull)

        // planned 改名 + 清日期
        val (s4, edited) = patch(authed, "REL", sid, mapOf("name" to "1.0 RC2 改", "targetDate" to ""))
        assertEquals(200, s4)
        assertEquals("1.0 RC2 改", edited!!["name"].asText())
        assertTrue(edited["targetDate"].isNull)

        // 删 planned → 204;released 删除/编辑 → 409
        assertEquals(204, delete(authed, "REL", sid))
        assertEquals(409, delete(authed, "REL", rid))
        val (sf) = patch(authed, "REL", rid, mapOf("name" to "试图改已发布"))
        assertEquals(409, sf)

        // 列表:released 末尾
        val arr = list(authed, "REL")
        assertEquals(1, arr.size())
        assertEquals("released", arr[0]["status"].asText())
    }

    @Test
    fun `校验边界 - stage 枚举与 name 必填`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "RELB")

        val (s1, _) = create(authed, "RELB", mapOf("name" to "x", "stage" to "dev"))
        assertEquals(400, s1)
        val (s2, _) = create(authed, "RELB", mapOf("name" to "  "))
        assertEquals(400, s2)
        val (s3, _) = create(authed, "RELB", mapOf("name" to "x", "targetDate" to "2026/01/01"))
        assertEquals(400, s3)
        // YPJ-202(spec 0135):status 通道已开——非法值 400;released 补建走独立用例
        val (s4, _) = create(authed, "RELB", mapOf("name" to "补建负例", "status" to "bogus"))
        assertEquals(400, s4)
    }

    /** YPJ-202(spec 0135):US-R5 事后补建。 */
    @Test
    fun `事后补建 - released 加日期 - 缺省今天 - 未来日 400`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "REL5")

        val (s1, backfill) = create(authed, "REL5", mapOf("name" to "v0.5 补录", "status" to "released", "releasedDate" to "2026-06-01"))
        assertEquals(201, s1)
        assertEquals("released", backfill!!["status"].asText())
        assertEquals("2026-06-01", backfill["releasedDate"].asText())

        val (s2, todayRow) = create(authed, "REL5", mapOf("name" to "v0.4 补录", "status" to "released"))
        assertEquals(201, s2)
        assertEquals("released", todayRow!!["status"].asText())
        assertTrue(todayRow["releasedDate"].asText().length == 10)

        val (s3, _) = create(authed, "REL5", mapOf("name" to "未来", "status" to "released", "releasedDate" to "2999-01-01"))
        assertEquals(400, s3)

        // 补录行进列表 released 区(排序不在本票验证,只验出现)
        val names = list(authed, "REL5").map { it["name"].asText() }
        assertTrue("v0.5 补录" in names)
    }

    @Test
    fun `跨项目 404`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "RELC")
        val (s, _) = create(authed, "NOPE", mapOf("name" to "x"))
        assertEquals(404, s)
    }
}
