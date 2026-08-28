package yangzhou.api.member

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MembersApiTest : AbstractApiTest() {

    @Test
    fun `成员目录含我与虚拟成员,虚拟者无凭据不可登录`() {
        val authed = bootstrapAndAuth()
        authed.post().uri("/api/members")
            .body(mapOf("displayName" to "小李"))
            .exchange().expectStatus().isCreated()

        val list = json.readTree(
            authed.get().uri("/api/members").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(2, list.size())
        val me = list.first { it["displayName"].asText() == "me" }
        val xiaoli = list.first { it["displayName"].asText() == "小李" }
        assertEquals(false, me["virtual"].asBoolean())
        assertEquals("me", me["username"].asText())
        assertEquals(true, xiaoli["virtual"].asBoolean())
        assertTrue(xiaoli["username"].isNull)

        // 虚拟成员无用户名,登录必败(笼统 401)
        rest.post().uri("/api/auth/login")
            .body(mapOf("username" to "小李", "password" to "x"))
            .exchange().expectStatus().isUnauthorized()
    }

    @Test
    fun `虚拟成员能力自评按成员维度可写,与我的能力隔离`() {
        val authed = bootstrapAndAuth()
        createSkillAttribute(authed, "Java")

        val xiaoli = json.readTree(
            authed.post().uri("/api/members").body(mapOf("displayName" to "小李"))
                .exchange().expectStatus().isCreated()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        val id = xiaoli["memberId"].asText()

        authed.put().uri("/api/members/$id/capabilities")
            .body(mapOf("attribute" to "Java", "level" to 3))
            .exchange().expectStatus().isOk()

        val liCaps = json.readTree(
            authed.get().uri("/api/members/$id/capabilities").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(3, liCaps[0]["level"].asInt())

        // 我的能力不受影响(为空)
        val myCaps = json.readTree(
            authed.get().uri("/api/capabilities").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(0, myCaps.size())
    }

    @Test
    fun `虚拟成员可删,登录账号不可删`() {
        val authed = bootstrapAndAuth()
        val xiaoli = json.readTree(
            authed.post().uri("/api/members").body(mapOf("displayName" to "小李"))
                .exchange().expectStatus().isCreated()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )

        authed.delete().uri("/api/members/${xiaoli["memberId"].asText()}")
            .exchange().expectStatus().isNoContent()

        val me = json.readTree(
            authed.get().uri("/api/members").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        ).first { it["virtual"].asBoolean() == false }
        authed.delete().uri("/api/members/${me["memberId"].asText()}")
            .exchange().expectStatus().isEqualTo(409)
    }

    @Test
    fun `可行性仍以登录成员计算(不因虚拟成员出现而漂移)`() {
        val authed = bootstrapAndAuth()
        createSkillAttribute(authed, "Java")
        setCapability(authed, "Java", 4)
        createProject(authed, "CHE")
        createItem(authed, "CHE", "x", listOf(mapOf("attribute" to "Java", "minLevel" to 3)))

        authed.post().uri("/api/members").body(mapOf("displayName" to "菜鸟"))
            .exchange().expectStatus().isCreated()

        val body = json.readTree(
            authed.get().uri("/api/projects/CHE/feasibility").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals("GREEN", body["signal"].asText()) // 我 Java=4 ≥3;虚拟成员不入算
    }
}
