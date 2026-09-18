package yangzhou.api.favorite

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JCW-130:V9-S2 收藏黑盒(PUT 幂等/GET 序/DELETE/跨项目)。 */
class FavoriteApiTest : AbstractApiTest() {

    @Test
    fun `收藏增删查——PUT 幂等——列表按创建序`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "AAA")
        createProject(authed, "BBB")

        authed.put().uri("/api/projects/AAA/favorite").exchange().expectStatus().isOk()
        authed.put().uri("/api/projects/AAA/favorite").exchange().expectStatus().isOk() // 幂等
        authed.put().uri("/api/projects/BBB/favorite").exchange().expectStatus().isOk()

        val favs = json.readTree(
            authed.get().uri("/api/favorites").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        println("DEBUG_FAVS=" + favs.toString())
        assertEquals(2, favs.size(), "favs=\$favs")
        assertEquals("AAA", favs[0]["key"].asText())

        // 移除 AAA → 只剩 BBB
        authed.delete().uri("/api/projects/AAA/favorite")
            .exchange().expectStatus().isNoContent()
        val after = json.readTree(
            authed.get().uri("/api/favorites").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(1, after.size())
        assertEquals("BBB", after[0]["key"].asText())
    }

    @Test
    fun `未知项目 404——未收藏 DELETE 也 204(幂等)`() {
        val authed = bootstrapAndAuth()
        val nopePut = authed.put().uri("/api/projects/NOPE/favorite").exchange()
        println("DEBUG_NOPE_PUT=" + String(nopePut.expectBody(ByteArray::class.java).returnResult().responseBody ?: ByteArray(0)))
        nopePut.expectStatus().isNotFound()
        val nopeDel = authed.delete().uri("/api/projects/NOPE/favorite").exchange()
        println("DEBUG_NOPE_DEL=" + String(nopeDel.expectBody(ByteArray::class.java).returnResult().responseBody ?: ByteArray(0)))
        nopeDel.expectStatus().isNotFound()

        createProject(authed, "CHE")
        authed.delete().uri("/api/projects/CHE/favorite")
            .exchange().expectStatus().isNoContent() // 未收藏时删除 = 幂等无操作
        val delChe = authed.delete().uri("/api/projects/CHE/favorite").exchange()
        println("DEBUG_DEL_CHE=" + String(delChe.expectBody(ByteArray::class.java).returnResult().responseBody ?: ByteArray(0)))
        delChe.expectStatus().isNoContent()
    }
}
