package yangzhou.api.business.search

import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** D7(spec 0052):搜索通配符按字面匹配——用户输入的 %/_ 不再当通配符。 */
class SearchWildcardApiTest : AbstractApiTest() {
    private fun search(
        authed: RestTestClient,
        q: String,
    ): Int = authed
        .get()
        .uri { b -> b.path("/api/search").queryParam("q", q).build() }
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String::class.java)
        .returnResult()
        .responseBody!!
        .let { json.readTree(it).size() }

    @Test
    fun `标题含百分号——按字面命中,不过度匹配`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        createItem(authed, "CHE", "增长 100% 计划")
        createItem(authed, "CHE", "增长 100 倍计划")

        // 字面 "100%" 只命中第一条;"1" 后跟任意两字符再跟 "%" 的通配语义不复存在
        assertEquals(1, search(authed, "100%"))
        assertEquals(2, search(authed, "计划"))
        println("DEBUG pattern=" + SearchService.likePattern("100%"))
        // 通配语义不再存在:字面 "增长 100_ 计划" 无命中(真实标题是 "100%")
        assertEquals(0, search(authed, "增长 100_ 计划"))
    }

    @Test
    fun `下划线按字面匹配`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        createItem(authed, "CHE", "foo_bar 任务")
        createItem(authed, "CHE", "fooXbar 任务")

        assertEquals(1, search(authed, "foo_bar"))
    }
}
