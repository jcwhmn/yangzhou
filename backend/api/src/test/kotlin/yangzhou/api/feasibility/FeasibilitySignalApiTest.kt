package yangzhou.api.feasibility

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JCW-129:V9-S1 读模型冗余黑盒(写路径重算/聚合/workspace 范围/shortfall)。 */
class FeasibilitySignalApiTest : AbstractApiTest() {

    private fun signals(authed: org.springframework.test.web.servlet.client.RestTestClient, key: String): Pair<String?, String?> {
        val proj = json.readTree(
            authed.get().uri("/api/projects").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        ).first { it["key"].asText() == key }
        val itemSignal = json.readTree(
            authed.get().uri("/api/projects/$key/items").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )[0]["feasSignal"].asText(null)
        return proj["feasSignal"].asText(null) to itemSignal
    }

    @Test
    fun `冗余随写路径重算——requirement 与 capability 变更——workspace 范围`() {
        val authed = bootstrapAndAuth()
        authed.post().uri("/api/attributes")
            .body(mapOf("name" to "React", "kind" to "skill", "leveled" to true))
            .exchange().expectStatus().isCreated()
        createProject(authed, "CHE")
        createProject(authed, "SEC")
        val i1 = createItem(authed, "CHE", "x")["itemId"].asText()
        val i2 = createItem(authed, "SEC", "y")["itemId"].asText()

        // 无需求 → GREEN
        val (pSig, iSig) = signals(authed, "CHE")
        assertEquals("GREEN", pSig)
        assertEquals("GREEN", iSig)

        // 加需求(无能力)→ item RED,project RED
        authed.put().uri("/api/items/$i1/requirements")
            .body(mapOf("requirements" to listOf(mapOf("attribute" to "React", "minLevel" to 2))))
            .exchange().expectStatus().isOk()
        assertEquals("RED" to "RED", signals(authed, "CHE"))

        // 能力 3 ≥ 2 → satisfied → GREEN
        authed.put().uri("/api/capabilities")
            .body(mapOf("attribute" to "React", "level" to 3))
            .exchange().expectStatus().isOk()
        assertEquals("GREEN" to "GREEN", signals(authed, "CHE"))

        // 能力降到 1 → gap → YELLOW;且 workspace 重算不影响 SEC(仍 GREEN)
        authed.put().uri("/api/capabilities")
            .body(mapOf("attribute" to "React", "level" to 1))
            .exchange().expectStatus().isOk()
        assertEquals("YELLOW" to "YELLOW", signals(authed, "CHE"))
        assertEquals("GREEN" to "GREEN", signals(authed, "SEC").let { it.first to it.second }) // SEC item 无需求

        // SEC 也加同属性需求(cap 仍 =1)→ gap → YELLOW
        authed.put().uri("/api/items/$i2/requirements")
            .body(mapOf("requirements" to listOf(mapOf("attribute" to "React", "minLevel" to 2))))
            .exchange().expectStatus().isOk()
        assertEquals("YELLOW", signals(authed, "SEC").second)
        // 能力升到 2 → 两个项目(需求 ≥2)都满足 → GREEN(workspace 重算)
        authed.put().uri("/api/capabilities")
            .body(mapOf("attribute" to "React", "level" to 2))
            .exchange().expectStatus().isOk()
        assertEquals("GREEN", signals(authed, "CHE").second)
        assertEquals("GREEN", signals(authed, "SEC").second)
    }

    @Test
    fun `shortfall 聚合——gap 汇总正确`() {
        val authed = bootstrapAndAuth()
        authed.post().uri("/api/attributes")
            .body(mapOf("name" to "React", "kind" to "skill", "leveled" to true))
            .exchange().expectStatus().isCreated()
        createProject(authed, "CHE")
        val i1 = createItem(authed, "CHE", "x")["itemId"].asText()
        authed.put().uri("/api/items/$i1/requirements")
            .body(mapOf("requirements" to listOf(mapOf("attribute" to "React", "minLevel" to 2))))
            .exchange().expectStatus().isOk()

        val shortfall = json.readTree(
            authed.get().uri("/api/shortfall").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(1, shortfall.size())
        assertEquals("React", shortfall[0]["attribute"].asText())
        assertEquals(0, shortfall[0]["deltaSum"].asInt()) // 无能力 = missing,delta 无
        assertEquals(1, shortfall[0]["missingCount"].asInt())
        assertEquals("CHE-1", shortfall[0]["items"][0]["number"].asText())
    }

    @Test
    fun `recompute 兜底端点`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        val res = authed.post().uri("/api/projects/CHE/recompute").exchange()
            .expectStatus().isOk()
            .expectBody(String::class.java).returnResult().responseBody!!
        assertTrue(res.contains("signal"))
    }
}
