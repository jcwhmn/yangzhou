package yangzhou.api.business.auth

import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthApiTest : AbstractApiTest() {
    @Test
    fun `bootstrap 创建首个用户并返回 token`() {
        val response =
            rest
                .post()
                .uri("/api/auth/bootstrap")
                .body(mapOf("username" to "me", "password" to "secret123"))
                .exchange()

        response.expectStatus().isCreated()
        val body = json.readTree(response.expectBody(String::class.java).returnResult().responseBody!!)
        assertTrue(body["token"].asText().isNotBlank())
        assertEquals("me", body["username"].asText())
    }

    @Test
    fun `重复 bootstrap 返回 409`() {
        rest
            .post()
            .uri("/api/auth/bootstrap")
            .body(mapOf("username" to "me", "password" to "secret123"))
            .exchange()
            .expectStatus()
            .isCreated()

        rest
            .post()
            .uri("/api/auth/bootstrap")
            .body(mapOf("username" to "other", "password" to "secret123"))
            .exchange()
            .expectStatus()
            .isEqualTo(409)
    }

    @Test
    fun `登录成功返回 token,密码错误 401 且消息笼统`() {
        rest
            .post()
            .uri("/api/auth/bootstrap")
            .body(mapOf("username" to "me", "password" to "secret123"))
            .exchange()

        assertEquals("me", login("me", "secret123")["username"].asText())

        rest
            .post()
            .uri("/api/auth/login")
            .body(mapOf("username" to "me", "password" to "wrong"))
            .exchange()
            .expectStatus()
            .isUnauthorized()
    }

    @Test
    fun `未认证访问受保护端点返回 401,带 token 可访问`() {
        rest
            .get()
            .uri("/api/projects")
            .exchange()
            .expectStatus()
            .isUnauthorized()

        val authed: RestTestClient = bootstrapAndAuth()
        authed
            .get()
            .uri("/api/projects")
            .exchange()
            .expectStatus()
            .isOk()
    }

    // ---------- spec 0177(S1):账号基座场景 ----------

    @Test
    fun `bootstrap 落 workspace admin 角色行`() {
        // spec §3.2 backfill 语义:bootstrap 账号必须全权
        bootstrapAndAuth()
        val count = jdbc.queryForList("select id from member_role where role = 'admin' and project_id is null").size
        assertEquals(1, count)
    }

    @Test
    fun `disabled 账号登录返回 401 账号已禁用`() {
        bootstrapAndAuth()
        jdbc.execute("update member set status = 'DISABLED' where username = 'me'")

        val response =
            rest
                .post()
                .uri("/api/auth/login")
                .body(mapOf("username" to "me", "password" to "secret123"))
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        assertTrue(json.readTree(response)["message"].asText().contains("账号已禁用"))
    }

    @Test
    fun `禁用后既有 token 请求链即拒 401`() {
        // spec §4.2:status == active 不过 → 不置身份(非终态会话即时失效)
        val authed = bootstrapAndAuth()
        jdbc.execute("update member set status = 'DISABLED' where username = 'me'")
        authed
            .get()
            .uri("/api/members")
            .exchange()
            .expectStatus()
            .isUnauthorized()
    }

    @Test
    fun `黑名单有效行即拒,过期行顺带清理`() {
        val authed = bootstrapAndAuth()
        val memberId = "(select id from member where username = 'me')"
        // created_at 强制 +1s:iat 为秒精度,kickSecond 必须 > token iat 秒才杀(spec 0177 §4.3 精化语义)
        jdbc.execute(
            "insert into member_blacklist (object_id, member_id, expires_at, created_at) " +
                "values (gen_random_uuid(), $memberId, now() + interval '10 days', now() + interval '1 second')",
        )
        authed
            .get()
            .uri("/api/members")
            .exchange()
            .expectStatus()
            .isUnauthorized()

        // 过期行无害(读时顺带删,惰性清理 §4.3)
        jdbc.execute(
            "update member_blacklist set expires_at = now() - interval '1 hour'",
        )
        authed
            .get()
            .uri("/api/members")
            .exchange()
            .expectStatus()
            .isOk()
        val remaining = jdbc.queryForList("select id from member_blacklist").size
        assertEquals(0, remaining)
    }

    /** 解 JWT payload(段 1,base64url)取 exp-iat 秒数。 */
    private fun ttlSeconds(token: String): Long {
        val payload = String(java.util.Base64.getUrlDecoder().decode(token.split(".")[1]))
        val node = json.readTree(payload)
        return node["exp"].asLong() - node["iat"].asLong()
    }

    @Test
    fun `ct 分型 web 8h cli 720h`() {
        bootstrapAndAuth()
        val webTtl =
            ttlSeconds(
                login("me", "secret123", mapOf("clientType" to "web"))["token"].asText(),
            )
        val cliTtl =
            ttlSeconds(
                login("me", "secret123", mapOf("clientType" to "cli"))["token"].asText(),
            )
        assertEquals(8 * 3600, webTtl)
        assertEquals(720 * 3600, cliTtl)
    }
}
