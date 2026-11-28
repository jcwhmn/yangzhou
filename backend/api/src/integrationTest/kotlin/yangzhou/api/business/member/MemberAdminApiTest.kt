package yangzhou.api.business.member

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** spec 0177(S2)YPJ-293:账号治理端点(建真账号/重置凭据/禁复职/授收 admin/删号/whoami)REST 黑盒。 */
class MemberAdminApiTest : AbstractApiTest() {
    @Autowired
    private lateinit var encoder: PasswordEncoder

    /** S1 先例:直插第二个登录账号走 login 拿 token(保持黑盒;S2 后也可走建号端点,此处留作 403 用例的廉价 seeded 账号)。 */
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

    private fun loginToken(
        username: String,
        password: String,
    ): String = login(username, password)["token"].asText()

    private fun authedWith(token: String): RestTestClient = rest.mutate().defaultHeaders { it.setBearerAuth(token) }.build()

    /** 建一个真账号(挂一次性项目)并返回 memberId。失败时打印响应体便诊断。 */
    private fun createRealAccount(
        admin: RestTestClient,
        projectId: String,
        username: String = "dev2",
    ): String {
        val result =
            admin
                .post()
                .uri("/api/members")
                .body(mapOf("displayName" to username, "username" to username, "password" to "secret123", "projectIds" to listOf(projectId)))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        if (result.status.value() != 201) throw IllegalStateException("CREATE_FAIL=${result.status} ${result.responseBody}")
        return json.readTree(result.responseBody!!)["memberId"].asText()
    }

    @Test
    fun `whoami - admin true 且不含密码字段`() {
        val admin = bootstrapAndAuth()
        val body =
            admin
                .get()
                .uri("/api/members/me")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
                .let { json.readTree(it) }
        assertEquals(true, body["admin"].asBoolean())
        assertEquals(false, body["virtual"].asBoolean())
        assertEquals(0, body["projectRoles"].size())
        assertNotNull(body["memberId"].asText())
        assertTrue(!body.has("passwordHash"))
    }

    @Test
    fun `建真账号挂池入库,新账号可登录且 whoami 非 admin`() {
        val admin = bootstrapAndAuth()
        val projectId = createProject(admin, "S2P")["projectId"].asText()
        val memberId = createRealAccount(admin, projectId)

        // 库内断言:池行已落(C8 建号即入池)
        val poolRows =
            jdbc.queryForObject(
                "select count(*) from project_member where project_object_id = cast(? as uuid) and member_object_id = cast(? as uuid)",
                Int::class.java,
                projectId,
                memberId,
            )
        assertEquals(1, poolRows)

        val token = loginToken("dev2", "secret123")
        val me =
            authedWith(token)
                .get()
                .uri("/api/members/me")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
                .let { json.readTree(it) }
        assertEquals(false, me["admin"].asBoolean())
    }

    @Test
    fun `建真账号负例 - 缺项目 400 重名 409 username 占用 409 短密码 400`() {
        val admin = bootstrapAndAuth()
        val projectId = createProject(admin, "S2P")["projectId"].asText()
        val base = mapOf("displayName" to "张三", "username" to "zhang", "password" to "secret123", "projectIds" to listOf(projectId))

        admin.post().uri("/api/members").body(base).exchange().expectStatus().isCreated() // 基线建成功
        admin.post().uri("/api/members").body(base).exchange().expectStatus().isEqualTo(409) // 重名成员 409
        admin
            .post()
            .uri("/api/members")
            .body(base + ("displayName" to "李四"))
            .exchange()
            .expectStatus().isEqualTo(409) // username 占用 409
        admin
            .post()
            .uri("/api/members")
            .body(base + mapOf("displayName" to "王五", "username" to "wang", "password" to "short7"))
            .exchange()
            .expectStatus()
            .isBadRequest() // <8 位
        admin
            .post()
            .uri("/api/members")
            .body(mapOf("displayName" to "赵六", "username" to "zhao", "password" to "secret123"))
            .exchange()
            .expectStatus()
            .isBadRequest() // C8 缺项目
    }

    @Test
    fun `重置密码踢会话 - 旧密码 401 新密码可登`() {
        val admin = bootstrapAndAuth()
        val projectId = createProject(admin, "S2P")["projectId"].asText()
        val memberId = createRealAccount(admin, projectId)

        admin
            .put()
            .uri("/api/members/$memberId/credentials")
            .body(mapOf("password" to "newsecret123"))
            .exchange()
            .expectStatus()
            .isOk()

        // 旧密码 401(不经 helper:helper 期望 isOk)
        rest
            .post()
            .uri("/api/auth/login")
            .body(mapOf("username" to "dev2", "password" to "secret123"))
            .exchange()
            .expectStatus()
            .isUnauthorized()
        loginToken("dev2", "newsecret123") // 新密码可登(helper 期望 isOk,失败即抛红)
    }

    @Test
    fun `虚拟成员设凭据升级后可登录`() {
        val admin = bootstrapAndAuth()
        val virtualId =
            json
                .readTree(
                    admin
                        .post()
                        .uri("/api/members")
                        .body(mapOf("displayName" to "虚拟丙"))
                        .exchange()
                        .expectStatus()
                        .isCreated()
                        .expectBody(String::class.java)
                        .returnResult()
                        .responseBody!!,
                )["memberId"].asText()

        admin
            .put()
            .uri("/api/members/$virtualId/credentials")
            .body(mapOf("username" to "virtual3", "password" to "secret123"))
            .exchange()
            .expectStatus()
            .isOk()

        val token = loginToken("virtual3", "secret123")
        assertEquals(false, authedWith(token).get().uri("/api/members/me").exchange().expectBody(String::class.java).returnResult().responseBody!!.let { json.readTree(it) }["virtual"].asBoolean())
    }

    @Test
    fun `禁用复职 - 禁用后登录 401 旧 token 越秒失效 复职恢复`() {
        val admin = bootstrapAndAuth()
        val projectId = createProject(admin, "S2P")["projectId"].asText()
        val memberId = createRealAccount(admin, projectId)
        val oldToken = loginToken("dev2", "secret123")

        admin
            .put()
            .uri("/api/members/$memberId/status")
            .body(mapOf("status" to "disabled"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .let { assertEquals("disabled", json.readTree(it)["status"].asText()) }

        // 同秒竞态:越秒界后旧 token 必死(S1 MemberAuthFoundationApiTest 同款节奏)
        Thread.sleep(1100)
        authedWith(oldToken).get().uri("/api/projects").exchange().expectStatus().isUnauthorized()
        rest
            .post()
            .uri("/api/auth/login")
            .body(mapOf("username" to "dev2", "password" to "secret123"))
            .exchange()
            .expectStatus()
            .isUnauthorized() // disabled 拒登

        admin
            .put()
            .uri("/api/members/$memberId/status")
            .body(mapOf("status" to "active"))
            .exchange()
            .expectStatus()
            .isOk()
        loginToken("dev2", "secret123") // 复职后可登(不抛即绿)
    }

    @Test
    fun `status 非法枚举 400`() {
        val admin = bootstrapAndAuth()
        val memberId = meMemberId(admin)
        admin
            .put()
            .uri("/api/members/$memberId/status")
            .body(mapOf("status" to "paused"))
            .exchange()
            .expectStatus()
            .isBadRequest()
    }

    @Test
    fun `授收 admin - whoami 随之翻转,最后 admin 自收 409`() {
        val admin = bootstrapAndAuth()
        val projectId = createProject(admin, "S2P")["projectId"].asText()
        val memberId = createRealAccount(admin, projectId)
        val dev2 = authedWith(loginToken("dev2", "secret123"))

        admin.put().uri("/api/members/$memberId/admin").body(mapOf("admin" to true)).exchange().expectStatus().isOk()
        assertEquals(true, dev2.get().uri("/api/members/me").exchange().expectBody(String::class.java).returnResult().responseBody!!.let { json.readTree(it) }["admin"].asBoolean())

        admin.put().uri("/api/members/$memberId/admin").body(mapOf("admin" to false)).exchange().expectStatus().isOk()
        assertEquals(false, dev2.get().uri("/api/members/me").exchange().expectBody(String::class.java).returnResult().responseBody!!.let { json.readTree(it) }["admin"].asBoolean())

        // 唯一 admin 自收 → 409(whoami 拿自己的 memberId,避免 meMemberId 在多真账号下取错人)
        val adminId = admin.get().uri("/api/members/me").exchange().expectBody(String::class.java).returnResult().responseBody!!.let { json.readTree(it)["memberId"].asText() }
        admin
            .put()
            .uri("/api/members/$adminId/admin")
            .body(mapOf("admin" to false))
            .exchange()
            .expectStatus().isEqualTo(409)
    }

    @Test
    fun `删真账号 - 删后 token 失效 自删 409`() {
        val admin = bootstrapAndAuth()
        val projectId = createProject(admin, "S2P")["projectId"].asText()
        val memberId = createRealAccount(admin, projectId)
        val dev2Token = loginToken("dev2", "secret123")

        admin.delete().uri("/api/members/$memberId").exchange().expectStatus().isNoContent()
        authedWith(dev2Token).get().uri("/api/projects").exchange().expectStatus().isUnauthorized() // sub 不存在即拒

        admin.delete().uri("/api/members/${meMemberId(admin)}").exchange().expectStatus().isEqualTo(409) // 自删
    }

    @Test
    fun `非 admin 调治理端点 403`() {
        val admin = bootstrapAndAuth()
        val projectId = createProject(admin, "S2P")["projectId"].asText()
        val memberId = createRealAccount(admin, projectId)
        val dev2 = seedLoginMember("dev3", "secret123") // 非 admin

        dev2.put().uri("/api/members/$memberId/status").body(mapOf("status" to "disabled")).exchange().expectStatus().isForbidden()
        dev2.put().uri("/api/members/$memberId/admin").body(mapOf("admin" to true)).exchange().expectStatus().isForbidden()
        dev2
            .put()
            .uri("/api/members/$memberId/credentials")
            .body(mapOf("password" to "secret123"))
            .exchange()
            .expectStatus()
            .isForbidden()
    }
}
