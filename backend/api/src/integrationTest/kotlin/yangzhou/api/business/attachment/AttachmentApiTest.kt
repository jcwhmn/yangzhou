package yangzhou.api.business.attachment

import org.springframework.beans.factory.annotation.Autowired
import org.junit.jupiter.api.BeforeEach
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** YPJ-50:附件端点黑盒(fake storage seam + Testcontainers 真 DB;presign 参数断言在 fake)。fake 在单测侧 FakeObjectStorage.kt,集成 classpath 复用。 */
@Import(FakeStorageConfig::class)
class AttachmentApiTest : AbstractApiTest() {

    @Autowired
    lateinit var fake: FakeObjectStorage

    private lateinit var authed: RestTestClient

    @BeforeEach
    fun resetFake() {
        fake.puts.clear(); fake.gets.clear(); fake.deletes.clear() // fake 不随 cleanDatabase 复位;别依赖用例序
    }

    private fun setup(): String {
        authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        return createItem(authed, "CHE", "x")["itemId"].asText()
    }

    @Test
    fun `注册——落行并返回上传 URL——列表——下载 URL——删除`() {
        val itemId = setup()

        val created = json.readTree(
            authed.post().uri("/api/items/$itemId/attachments")
                .body(mapOf("filename" to "报告.pdf", "contentType" to "application/pdf", "sizeBytes" to 1024))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(1, fake.puts.size)
        assertEquals("application/pdf", fake.puts[0].second)
        assertContains(fake.puts[0].first, "报告.pdf")
        assertEquals("http://fake/put/${fake.puts[0].first}", created["uploadUrl"].asText())

        val rows = jdbc.queryForList("select filename, size_bytes, object_key from attachment")
        assertEquals(1, rows.size)
        assertEquals("报告.pdf", rows[0]["filename"])
        assertEquals(rows[0]["object_key"], fake.puts[0].first)

        val list = json.readTree(
            authed.get().uri("/api/items/$itemId/attachments").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(1, list.size())
        assertEquals("报告.pdf", list[0]["filename"].asText())

        val objectId = created["attachmentId"].asText()
        val dl = json.readTree(
            authed.get().uri("/api/items/$itemId/attachments/$objectId/download-url").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals("http://fake/get/${fake.puts[0].first}", dl["downloadUrl"].asText())

        authed.delete().uri("/api/items/$itemId/attachments/$objectId").exchange()
            .expectStatus().isNoContent()
        assertEquals(1, fake.deletes.size)
        assertEquals(0, jdbc.queryForList("select id from attachment").size)
    }

    @Test
    fun `负例——404 未知 item 与未知附件、400 超限与非法扩展`() {
        val itemId = setup()
        val unknown = UUID.randomUUID().toString()

        authed.post().uri("/api/items/$unknown/attachments")
            .body(mapOf("filename" to "a.pdf", "contentType" to "application/pdf", "sizeBytes" to 1))
            .exchange().expectStatus().isNotFound()

        authed.post().uri("/api/items/$itemId/attachments")
            .body(mapOf("filename" to "big.pdf", "contentType" to "application/pdf", "sizeBytes" to 999_999_999_999L))
            .exchange().expectStatus().isEqualTo(400)

        authed.post().uri("/api/items/$itemId/attachments")
            .body(mapOf("filename" to "virus.exe", "contentType" to "application/x-msdownload", "sizeBytes" to 1))
            .exchange().expectStatus().isEqualTo(400)
        assertEquals(0, fake.puts.size)

        authed.get().uri("/api/items/$itemId/attachments/$unknown/download-url").exchange()
            .expectStatus().isNotFound()
    }
}
