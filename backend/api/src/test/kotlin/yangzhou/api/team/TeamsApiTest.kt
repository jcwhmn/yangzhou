package yangzhou.api.team

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TeamsApiTest : AbstractApiTest() {


    private fun teamIdOf(body: com.fasterxml.jackson.databind.JsonNode): String = body["teamId"].asText()

    private fun createMember(authed: org.springframework.test.web.servlet.client.RestTestClient, name: String): String =
        json.readTree(
            authed.post().uri("/api/members").body(mapOf("displayName" to name))
                .exchange().expectStatus().isCreated()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )["memberId"].asText()

    @Test
    fun `建组重名 409,改名可用`() {
        val authed = bootstrapAndAuth()
        val team = json.readTree(
            authed.post().uri("/api/teams").body(mapOf("name" to "后端组"))
                .exchange().expectStatus().isCreated()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        authed.post().uri("/api/teams").body(mapOf("name" to "后端组"))
            .exchange().expectStatus().isEqualTo(409)

        authed.patch().uri("/api/teams/${teamIdOf(team)}").body(mapOf("name" to "平台组"))
            .exchange().expectStatus().isOk()
    }

    @Test
    fun `成员进出组,可属多组,重复加入幂等`() {
        val authed = bootstrapAndAuth()
        val backend = json.readTree(
            authed.post().uri("/api/teams").body(mapOf("name" to "后端组"))
                .exchange().expectStatus().isCreated()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        val frontend = json.readTree(
            authed.post().uri("/api/teams").body(mapOf("name" to "前端组"))
                .exchange().expectStatus().isCreated()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        val xiaoli = createMember(authed, "小李")
        val me = json.readTree(
            authed.get().uri("/api/members").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        ).first { !it["virtual"].asBoolean() }["memberId"].asText()

        // 小李进后端组(两次,幂等);我进前端组
        listOf(1, 2).forEach { _ ->
            val t = json.readTree(
                authed.post().uri("/api/teams/${teamIdOf(backend)}/members").body(mapOf("memberId" to xiaoli))
                    .exchange().expectStatus().isOk()
                    .expectBody(String::class.java).returnResult().responseBody!!,
            )
            assertEquals(1, t["members"].size())
        }
        authed.post().uri("/api/teams/${teamIdOf(frontend)}/members").body(mapOf("memberId" to me))
            .exchange().expectStatus().isOk()

        // 小李再进前端组 = 跨双组
        authed.post().uri("/api/teams/${teamIdOf(frontend)}/members").body(mapOf("memberId" to xiaoli))
            .exchange().expectStatus().isOk()

        val teams = json.readTree(
            authed.get().uri("/api/teams").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        val be = teams.first { it["name"].asText() == "后端组" }
        val fe = teams.first { it["name"].asText() == "前端组" }
        assertEquals(listOf("小李"), be["members"].map { it["displayName"].asText() })
        assertEquals(2, fe["members"].size())

        // 移出
        authed.delete().uri("/api/teams/${teamIdOf(backend)}/members/$xiaoli")
            .exchange().expectStatus().isNoContent()
        val be2 = json.readTree(
            authed.get().uri("/api/teams").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        ).first { it["name"].asText() == "后端组" }
        assertEquals(0, be2["members"].size())
    }

    @Test
    fun `删组级联清成员关系`() {
        val authed = bootstrapAndAuth()
        val team = json.readTree(
            authed.post().uri("/api/teams").body(mapOf("name" to "临时组"))
                .exchange().expectStatus().isCreated()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        val xiaoli = createMember(authed, "小李")
        authed.post().uri("/api/teams/${teamIdOf(team)}/members").body(mapOf("memberId" to xiaoli))
            .exchange().expectStatus().isOk()

        authed.delete().uri("/api/teams/${teamIdOf(team)}")
            .exchange().expectStatus().isNoContent()

        val teams = json.readTree(
            authed.get().uri("/api/teams").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(0, teams.size())
        // 成员本体仍在(删组不删人)
        val members = json.readTree(
            authed.get().uri("/api/members").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(2, members.size())
    }
}
