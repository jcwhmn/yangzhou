package yangzhou.api.release

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** YPJ-4 Release 黑盒(spec 0012):CRUD/排序/单向生命周期/事后补建/membership/milestone N:1。 */
class ReleaseApiTest : AbstractApiTest() {

    @Test
    fun `CRUD 与排序_planned在前_target升序_released按发布日降序`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "REL")
        val a = post(authed, "/api/projects/REL/releases", mapOf("name" to "R2.0", "targetDate" to "2026-12-01"))
        val b = post(authed, "/api/projects/REL/releases", mapOf("name" to "R2.1"))
        post(authed, "/api/projects/REL/releases", mapOf("name" to "R1.9-补建", "status" to "released", "releasedDate" to "2026-09-15"))

        val list = get(authed, "/api/projects/REL/releases")
        assertEquals(3, list.size())
        assertEquals("R2.0", list[0]["name"].asText())       // planned 先,target asc nulls last
        assertEquals("R2.1", list[1]["name"].asText())
        assertEquals("R1.9-补建", list[2]["name"].asText())  // released 末尾
        assertEquals("released", list[2]["status"].asText())
        assertEquals("2026-09-15", list[2]["releasedDate"].asText())
        assertEquals(0, list[0]["memberCount"].asInt())

        // PATCH:改 target;空串清除
        authed.patch().uri("/api/projects/REL/releases/${a["releaseId"].asText()}")
            .body(mapOf("targetDate" to ""))
            .exchange().expectStatus().isOk()
        val patched = get(authed, "/api/projects/REL/releases")[0]
        assertTrue(patched["targetDate"].isNull)
    }

    @Test
    fun `生命周期_planned到released_终态不可变_事后补建`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "REL")
        val a = post(authed, "/api/projects/REL/releases", mapOf("name" to "R2.0", "targetDate" to "2026-12-01"))

        // planned → released
        authed.patch().uri("/api/projects/REL/releases/${a["releaseId"].asText()}")
            .body(mapOf("status" to "released", "releasedDate" to "2026-10-05"))
            .exchange().expectStatus().isOk()

        // released 终态:再迁移/改名/改日期 → 409
        for (body in listOf(
            mapOf("status" to "planned"),
            mapOf("name" to "改名"),
            mapOf("targetDate" to "2027-01-01"),
            mapOf("milestoneId" to ""),
        )) {
            authed.patch().uri("/api/projects/REL/releases/${a["releaseId"].asText()}")
                .body(body)
                .exchange().expectStatus().isEqualTo(409)
        }

        // 事后补建:创建即 released(§8.2)
        val h = post(authed, "/api/projects/REL/releases", mapOf("name" to "R1.0", "status" to "released"))
        assertEquals("released", h["status"].asText())

        // 非法 status → 400
        authed.post().uri("/api/projects/REL/releases")
            .body(mapOf("name" to "X", "status" to "shipped"))
            .exchange().expectStatus().isBadRequest()
    }

    @Test
    fun `membership_拉入移出补录_非空删除409`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "REL")
        val a = post(authed, "/api/projects/REL/releases", mapOf("name" to "R2.0"))
        val rid = a["releaseId"].asText()
        val x = createItem(authed, "REL", "feature x")["itemId"].asText()

        // 拉入 → ItemDto.releaseName 回填
        val assigned = authed.put().uri("/api/items/$x/release")
            .body(mapOf("releaseId" to rid))
            .exchange().expectStatus().isOk()
            .expectBody(String::class.java).returnResult().responseBody!!
        assertEquals("R2.0", json.readTree(assigned)["releaseName"].asText())

        // 非空删除 → 409
        authed.delete().uri("/api/projects/REL/releases/$rid")
            .exchange().expectStatus().isEqualTo(409)

        // released 的 release 仍可补录(§8.2 历史补建)
        authed.patch().uri("/api/projects/REL/releases/$rid").body(mapOf("status" to "released")).exchange().expectStatus().isOk()
        val y = createItem(authed, "REL", "feature y")["itemId"].asText()
        authed.put().uri("/api/items/$y/release").body(mapOf("releaseId" to rid))
            .exchange().expectStatus().isOk()

        // 列表 memberCount=2;移出 y;清空后删除 204
        assertEquals(2, get(authed, "/api/projects/REL/releases")[0]["memberCount"].asInt())
        authed.put().uri("/api/items/$y/release").body(mapOf<String, Any?>("releaseId" to null))
            .exchange().expectStatus().isOk()
        authed.put().uri("/api/items/$x/release").body(mapOf<String, Any?>("releaseId" to null))
            .exchange().expectStatus().isOk()
        authed.delete().uri("/api/projects/REL/releases/$rid").exchange().expectStatus().isNoContent()
    }

    @Test
    fun `milestone关联_可空N比1_跨项目404`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "REL")
        createProject(authed, "OTHER")
        val ms = post(authed, "/api/projects/REL/milestones", mapOf("name" to "M1"))

        val r = post(authed, "/api/projects/REL/releases", mapOf("name" to "R2.0", "milestoneId" to ms["milestoneId"].asText()))
        assertEquals(ms["milestoneId"].asText(), r["milestoneId"].asText())

        // 清除(空串)
        val cleared = authed.patch().uri("/api/projects/REL/releases/${r["releaseId"].asText()}")
            .body(mapOf("milestoneId" to ""))
            .exchange().expectStatus().isOk()
            .expectBody(String::class.java).returnResult().responseBody!!
        assertTrue(json.readTree(cleared)["milestoneId"].isNull)

        // 跨项目 milestoneId → 404
        val otherMs = post(authed, "/api/projects/OTHER/milestones", mapOf("name" to "M2"))
        authed.post().uri("/api/projects/REL/releases")
            .body(mapOf("name" to "X", "milestoneId" to otherMs["milestoneId"].asText()))
            .exchange().expectStatus().isNotFound()

        // 跨项目 releaseId:assign/PATCH/DELETE 均 404
        val other = post(authed, "/api/projects/OTHER/releases", mapOf("name" to "OTHER-R"))
        val x = createItem(authed, "REL", "x")["itemId"].asText()
        authed.put().uri("/api/items/$x/release").body(mapOf("releaseId" to other["releaseId"].asText()))
            .exchange().expectStatus().isNotFound()
        authed.patch().uri("/api/projects/REL/releases/${other["releaseId"].asText()}")
            .body(mapOf("name" to "hack")).exchange().expectStatus().isNotFound()
        authed.delete().uri("/api/projects/REL/releases/${other["releaseId"].asText()}")
            .exchange().expectStatus().isNotFound()
    }

    private fun post(authed: RestTestClient, uri: String, body: Map<String, Any?>): JsonNode =
        json.readTree(
            authed.post().uri(uri).body(body)
                .exchange().expectStatus().isCreated()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )

    private fun get(authed: RestTestClient, uri: String): JsonNode =
        json.readTree(
            authed.get().uri(uri).exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
}
