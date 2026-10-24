package yangzhou.api.business.sprint

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** YPJ-29:看板端点——active sprint 限定 items;无 active 降级全量;completed 不参与。 */
class SprintBoardApiTest : AbstractApiTest() {

    @Test
    fun `看板——无 active 返回全量,active 后仅 sprint 内 items,完成后回到全量`() {
        val authed = bootstrapAndAuth()
        fun board(): String =
            authed.get().uri("/api/projects/BRD/board")
                .exchange().expectBody(String::class.java).returnResult().responseBody!!
        createProject(authed, "BRD")
        val attr = "看板技"
        createSkillAttribute(authed, attr)
        val i1 = createItem(authed, "BRD", "在sprint里", listOf(mapOf("attribute" to attr, "minLevel" to 1)))["itemId"].asText()
        createItem(authed, "BRD", "不在sprint里")

        // 无任何 sprint → 全量
        var node = json.readTree(board())
        assertEquals(0, node["activeSprints"].size(), board())
        assertEquals(2, node["items"].size(), board())

        // planned 不影响看板范围
        val sprintId = authed.post().uri("/api/projects/BRD/sprints")
            .body(mapOf("name" to "S1")).exchange()
            .expectBody(String::class.java).returnResult().responseBody!!
            .let { json.readTree(it) }["sprintId"].asText()
        authed.put().uri("/api/items/$i1/sprint").body(mapOf("sprintId" to sprintId))
            .exchange().expectStatus().isOk
        node = json.readTree(board())
        assertEquals(0, node["activeSprints"].size(), board())
        assertEquals(2, node["items"].size(), board())

        // 激活 → 仅 sprint 内 item
        authed.patch().uri("/api/projects/BRD/sprints/$sprintId").body(mapOf("status" to "active"))
            .exchange().expectStatus().isOk
        node = json.readTree(board())
        assertEquals(1, node["activeSprints"].size(), board())
        assertEquals(1, node["items"].size(), board())
        assertEquals(i1, node["items"][0]["itemId"].asText(), board())

        // 完成 → 回到全量(completed sprint 不参与)
        authed.patch().uri("/api/projects/BRD/sprints/$sprintId").body(mapOf("status" to "completed"))
            .exchange().expectStatus().isOk
        node = json.readTree(board())
        assertEquals(0, node["activeSprints"].size(), board())
        assertEquals(2, node["items"].size(), board())
    }
}
