package yangzhou.api.business.item

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** audit 四元组集成验证(spec 0051):updated_at 前进 + created_by_object_id/updated_by_object_id 落当前成员 objectId。 */
class ItemAuditApiTest : AbstractApiTest() {

    @Test
    fun `item 更新后 updated_at 前进,updated_by_object_id 为当前成员`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        val item = createItem(authed, "CHE", "旧标题")

        val before = jdbc.queryForMap(
            "select created_at, updated_at, created_by_object_id, updated_by_object_id from item where object_id = ?",
            java.util.UUID.fromString(item["itemId"].asText()),
        )
        assertNotNull(before["created_by_object_id"], "创建即应有 created_by_object_id")
        assertEquals(before["created_by_object_id"], before["updated_by_object_id"])

        // 同秒内也可能 created==updated,故断言 updated_at 不早于 created_at;再改一次确保前进
        assertTrue(!(before["updated_at"] as java.sql.Timestamp).toInstant().isBefore((before["created_at"] as java.sql.Timestamp).toInstant()))

        authed.patch().uri("/api/items/${item["itemId"].asText()}")
            .body(mapOf("title" to "新标题"))
            .exchange().expectStatus().isOk()

        val after = jdbc.queryForMap(
            "select updated_at, updated_by_object_id from item where object_id = ?",
            java.util.UUID.fromString(item["itemId"].asText()),
        )
        assertTrue(
            (after["updated_at"] as java.sql.Timestamp).toInstant().isAfter((before["updated_at"] as java.sql.Timestamp).toInstant()),
            "更新后 updated_at 必须前进(修 auditing 前 copy 不动它)",
        )
        val meId = jdbc.queryForObject("select object_id from member where username = 'me'", java.util.UUID::class.java)
        assertEquals(meId, after["updated_by_object_id"])
    }

    @Test
    fun `不支持的方法返回 405 通用体`() {
        val authed = bootstrapAndAuth()
        val body = json.readTree(
            authed.delete().uri("/api/projects").exchange()
                .expectStatus().isEqualTo(405)
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals("METHOD_NOT_ALLOWED", body["code"].asText())
    }
}
