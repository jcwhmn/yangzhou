package yangzhou.api.github

import tools.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals

/** JCW-113 dogfood 抓到的真 bug:列表载荷无 merged 布尔,只有 merged_at。 */
class RealGithubGatewayPrParseTest {

    private val mapper = ObjectMapper()

    @Test
    fun `列表载荷按 merged_at 判定 merged——open 与 closed 各归位`() {
        val payload = """
            [
              {"number":1,"state":"open","merged_at":null,"head":{"ref":"YPJ-1-a"},"html_url":"https://github.com/o/r/pull/1"},
              {"number":2,"state":"closed","merged_at":"2026-09-12T10:00:00Z","head":{"ref":"YPJ-1-b"},"html_url":"https://github.com/o/r/pull/2"},
              {"number":3,"state":"closed","merged_at":null,"head":{"ref":"CHE-1-c"},"html_url":"https://github.com/o/r/pull/3"}
            ]
        """.trimIndent()
        val prs = mapper.readTree(payload).map { RealGithubGateway.toGithubPr(it) }
        assertEquals("open", prs[0].state)
        assertEquals("merged", prs[1].state)
        assertEquals("closed", prs[2].state)
        assertEquals("YPJ-1-b", prs[1].headRef)
    }
}
