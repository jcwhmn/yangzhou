package yangzhou.api.checklist

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JCW-133:V9-S5 检查清单黑盒(增删改勾/完成度/级联/404)。 */
class ChecklistApiTest : AbstractApiTest() {

    private fun setup(): Pair<org.springframework.test.web.servlet.client.RestTestClient, String> {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        val itemId = createItem(authed, "CHE", "x")["itemId"].asText()
        return authed to itemId
    }

    private fun list(authed: org.springframework.test.web.servlet.client.RestTestClient, itemId: String) =
        json.readTree(
            authed.get().uri("/api/items/$itemId/checklist").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )

    @Test
    fun `增改勾删——完成度 x_y`() {
        val (authed, itemId) = setup()

        authed.post().uri("/api/items/$itemId/checklist")
            .body(mapOf("text" to "第一步"))
            .exchange().expectStatus().isCreated()
        authed.post().uri("/api/items/$itemId/checklist")
            .body(mapOf("text" to "第二步"))
            .exchange().expectStatus().isCreated()

        var cl = list(authed, itemId)
        assertEquals(2, cl["totalCount"].asInt())
        assertEquals(0, cl["doneCount"].asInt())
        assertEquals("第一步", cl["entries"][0]["text"].asText()) // position 序

        // 勾选第一条
        val firstId = cl["entries"][0]["checklistItemId"].asText()
        authed.patch().uri("/api/checklist/$firstId")
            .body(mapOf("done" to true))
            .exchange().expectStatus().isOk()
        cl = list(authed, itemId)
        assertEquals(1, cl["doneCount"].asInt())
        assertTrue(cl["entries"][0]["done"].asBoolean())

        // 改文案
        authed.patch().uri("/api/checklist/$firstId")
            .body(mapOf("text" to "第一步(改)"))
            .exchange().expectStatus().isOk()
        assertEquals("第一步(改)", list(authed, itemId)["entries"][0]["text"].asText())

        // 删除 → 总数 1
        authed.delete().uri("/api/checklist/$firstId")
            .exchange().expectStatus().isNoContent()
        assertEquals(1, list(authed, itemId)["totalCount"].asInt())

        // 空文案 400
        authed.post().uri("/api/items/$itemId/checklist")
            .body(mapOf("text" to "  "))
            .exchange().expectStatus().isBadRequest()
    }

    @Test
    fun `未知 item 或条目 404`() {
        val (authed, itemId) = setup()
        authed.post().uri("/api/items/00000000-0000-0000-0000-000000000000/checklist")
            .body(mapOf("text" to "x"))
            .exchange().expectStatus().isNotFound()
        authed.patch().uri("/api/checklist/00000000-0000-0000-0000-000000000000")
            .body(mapOf("done" to true))
            .exchange().expectStatus().isNotFound()
        authed.delete().uri("/api/checklist/00000000-0000-0000-0000-000000000000")
            .exchange().expectStatus().isNotFound()
        assertEquals(0, list(authed, itemId)["totalCount"].asInt())
    }
}
