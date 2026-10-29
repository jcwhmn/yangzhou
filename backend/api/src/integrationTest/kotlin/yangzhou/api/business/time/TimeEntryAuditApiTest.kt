package yangzhou.api.business.time

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** spec 0060 / YPJ-61:time_entry 审计四元组——创建与 stop 更新都落当前成员(内部 id);列 nullable 但新写入必有值。 */
class TimeEntryAuditApiTest : AbstractApiTest() {

    @Test
    fun `创建与 stop 更新——审计列落当前成员`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        val itemId = createItem(authed, "CHE", "审计工时")["itemId"].asText()
        val meId = jdbc.queryForObject("select object_id from member where username = 'me'", java.util.UUID::class.java)

        authed.post().uri("/api/items/$itemId/time-entries")
            .body(mapOf<String, Any?>("minutes" to null))
            .exchange().expectStatus().isCreated()

        val entryId = jdbc.queryForList("select object_id from time_entry")[0]["object_id"].toString()
        authed.post().uri("/api/time-entries/$entryId/stop")
            .exchange().expectStatus().isOk()

        val row = jdbc.queryForMap(
            "select created_at, updated_at, created_by_object_id, updated_by_object_id from time_entry where object_id = ?",
            java.util.UUID.fromString(entryId),
        )
        assertEquals(meId, row["created_by_object_id"])
        assertEquals(meId, row["updated_by_object_id"])
        assertNotNull(row["created_at"])
        assertNotNull(row["updated_at"])
    }
}
