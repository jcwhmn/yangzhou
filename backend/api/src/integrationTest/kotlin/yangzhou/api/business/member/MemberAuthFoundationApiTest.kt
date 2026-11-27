package yangzhou.api.business.member

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** spec 0177(S1)YPJ-288:requireAdmin 收权 + 自助改密(踢会话)。 */
class MemberAuthFoundationApiTest : AbstractApiTest() {
    @Autowired
    private lateinit var encoder: PasswordEncoder

    /** 直插第二个登录账号(S1 尚无建号端点,S2 才有);走 login 端点拿 token 保持黑盒。 */
    private fun seedLoginMember(
        username: String,
        password: String,
    ): RestTestClient {
        jdbc.update(
            "insert into member (object_id, workspace_object_id, username, password_hash, display_name) " +
                "values (cast(? as uuid), (select object_id from workspace limit 1), ?, ?, ?)",
            java.util.UUID.randomUUID().toString(),
            username,
            encoder.encode(password),
            username,
        )
        val token = login(username, password)["token"].asText()
        return rest.mutate().defaultHeaders { it.setBearerAuth(token) }.build()
    }

    @Test
    fun `非 admin 建虚拟成员被拒 403,admin 放行`() {
        val admin = bootstrapAndAuth() // 先 bootstrap(workspace/member 基础数据)
        val dev2 = seedLoginMember("dev2", "secret123")
        dev2
            .post()
            .uri("/api/members")
            .body(mapOf("displayName" to "小李"))
            .exchange()
            .expectStatus()
            .isForbidden()

        admin
            .post()
            .uri("/api/members")
            .body(mapOf("displayName" to "小李"))
            .exchange()
            .expectStatus()
            .isCreated()
    }

    @Test
    fun `非 admin 删号被拒 403`() {
        val admin = bootstrapAndAuth()
        val virtualId =
            json
                .readTree(
                    admin
                        .post()
                        .uri("/api/members")
                        .body(mapOf("displayName" to "小李"))
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody(String::class.java)
                        .returnResult()
                        .responseBody!!,
                )["memberId"]
                .asText()

        val dev2 = seedLoginMember("dev2", "secret123")
        dev2
            .delete()
            .uri("/api/members/$virtualId")
            .exchange()
            .expectStatus()
            .isForbidden()
    }

    @Test
    fun `自助改密成功踢所有会话,旧密码不能再用`() {
        val authed = bootstrapAndAuth()
        val oldToken = login("me", "secret123")["token"].asText()

        // 踢出前越过秒界:iat 为秒精度,同秒签发的旧 token 不会被 kickSecond 杀(spec 0177 §4.3 精化语义)
        Thread.sleep(1100)
        authed
            .put()
            .uri("/api/members/me/password")
            .body(mapOf("oldPassword" to "secret123", "newPassword" to "newpass456"))
            .exchange()
            .expectStatus()
            .isNoContent()

        // 黑名单 upsert(R7「踢」):旧 token 即刻失效
        rest
            .get()
            .uri("/api/members")
            .header("Authorization", "Bearer $oldToken")
            .exchange()
            .expectStatus()
            .isUnauthorized()

        // 踢后重登的新 token 必须可用(否则自助改密=自锁 30d;spec 0177 §4.3 iat 精化)
        val freshToken = login("me", "newpass456")["token"].asText()
        rest
            .get()
            .uri("/api/members")
            .header("Authorization", "Bearer " + freshToken)
            .exchange()
            .expectStatus()
            .isOk()

        // 新密码可登,旧密码不可登
        assertEquals("me", login("me", "newpass456")["username"].asText())
        rest
            .post()
            .uri("/api/auth/login")
            .body(mapOf("username" to "me", "password" to "secret123"))
            .exchange()
            .expectStatus()
            .isUnauthorized()
    }

    @Test
    fun `自助改密 old 错返回 401,new 短于 8 位返回 400`() {
        val authed = bootstrapAndAuth()

        authed
            .put()
            .uri("/api/members/me/password")
            .body(mapOf("oldPassword" to "wrong-old", "newPassword" to "newpass456"))
            .exchange()
            .expectStatus()
            .isUnauthorized()

        authed
            .put()
            .uri("/api/members/me/password")
            .body(mapOf("oldPassword" to "secret123", "newPassword" to "short"))
            .exchange()
            .expectStatus()
            .isBadRequest()

        // 密码未被改动
        assertNotEquals(null, login("me", "secret123")["token"].asText())
    }
}
