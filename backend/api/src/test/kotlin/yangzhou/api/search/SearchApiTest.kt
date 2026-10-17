package yangzhou.api.search

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import yangzhou.api.AbstractApiTest

/** YPJ-63(spec 0048):全局搜索命中编号——编号直达 URL 解析与 /search 页共用本端点。 */
class SearchApiTest : AbstractApiTest() {

    @Test
    fun `按标题关键词命中`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "SRA")
        createItem(authed, "SRA", "搜索样例蓝香蕉")
        val body = authed.get().uri("/api/search?q=蓝香蕉")
            .exchange().expectStatus().isOk()
            .expectBody(String::class.java).returnResult().responseBody!!
        val hits = json.readTree(body)
        assertEquals(1, hits.size())
        assertEquals("SRA-1", hits[0]["number"].asText())
    }

    @Test
    fun `按编号命中——key 数字混合与大小写不敏感`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "SR9")
        createItem(authed, "SR9", "编号命中样例")
        val body = authed.get().uri("/api/search?q=sr9-1")
            .exchange().expectStatus().isOk()
            .expectBody(String::class.java).returnResult().responseBody!!
        val hits = json.readTree(body)
        assertEquals(1, hits.size(), "编号 ILIKE 应命中(大小写不敏感)")
        assertEquals("SR9-1", hits[0]["number"].asText())
        assertEquals("SR9", hits[0]["projectKey"].asText())
    }

    @Test
    fun `软删不命中,查无返回空数组`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "SRB")
        val item = createItem(authed, "SRB", "将删除的独有词石榴")
        authed.delete().uri("/api/items/${item["itemId"].asText()}")
            .exchange().expectStatus().isNoContent()
        val body = authed.get().uri("/api/search?q=石榴")
            .exchange().expectStatus().isOk()
            .expectBody(String::class.java).returnResult().responseBody!!
        assertEquals(0, json.readTree(body).size(), "软删 item 不应命中")
    }
}
