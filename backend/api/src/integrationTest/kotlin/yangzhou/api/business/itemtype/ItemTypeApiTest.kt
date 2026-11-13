package yangzhou.api.business.itemtype

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** YPJ-177(spec 0123):item-types 全链路——种子/CRUD/校验/删除守卫/metrics 显示名注入。 */
class ItemTypeApiTest : AbstractApiTest() {
    private fun list(
        authed: RestTestClient,
        key: String,
    ): JsonNode = json.readTree(
        authed
            .get()
            .uri("/api/projects/$key/item-types")
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!,
    )

    private fun createItemType(
        authed: RestTestClient,
        key: String,
        body: Map<String, String>,
    ) = authed
        .post()
        .uri("/api/projects/$key/item-types")
        .body(body)
        .exchange()
        .expectBody(String::class.java)
        .returnResult()

    @Test
    fun `种子 CRUD 与校验`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "ITY")

        // 建项目即四种占位(spec 0123 修订:builtin 概念移除,种子可增删改)
        val seeds = list(authed, "ITY")
        assertEquals(4, seeds.size())
        assertEquals(
            listOf("task", "bug", "story", "goal"),
            seeds.map { it["typeKey"].asText() },
        )

        // 新增自定义 → 列表 +1;重复 409;坏 slug 400
        assertEquals(201, createItemType(authed, "ITY", mapOf("typeKey" to "feat", "name" to "Feature")).status.value())
        assertEquals(5, list(authed, "ITY").size())
        assertEquals(409, createItemType(authed, "ITY", mapOf("typeKey" to "feat", "name" to "X")).status.value())
        assertEquals(400, createItemType(authed, "ITY", mapOf("typeKey" to "EPIC", "name" to "X")).status.value())

        // 改名(typeKey 不可变,仅 name)
        val patched =
            json.readTree(
                authed
                    .patch()
                    .uri("/api/projects/ITY/item-types/feat")
                    .body(mapOf("name" to "特性"))
                    .exchange()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )
        assertEquals("特性", patched["name"].asText())
        assertEquals("feat", patched["typeKey"].asText())
    }

    @Test
    fun `动态 type 建 item 与删除守卫`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "ITD")
        createItemType(authed, "ITD", mapOf("typeKey" to "feat", "name" to "Feature"))

        // item 用动态 type 建/改走通
        val itemId =
            json.readTree(
                authed
                    .post()
                    .uri("/api/projects/ITD/items")
                    .body(mapOf("title" to "动态类型件", "type" to "feat"))
                    .exchange()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )["itemId"].asText()

        // 在用删 → 409 带计数;迁移后删 → 204;builtin 删 → 409
        val blocked =
            authed
                .delete()
                .uri("/api/projects/ITD/item-types/feat")
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(409, blocked.status.value(), blocked.responseBody)
        assertTrue(blocked.responseBody!!.contains("1 个 item"))
        authed
            .patch()
            .uri("/api/items/$itemId")
            .body(mapOf("type" to "task"))
            .exchange()
            .expectStatus()
            .isOk()
        authed.delete().uri("/api/projects/ITD/item-types/feat").exchange().expectStatus().isNoContent()

        // 修订:种子也是占位,空即可删(goal 无引用)
        authed.delete().uri("/api/projects/ITD/item-types/goal").exchange().expectStatus().isNoContent()

        // metrics byType 显示名注入(定义缺失回退 key)
        authed
            .post()
            .uri("/api/projects/ITD/items")
            .body(mapOf("title" to "任务件"))
            .exchange()
            .expectStatus()
            .isCreated()
        val workload =
            json.readTree(
                authed
                    .get()
                    .uri("/api/projects/ITD/metrics/workload")
                    .exchange()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!,
            )
        assertEquals("Task", workload["byType"][0]["name"].asText())
        assertEquals("task", workload["byType"][0]["type"].asText())
    }
}
