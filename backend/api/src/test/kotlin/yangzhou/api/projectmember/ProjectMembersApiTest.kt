package yangzhou.api.projectmember

import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JCW-94 V3.5-B:项目成员池 CRUD + 指派/候选池语义(黑盒)。 */
class ProjectMembersApiTest : AbstractApiTest() {

    private fun addWorkspaceMember(authed: RestTestClient, name: String): String =
        json.readTree(
            authed.post().uri("/api/members").body(mapOf("displayName" to name))
                .exchange().expectStatus().isCreated()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )["memberId"].asText()

    private fun post(authed: RestTestClient, key: String, memberId: String) =
        authed.post().uri("/api/projects/$key/members")
            .body(mapOf("memberId" to memberId))
            .exchange().expectBody(String::class.java).returnResult()

    private fun listMembers(authed: RestTestClient, key: String) =
        json.readTree(
            authed.get().uri("/api/projects/$key/members")
                .exchange().expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )

    private fun delete(authed: RestTestClient, key: String, memberId: String) =
        authed.delete().uri("/api/projects/$key/members/$memberId")
            .exchange().expectBody(String::class.java).returnResult()

    private fun assign(authed: RestTestClient, itemId: String, memberId: String?) =
        authed.put().uri("/api/items/$itemId/assignee")
            .body(mapOf("assigneeItemId" to memberId))
            .exchange().expectBody(String::class.java).returnResult()

    private fun candidateIds(authed: RestTestClient, itemId: String): Set<String> =
        json.readTree(
            authed.get().uri("/api/items/$itemId/candidates")
                .exchange().expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        ).map { it["memberId"].asText() }.toSet()

    /** fixture:项目 CHE + 两名虚拟成员(小李/小王)+ 属性 Java。 */
    private fun seed(): Triple<RestTestClient, String, String> {
        val authed = bootstrapAndAuth()
        createSkillAttribute(authed, "Java")
        createProject(authed, "CHE")
        val xiaoli = addWorkspaceMember(authed, "小李")
        val xiaowang = addWorkspaceMember(authed, "小王")
        return Triple(authed, xiaoli, xiaowang)
    }

    @Test
    fun `增列删 - 重复409 - 未知404 - 按加入顺序`() {
        val (authed, xiaoli, xiaowang) = seed()

        val added = json.readTree(post(authed, "CHE", xiaoli).responseBody!!)
        assertEquals(xiaoli, added["memberId"].asText())
        assertEquals("小李", added["displayName"].asText())
        assertTrue(added["virtual"].asBoolean())
        assertEquals(201, post(authed, "CHE", xiaowang).status.value())

        val list = listMembers(authed, "CHE")
        assertEquals(2, list.size())
        assertEquals("小李", list[0]["displayName"].asText())
        assertEquals("小王", list[1]["displayName"].asText())

        assertEquals(409, post(authed, "CHE", xiaoli).status.value()) // 重复
        assertEquals(404, post(authed, "CHE", "00000000-0000-0000-0000-000000000000").status.value()) // 未知成员
        assertEquals(404, post(authed, "NOPE", xiaoli).status.value()) // 未知项目
    }

    @Test
    fun `删除成员 - 204 再删404 - 列表更新`() {
        val (authed, xiaoli, xiaowang) = seed()
        assertEquals(201, post(authed, "CHE", xiaoli).status.value())
        assertEquals(201, post(authed, "CHE", xiaowang).status.value())

        assertEquals(204, delete(authed, "CHE", xiaowang).status.value())
        assertEquals(404, delete(authed, "CHE", xiaowang).status.value())
        val list = listMembers(authed, "CHE")
        assertEquals(1, list.size())
        assertEquals("小李", list[0]["displayName"].asText())
    }

    @Test
    fun `配置池后 - 指派池外409 - 池内200 - 候选不含池外`() {
        val (authed, xiaoli, xiaowang) = seed()
        setMemberCap(authed, xiaoli, "Java", 3)
        assertEquals(201, post(authed, "CHE", xiaoli).status.value()) // 池 = {小李}

        val item = createItem(authed, "CHE", "池约束", listOf(mapOf("attribute" to "Java", "minLevel" to 2)))
        val itemId = item["itemId"].asText()

        val conflict = assign(authed, itemId, xiaowang)
        assertEquals(409, conflict.status.value())
        assertEquals("该成员不在项目成员中", json.readTree(conflict.responseBody!!)["message"].asText())

        assertEquals(200, assign(authed, itemId, xiaoli).status.value()) // 池内 OK
        assertEquals(setOf(xiaoli), candidateIds(authed, itemId)) // 候选仅池内
    }

    @Test
    fun `无池行 - 全员可指派进候选(存量零配置回归)`() {
        val (authed, xiaoli, xiaowang) = seed() // 不加任何项目成员
        val item = createItem(authed, "CHE", "回归", listOf(mapOf("attribute" to "Java", "minLevel" to 2)))
        val itemId = item["itemId"].asText()

        assertEquals(200, assign(authed, itemId, xiaowang).status.value()) // 任意成员可指派
        assertEquals(3, candidateIds(authed, itemId).size) // 我 + 小李 + 小王 全员进候选
    }

    @Test
    fun `删除成员 - 项目成员行级联清 - assignee置空(V5语义不变)`() {
        val (authed, xiaoli, _) = seed()
        assertEquals(201, post(authed, "CHE", xiaoli).status.value())
        val item = createItem(authed, "CHE", "删人置空")
        val itemId = item["itemId"].asText()
        assertEquals(200, assign(authed, itemId, xiaoli).status.value())

        authed.delete().uri("/api/members/$xiaoli").exchange().expectStatus().isNoContent()

        // project_member 行随 FK 级联清 → 池回退到“未配置 = 全池”
        assertEquals(0, listMembers(authed, "CHE").size())
        // assignee 由 V5 FK(on delete set null)置空
        val itemAfter = json.readTree(
            authed.get().uri("/api/items/$itemId")
                .exchange().expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(null, itemAfter["assignee"].let { if (it.isNull) null else it.asText() })
    }

    private fun setMemberCap(authed: RestTestClient, memberId: String, attribute: String, level: Int) {
        authed.put().uri("/api/members/$memberId/capabilities")
            .body(mapOf("attribute" to attribute, "level" to level))
            .exchange().expectStatus().isOk()
    }
}
