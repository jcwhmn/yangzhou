package yangzhou.api.dependency

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JCW-131:V9-S3 依赖黑盒(增删查/自依赖/成环/重复/blocked 标识)。 */
class DependencyApiTest : AbstractApiTest() {

    private fun setup(): Triple<org.springframework.test.web.servlet.client.RestTestClient, String, String> {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        val i1 = createItem(authed, "CHE", "x")["itemId"].asText()
        val i2 = createItem(authed, "CHE", "y")["itemId"].asText()
        return Triple(authed, i1, i2)
    }

    private fun deps(authed: org.springframework.test.web.servlet.client.RestTestClient, itemId: String) =
        json.readTree(
            authed.get().uri("/api/items/$itemId/dependencies").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )

    @Test
    fun `增删查——blocked 标识随终态翻转`() {
        val (authed, i1, i2) = setup()

        authed.post().uri("/api/items/$i1/dependencies")
            .body(mapOf("dependsOnItemId" to i2))
            .exchange().expectStatus().isCreated()

        val list = deps(authed, i1)
        assertEquals(1, list["dependencies"].size())
        assertEquals("CHE-2", list["dependencies"][0]["number"].asText())
        assertTrue(list["blocked"].asBoolean()) // 依赖 y 未终态

        // y 到终态 → i1 不再被阻塞(先指派 y:开工须有主)
        val meId = json.readTree(
            authed.get().uri("/api/members").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        ).first { !it["virtual"].asBoolean() }["memberId"].asText()
        authed.put().uri("/api/items/$i2/assignee")
            .body(mapOf("assigneeItemId" to meId))
            .exchange().expectStatus().isOk()
        val yStatuses = json.readTree(
            authed.get().uri("/api/projects/CHE").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )["statuses"]
        authed.patch().uri("/api/items/$i2")
            .body(mapOf("statusItemId" to yStatuses[4]["statusId"].asText()))
            .exchange().expectStatus().isOk()

        val after = deps(authed, i1)
        assertTrue(after["dependencies"][0]["final"].asBoolean())
        assertTrue(!after["blocked"].asBoolean())

        // 删除
        val rowId = after["dependencies"][0]["dependencyItemId"].asText()
        authed.delete().uri("/api/dependencies/$rowId")
            .exchange().expectStatus().isNoContent()
        assertEquals(0, deps(authed, i1)["dependencies"].size())
    }

    @Test
    fun `自依赖与跨项目 400——重复 409——成环 400`() {
        val (authed, i1, i2) = setup()

        // 自依赖
        authed.post().uri("/api/items/$i1/dependencies")
            .body(mapOf("dependsOnItemId" to i1))
            .exchange().expectStatus().isBadRequest()

        // 跨项目
        createProject(authed, "SEC")
        val other = createItem(authed, "SEC", "z")["itemId"].asText()
        authed.post().uri("/api/items/$i1/dependencies")
            .body(mapOf("dependsOnItemId" to other))
            .exchange().expectStatus().isBadRequest()

        // 正常 + 重复
        authed.post().uri("/api/items/$i1/dependencies")
            .body(mapOf("dependsOnItemId" to i2))
            .exchange().expectStatus().isCreated()
        authed.post().uri("/api/items/$i1/dependencies")
            .body(mapOf("dependsOnItemId" to i2))
            .exchange().expectStatus().isEqualTo(409)

        // 成环:i2 依赖 i1(此时 i1 已依赖 i2)→ 400
        authed.post().uri("/api/items/$i2/dependencies")
            .body(mapOf("dependsOnItemId" to i1))
            .exchange().expectStatus().isBadRequest()

        // 看板 blocked 标识
        val board = json.readTree(
            authed.get().uri("/api/projects/CHE/items").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        val x = board.first { it["title"].asText() == "x" }
        assertTrue(x["blocked"].asBoolean())
    }
}
