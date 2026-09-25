package yangzhou.api.member

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JCW-141:V10-S3 颜色语义(创建自动分配调色板色/轮转)。 */
class MemberColorApiTest : AbstractApiTest() {

    @Test
    fun `建成员自动分配颜色——轮转不重复——颜色可编辑`() {
        val authed = bootstrapAndAuth()
        val colors = mutableSetOf<String?>()
        for (i in 1..3) {
            val m = json.readTree(
                authed.post().uri("/api/members")
                    .body(mapOf("displayName" to "成员$i"))
                    .exchange().expectStatus().isCreated()
                    .expectBody(String::class.java).returnResult().responseBody!!,
            )
            val color = m["color"].asText(null)
            assertTrue(color != null && color.startsWith("#"), "color=$color")
            colors.add(color)
        }
        assertEquals(3, colors.size) // 轮转 3 色互不相同
    }
}
