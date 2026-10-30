package yangzhou.api.business.item

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Q2(spec 0052):重复需求同属性显式拒绝——原静默取第一个。 */
class ItemDuplicateRequirementApiTest : AbstractApiTest() {
    @Test
    fun `同属性重复需求——400`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        createSkillAttribute(authed, "React")

        val res =
            authed
                .post()
                .uri("/api/projects/CHE/items")
                .body(
                    mapOf(
                        "title" to "x",
                        "requirements" to
                            listOf(
                                mapOf("attribute" to "React", "minLevel" to 3),
                                mapOf("attribute" to "React", "minLevel" to 1),
                            ),
                    ),
                ).exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!

        val body = json.readTree(res)
        assertEquals("BAD_REQUEST", body["code"].asText())
        assertEquals(true, body["message"].asText().contains("重复"))
    }
}
