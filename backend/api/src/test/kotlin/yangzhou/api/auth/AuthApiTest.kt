package yangzhou.api.auth

import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthApiTest : AbstractApiTest() {

    @Test
    fun `bootstrap 创建首个用户并返回 token`() {
        val response = rest.post().uri("/api/auth/bootstrap")
            .body(mapOf("username" to "me", "password" to "secret"))
            .exchange()

        response.expectStatus().isCreated()
        val body = json.readTree(response.expectBody(String::class.java).returnResult().responseBody!!)
        assertTrue(body["token"].asText().isNotBlank())
        assertEquals("me", body["username"].asText())
    }

    @Test
    fun `重复 bootstrap 返回 409`() {
        rest.post().uri("/api/auth/bootstrap")
            .body(mapOf("username" to "me", "password" to "secret"))
            .exchange().expectStatus().isCreated()

        rest.post().uri("/api/auth/bootstrap")
            .body(mapOf("username" to "other", "password" to "secret"))
            .exchange().expectStatus().isEqualTo(409)
    }

    @Test
    fun `登录成功返回 token,密码错误 401 且消息笼统`() {
        rest.post().uri("/api/auth/bootstrap")
            .body(mapOf("username" to "me", "password" to "secret"))
            .exchange()

        assertEquals("me", login("me", "secret")["username"].asText())

        rest.post().uri("/api/auth/login")
            .body(mapOf("username" to "me", "password" to "wrong"))
            .exchange()
            .expectStatus().isUnauthorized()
    }

    @Test
    fun `未认证访问受保护端点返回 401,带 token 可访问`() {
        rest.get().uri("/api/projects").exchange().expectStatus().isUnauthorized()

        val authed: RestTestClient = bootstrapAndAuth()
        authed.get().uri("/api/projects").exchange().expectStatus().isOk()
    }
}
