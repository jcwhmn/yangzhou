package yangzhou.api.business.item

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** YPJ-130(spec 0093):表格后端分页黑盒——契约真值表见 spec §1。 */
class ItemsPageApiTest : AbstractApiTest() {
    private lateinit var authed: RestTestClient

    private fun setupProject(key: String, count: Int = 25) {
        authed = bootstrapAndAuth()
        createProject(authed, key)
        (1..count).forEach { createItem(authed, key, "i%02d".format(it)) }
    }

    private fun page(key: String, qs: String): JsonNode {
        val r =
            authed
                .get()
                .uri("/api/projects/$key/items/page?$qs")
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertTrue(r.status.value() < 300, "GET page?$qs -> ${r.status.value()} ${r.responseBody}")
        return json.readTree(r.responseBody!!)
    }

    private fun rawPage(key: String, qs: String) = authed
        .get()
        .uri("/api/projects/$key/items/page?$qs")
        .exchange()
        .expectBody(String::class.java)
        .returnResult()

    @Test
    fun `分页切片与 total,越末页空集不报错`() {
        setupProject("PGPAGE")
        val p0 = page("PGPAGE", "page=0&size=20")
        assertEquals(25L, p0["total"].asLong())
        assertEquals(20, p0["items"].size())
        assertEquals(0, p0["page"].asInt())
        val p1 = page("PGPAGE", "page=1&size=20")
        assertEquals(5, p1["items"].size())
        val p9 = page("PGPAGE", "page=9&size=20")
        assertEquals(0, p9["items"].size())
        assertEquals(25L, p9["total"].asLong())
        assertTrue(p0["items"][0]["number"].asText().isNotEmpty(), "ItemDto 字段齐全")
    }

    @Test
    fun `排序跨页稳定(含多键与派生列字段)`() {
        setupProject("PGSORT")
        val desc0 = page("PGSORT", "size=20&sort=title:desc")
        assertEquals("i25", desc0["items"][0]["title"].asText())
        val asc = page("PGSORT", "size=20&sort=title:asc")
        assertEquals("i01", asc["items"][0]["title"].asText())
        // 多键:主键 tie(全部 overdue=false)→ number 兜底
        val multi = page("PGSORT", "size=20&sort=overdue:desc,number:asc")
        assertEquals("i01", multi["items"][0]["title"].asText())
    }

    @Test
    fun `搜索子串与状态筛选`() {
        setupProject("PGQ")
        val q = page("PGQ", "query=i2")
        assertEquals(6L, q["total"].asLong(), "i20..i25 命中(编号/标题不含 i2)")
        assertEquals(6, q["items"].size())
        val statusName =
            json
                .readTree(
                    authed
                        .get()
                        .uri("/api/projects/PGQ")
                        .exchange()
                        .expectBody(String::class.java)
                        .returnResult()
                        .responseBody!!,
                )["statuses"][0]["name"]
                .asText()
        assertEquals(25L, page("PGQ", "status=$statusName")["total"].asLong(), "状态名精确匹配")
        assertEquals(0L, page("PGQ", "status=不存在状态")["total"].asLong())
    }

    @Test
    fun `负例 400 统一形状`() {
        setupProject("PGNEG", count = 1)
        for (qs in listOf("sort=evil:asc", "size=15", "page=-1", "sort=title")) {
            val result = rawPage("PGNEG", qs)
            assertEquals(400, result.status.value(), "qs=$qs 应 400")
            val body = json.readTree(result.responseBody!!)
            assertTrue(body.has("message"), "qs=$qs 错误形状含 message")
        }
    }
}
