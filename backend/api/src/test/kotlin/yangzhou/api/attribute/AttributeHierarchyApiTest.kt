package yangzhou.api.attribute

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JCW-139:V10-S1 层次结构黑盒(分类/挂载/移动/删除保护/未分类)。 */
class AttributeHierarchyApiTest : AbstractApiTest() {

    @Test
    fun `分类与叶子——挂载_移动_未分类——删除保护`() {
        val authed = bootstrapAndAuth()

        // 建分类 + 两个叶子挂载
        val cat = json.readTree(
            authed.post().uri("/api/attributes")
                .body(mapOf("name" to "后端", "kind" to "category"))
                .exchange().expectStatus().isCreated()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals("category", cat["kind"].asText())
        val catId = cat["attributeId"].asText()

        authed.post().uri("/api/attributes")
            .body(mapOf("name" to "kotlin", "kind" to "skill", "leveled" to true, "parentId" to catId))
            .exchange().expectStatus().isCreated()
        authed.post().uri("/api/attributes")
            .body(mapOf("name" to "前端", "kind" to "category"))
            .exchange().expectStatus().isCreated()

        // 分类下再挂分类 → 400
        authed.post().uri("/api/attributes")
            .body(mapOf("name" to "坏分类", "kind" to "category", "parentId" to catId))
            .exchange().expectStatus().isBadRequest()

        // 列表:叶子带分类名
        val all = json.readTree(
            authed.get().uri("/api/attributes").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        val kotlin = all.first { it["name"].asText() == "kotlin" }
        assertEquals("后端", kotlin["categoryName"].asText())

        // 叶子移动:后端 → 前端
        val frontendId = all.first { it["name"].asText() == "前端" }["attributeId"].asText()
        authed.patch().uri("/api/attributes/${kotlin["attributeId"].asText()}")
            .body(mapOf("parentObjectId" to frontendId))
            .exchange().expectStatus().isOk()
        assertEquals("前端", list(authed, "kotlin")["categoryName"].asText())

        // 分类有子项删除 → 409(前端下还有 kotlin)
        authed.delete().uri("/api/attributes/$frontendId")
            .exchange().expectStatus().isEqualTo(409)

        // 分类不能变更类型
        authed.patch().uri("/api/attributes/$frontendId")
            .body(mapOf("kind" to "skill"))
            .exchange().expectStatus().isBadRequest()

        // 叶子移到未分类
        authed.patch().uri("/api/attributes/${kotlin["attributeId"].asText()}")
            .body(mapOf("unassign" to true))
            .exchange().expectStatus().isOk()
        assertTrue(list(authed, "kotlin")["categoryName"].isNull)
    }

    private fun list(authed: org.springframework.test.web.servlet.client.RestTestClient, name: String) =
        json.readTree(
            authed.get().uri("/api/attributes").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        ).first { it["name"].asText() == name }
}
