package yangzhou.api.candidate

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CandidatesApiTest : AbstractApiTest() {

    private fun addMember(authed: org.springframework.test.web.servlet.client.RestTestClient, name: String): String =
        json.readTree(
            authed.post().uri("/api/members").body(mapOf("displayName" to name))
                .exchange().expectStatus().isCreated()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )["memberId"].asText()

    private fun setMemberCap(authed: org.springframework.test.web.servlet.client.RestTestClient, memberId: String, attribute: String, level: Int?) {
        authed.put().uri("/api/members/$memberId/capabilities")
            .body(mapOf("attribute" to attribute, "level" to level))
            .exchange().expectStatus().isOk()
    }

    /** fixture:小李(Java3/React3 全绿)、我(Java4/React1 差1)、小王(React1 差1);item 需 React≥2。 */
    private fun seed(): Pair<org.springframework.test.web.servlet.client.RestTestClient, String> {
        val authed = bootstrapAndAuth()
        createSkillAttribute(authed, "Java")
        createSkillAttribute(authed, "React")
        setCapability(authed, "Java", 4)
        setCapability(authed, "React", 1)

        val xiaoli = addMember(authed, "小李")
        setMemberCap(authed, xiaoli, "Java", 3)
        setMemberCap(authed, xiaoli, "React", 3)
        val xiaowang = addMember(authed, "小王")
        setMemberCap(authed, xiaowang, "React", 1)

        createProject(authed, "CHE")
        val item = createItem(authed, "CHE", "wire frontend", listOf(mapOf("attribute" to "React", "minLevel" to 2)))
        return authed to item["itemId"].asText()
    }

    @Test
    fun `候选含我与虚拟成员,排序=缺门少→差距小,无加权`() {
        val (authed, itemId) = seed()

        val list = json.readTree(
            authed.get().uri("/api/items/$itemId/candidates").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(3, list.size())

        val first = list[0]
        assertEquals(1, first["rank"].asInt())
        assertEquals("小李", first["displayName"].asText())
        assertEquals(true, first["virtual"].asBoolean())
        assertEquals("GREEN", first["signal"].asText())
        assertEquals(0, first["missingCount"].asInt())
        assertEquals("surplus", first["verdicts"][0]["kind"].asText()) // React 3 ≥ 2

        // 我与小王同为(YELLOW, delta=1)——都在,名次在 小李 之后
        val names = list.map { it["displayName"].asText() }
        assertEquals(listOf("小李", "me", "小王"), names) // 稳定排序=成员插入序
        val me = list[1]
        assertEquals("gap", me["verdicts"][0]["kind"].asText())
        assertEquals(1, me["verdicts"][0]["delta"].asInt())
        assertEquals(false, me["virtual"].asBoolean())
    }

    @Test
    fun `指派与取消,item wire 带 assignee 显示名`() {
        val (authed, itemId) = seed()
        val xiaoli = json.readTree(
            authed.get().uri("/api/members").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        ).first { it["displayName"].asText() == "小李" }["memberId"].asText()

        val assigned = json.readTree(
            authed.put().uri("/api/items/$itemId/assignee")
                .body(mapOf("assigneeItemId" to xiaoli))
                .exchange().expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals("小李", assigned["assignee"].asText())

        val inList = json.readTree(
            authed.get().uri("/api/projects/CHE/items").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        ).first { it["itemId"].asText() == itemId }
        assertEquals("小李", inList["assignee"].asText())

        // 取消
        val cleared = json.readTree(
            authed.put().uri("/api/items/$itemId/assignee")
                .body(mapOf("assigneeItemId" to null))
                .exchange().expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertTrue(cleared["assignee"].isNull)

        // 不存在成员 404
        authed.put().uri("/api/items/$itemId/assignee")
            .body(mapOf("assigneeItemId" to "00000000-0000-0000-0000-000000000000"))
            .exchange().expectStatus().isNotFound()
    }

    @Test
    fun `被指派成员删除 → assignee 置空(人走事在)`() {
        val (authed, itemId) = seed()
        val xiaoli = json.readTree(
            authed.get().uri("/api/members").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        ).first { it["displayName"].asText() == "小李" }["memberId"].asText()

        authed.put().uri("/api/items/$itemId/assignee")
            .body(mapOf("assigneeItemId" to xiaoli))
            .exchange().expectStatus().isOk()

        authed.delete().uri("/api/members/$xiaoli")
            .exchange().expectStatus().isNoContent()

        val item = json.readTree(
            authed.get().uri("/api/items/$itemId").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertTrue(item["assignee"].isNull)
    }
}
