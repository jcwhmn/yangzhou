package yangzhou.api.project

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import yangzhou.api.AbstractApiTest

/** YPJ-60(spec 0046):项目级联硬删 + 删除预览 + member 删除引用检查 409 化。 */
class ProjectDeletionApiTest : AbstractApiTest() {

    @Test
    fun `删除项目——item 级联清空含回收站软删,preview 计数含软删`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "DEL")
        val parent = createItem(authed, "DEL", "父")
        createItem(authed, "DEL", "子", parentItemId = parent["itemId"].asText())
        // 软删一个(进回收站)
        authed.delete().uri("/api/items/${parent["itemId"].asText()}")
            .exchange().expectStatus().isNoContent()

        val preview = authed.get().uri("/api/projects/DEL/deletion-preview")
            .exchange().expectStatus().isOk()
            .expectBody(String::class.java).returnResult().responseBody!!
        assertEquals(2, json.readTree(preview)["itemCount"].asInt(), "preview 计数应含软删 item")

        authed.delete().uri("/api/projects/DEL").exchange().expectStatus().isNoContent()
        authed.get().uri("/api/projects/DEL").exchange().expectStatus().isNotFound()

        val bin = authed.get().uri("/api/recycle-bin")
            .exchange().expectStatus().isOk()
            .expectBody(String::class.java).returnResult().responseBody!!
        assertEquals(0, json.readTree(bin).size(), "回收站应随项目删除清空")
    }

    @Test
    fun `删除项目——sprint 与 milestone 一并清,对照项目不受影响`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "KPA") // 对照组
        createItem(authed, "KPA", "保留")
        createProject(authed, "GON")
        authed.post().uri("/api/projects/GON/sprints")
            .body(mapOf<String, String?>("name" to "S1"))
            .exchange().expectStatus().isCreated()
        authed.post().uri("/api/projects/GON/milestones")
            .body(mapOf<String, String?>("name" to "M1"))
            .exchange().expectStatus().isCreated()

        val preview = authed.get().uri("/api/projects/GON/deletion-preview")
            .exchange().expectStatus().isOk()
            .expectBody(String::class.java).returnResult().responseBody!!
        val p = json.readTree(preview)
        assertEquals(1, p["groupCount"].asInt())
        assertEquals(1, p["milestoneCount"].asInt())
        assertEquals(5, p["statusCount"].asInt()) // 默认工作流 5 状态

        authed.delete().uri("/api/projects/GON").exchange().expectStatus().isNoContent()
        authed.get().uri("/api/projects/GON").exchange().expectStatus().isNotFound()
        // 对照组完好
        authed.get().uri("/api/projects/KPA").exchange().expectStatus().isOk()
        val items = authed.get().uri("/api/projects/KPA/items")
            .exchange().expectStatus().isOk()
            .expectBody(String::class.java).returnResult().responseBody!!
        assertEquals(1, json.readTree(items).size())
    }

    @Test
    fun `删除项目清 last_project_key——登录回退不 404`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "LPK")
        authed.put().uri("/api/members/me/last-project")
            .body(mapOf("key" to "LPK"))
            .exchange().expectStatus().isOk()

        authed.delete().uri("/api/projects/LPK").exchange().expectStatus().isNoContent()

        val members = authed.get().uri("/api/members")
            .exchange().expectStatus().isOk()
            .expectBody(String::class.java).returnResult().responseBody!!
        val me = json.readTree(members).first { !it["virtual"].asBoolean() }
        assertEquals(null, me["lastProjectKey"].let { if (it.isNull || it.isMissingNode) null else it.asText() })
    }

    @Test
    fun `成员删除——进行中指派 409(既有),删项目后可删`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "MDEL")
        val m = authed.post().uri("/api/members")
            .body(mapOf("displayName" to "垃圾成员"))
            .exchange().expectStatus().isCreated()
            .expectBody(String::class.java).returnResult().responseBody!!
        val mid = json.readTree(m)["memberId"].asText()
        // 指派要求成员在项目池内(仓内约束),先入池
        authed.post().uri("/api/projects/MDEL/members")
            .body(mapOf("memberId" to mid))
            .exchange().expectStatus().isCreated()
        val item = createItem(authed, "MDEL", "被指派")
        authed.put().uri("/api/items/${item["itemId"].asText()}/assignee")
            .body(mapOf("assigneeItemId" to mid))
            .exchange().expectStatus().isOk()

        authed.delete().uri("/api/members/$mid").exchange().expectStatus().isEqualTo(409)

        authed.delete().uri("/api/projects/MDEL").exchange().expectStatus().isNoContent()
        authed.delete().uri("/api/members/$mid").exchange().expectStatus().isNoContent()
    }

    @Test
    fun `成员删除——restrict 引用(评论)409 化而非 500,清引用后可删`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "REF")
        val item = createItem(authed, "REF", "评论宿主")
        val m = authed.post().uri("/api/members")
            .body(mapOf("displayName" to "评论者"))
            .exchange().expectStatus().isCreated()
            .expectBody(String::class.java).returnResult().responseBody!!
        val mid = json.readTree(m)["memberId"].asText()
        // 单真实用户体系评论作者恒为本人,API 造不出该成员的评论:jdbc 直造(测 service 引用检查)
        val itemId = jdbc.queryForObject(
            "select id from item where object_id = ?", Long::class.java, UUID.fromString(item["itemId"].asText()),
        )
        val memberId = jdbc.queryForObject(
            "select id from member where object_id = ?", Long::class.java, UUID.fromString(mid),
        )
        jdbc.update(
            "insert into comment (object_id, item_id, author_member_id, body) values (?, ?, ?, ?)",
            UUID.randomUUID(), itemId, memberId, "脏引用",
        )

        val resp = authed.delete().uri("/api/members/$mid")
            .exchange().expectStatus().isEqualTo(409)
            .expectBody(String::class.java).returnResult().responseBody
        assertTrue(resp!!.contains("引用数据"), "409 消息应带引用计数:$resp")

        jdbc.update("delete from comment where author_member_id = ?", memberId)
        authed.delete().uri("/api/members/$mid").exchange().expectStatus().isNoContent()
    }

    @Test
    fun `负例——删除与预览对未知项目 404`() {
        val authed = bootstrapAndAuth()
        authed.get().uri("/api/projects/NOPE/deletion-preview")
            .exchange().expectStatus().isNotFound()
        authed.delete().uri("/api/projects/NOPE")
            .exchange().expectStatus().isNotFound()
    }
}
