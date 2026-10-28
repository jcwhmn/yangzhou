package yangzhou.api.business.projectmember

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import java.util.UUID
import kotlin.test.assertEquals

/**
 * YPJ-102 集成(spec 0081):leader 门槛全链。
 * 第二名凭据成员无创建 API(bootstrap 仅首个),经 jdbc 造行后走 /api/auth/login 正常登录——黑盒例外,勿扩散。
 */
class LeaderPermissionApiTest : AbstractApiTest() {

    @Autowired
    private lateinit var encoder: PasswordEncoder

    private fun insertMember(username: String, password: String): String {
        val objectId = UUID.randomUUID()
        val workspaceId = jdbc.queryForObject("select object_id from workspace limit 1", java.util.UUID::class.java)!!
        jdbc.update(
            "insert into member (object_id, workspace_object_id, username, password_hash, display_name, created_at, updated_at) " +
                "values (?, ?, ?, ?, ?, now(), now())",
            objectId, workspaceId, username, encoder.encode(password), username,
        )
        return objectId.toString()
    }

    private fun clientFor(username: String, password: String): RestTestClient {
        val token = login(username, password)["token"].asText()
        return rest.mutate().defaultHeaders { it.setBearerAuth(token) }.build()
    }

    @Test
    fun `R1 创建者自动入池且为 leader`() {
        val a = bootstrapAndAuth()
        createProject(a, "LDR")
        val pool = json.readTree(
            a.get().uri("/api/projects/LDR/members").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(1, pool.size())
        assertEquals("leader", pool[0]["role"].asText())
    }

    @Test
    fun `非leader池成员 管理端点403 item操作与读放行`() {
        val a = bootstrapAndAuth()
        createProject(a, "LDR")
        val bObjectId = insertMember("bee", "beepass123")
        addPoolMember(a, "LDR", bObjectId)
        val b = clientFor("bee", "beepass123")

        // 管理:403
        b.post().uri("/api/projects/LDR/statuses").body(mapOf("name" to "评审"))
            .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
        b.delete().uri("/api/projects/LDR")
            .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
        // 干活与读:放行
        b.post().uri("/api/projects/LDR/items").body(mapOf("title" to "b-item"))
            .exchange().expectStatus().isCreated()
        b.get().uri("/api/projects/LDR").exchange().expectStatus().isOk()
    }

    @Test
    fun `设leader后可管理 唯一leader降级409`() {
        val a = bootstrapAndAuth()
        createProject(a, "LDR")
        // A 的 memberId:建项后池内只有 A,直接取[0](meMemberId 取首个凭据成员,双凭据场景会取错)
        val aMemberId = json.readTree(
            a.get().uri("/api/projects/LDR/members").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )[0]["memberId"].asText()
        val bObjectId = insertMember("bee", "beepass123")
        addPoolMember(a, "LDR", bObjectId)
        val b = clientFor("bee", "beepass123")

        // A 提名 B 为 leader → B 可管理
        a.put().uri("/api/projects/LDR/members/$bObjectId/role").body(mapOf("role" to "leader"))
            .exchange().expectStatus().isOk()
        b.post().uri("/api/projects/LDR/statuses").body(mapOf("name" to "评审"))
            .exchange().expectStatus().isCreated()

        // A(尚有 B 这个 leader)自降 → OK;B 降自己(最后 leader)→ 409
        a.put().uri("/api/projects/LDR/members/$aMemberId/role").body(mapOf("role" to "member"))
            .exchange().expectStatus().isOk()
        b.put().uri("/api/projects/LDR/members/$bObjectId/role").body(mapOf("role" to "member"))
            .exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT)

        // 非法 role 值 → 400
        b.put().uri("/api/projects/LDR/members/$bObjectId/role").body(mapOf("role" to "boss"))
            .exchange().expectStatus().isBadRequest()
    }

    @Test
    fun `R2 leader行全清后 池内成员皆可管理`() {
        val a = bootstrapAndAuth()
        createProject(a, "LDR")
        val bObjectId = insertMember("bee", "beepass123")
        addPoolMember(a, "LDR", bObjectId)
        val b = clientFor("bee", "beepass123")
        jdbc.update("update project_member set role = 'member'")
        b.post().uri("/api/projects/LDR/statuses").body(mapOf("name" to "评审"))
            .exchange().expectStatus().isCreated()
    }
}
