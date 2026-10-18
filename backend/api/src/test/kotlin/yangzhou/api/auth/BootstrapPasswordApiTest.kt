package yangzhou.api.auth

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** D6(spec 0052):bootstrap 密码最短 8 位。 */
class BootstrapPasswordApiTest : AbstractApiTest() {

    @Test
    fun `密码不足 8 位——400 VALIDATION`() {
        val body = json.readTree(
            rest.post().uri("/api/auth/bootstrap")
                .body(mapOf("username" to "me", "password" to "1234567"))
                .exchange().expectStatus().isBadRequest()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals("VALIDATION", body["code"].asText())
        assertEquals(true, body["fields"]["password"].asText().contains("8"))
    }
}
