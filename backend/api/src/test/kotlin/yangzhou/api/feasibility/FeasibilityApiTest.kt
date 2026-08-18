package yangzhou.api.feasibility

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 判定语义用真实 fixture(chess JCW-30/44/46、BOO-UI 家族)断言。 */
class FeasibilityApiTest : AbstractApiTest() {

    private fun seedFixture(): org.springframework.test.web.servlet.client.RestTestClient {
        val authed = bootstrapAndAuth()
        createSkillAttribute(authed, "Java")
        createSkillAttribute(authed, "架构")
        createSkillAttribute(authed, "React")
        createSkillAttribute(authed, "UI 设计")

        // 我:Java 4 / 架构 1 / React 1(真实自评)
        setCapability(authed, "Java", 4)
        setCapability(authed, "架构", 1)
        setCapability(authed, "React", 1)

        createProject(authed, "CHE")
        createItem(authed, "CHE", "auth baseline", listOf(
            mapOf("attribute" to "Java", "minLevel" to 3),
            mapOf("attribute" to "架构", "minLevel" to 2),
        )) // JCW-30:有余 + 差1级 → YELLOW
        createItem(authed, "CHE", "websocket draw", listOf(
            mapOf("attribute" to "Java", "minLevel" to 3),
        )) // JCW-44:有余 → GREEN
        createItem(authed, "CHE", "wire frontend", listOf(
            mapOf("attribute" to "React", "minLevel" to 2),
        )) // JCW-46:差1级 → YELLOW

        createProject(authed, "BOO")
        createItem(authed, "BOO", "UI 设计", listOf(
            mapOf("attribute" to "UI 设计", "minLevel" to 3),
        )) // BOO-UI:缺能力 → RED
        return authed
    }

    @Test
    fun `项目 rollup 取最差信号 + 逐条判定`() {
        val authed = seedFixture()

        val body = json.readTree(
            authed.get().uri("/api/projects/CHE/feasibility").exchange()
                .expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )

        assertEquals("YELLOW", body["signal"].asText())
        assertEquals("CHE-1", body["items"][0]["number"].asText())
        assertEquals("YELLOW", body["items"][0]["signal"].asText())

        val verdicts = body["items"][0]["verdicts"]
        assertEquals("surplus", verdicts[0]["kind"].asText()) // Java 4 ≥ 3
        assertEquals("gap", verdicts[1]["kind"].asText())     // 架构 1 < 2
        assertEquals(1, verdicts[1]["delta"].asInt())
        assertEquals(2, verdicts[1]["required"].asInt())
        assertEquals(1, verdicts[1]["actual"].asInt())

        assertEquals("GREEN", body["items"][1]["signal"].asText()) // JCW-44
    }

    @Test
    fun `整块缺失 → RED,missingCount 上报`() {
        val authed = seedFixture()

        val body = json.readTree(
            authed.get().uri("/api/projects/BOO/feasibility").exchange()
                .expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )

        assertEquals("RED", body["signal"].asText())
        assertEquals(1, body["missingCount"].asInt())
        assertEquals("missing", body["items"][0]["verdicts"][0]["kind"].asText())
    }

    @Test
    fun `item 级可行性端点`() {
        val authed = seedFixture()
        val items = json.readTree(
            authed.get().uri("/api/projects/CHE/items").exchange()
                .expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        val jcw44 = items.first { it["title"].asText() == "websocket draw" }["itemId"].asText()

        val body = json.readTree(
            authed.get().uri("/api/items/$jcw44/feasibility").exchange()
                .expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals("GREEN", body["signal"].asText())
        assertEquals("surplus", body["verdicts"][0]["kind"].asText())
    }
}
