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
 * YPJ-299 集成(spec 0177 §5/§3.2):三级守卫平移 + 池去角色化全链。
 * 第二名凭据成员无创建 API(bootstrap 仅首个),经 jdbc 造行后走 /api/auth/login 正常登录——黑盒例外,勿扩散。
 * R2「无 leader 放行」废除后的反证:项目无任何 editor 行时,池成员仍 403(admin 兜底)。
 */
class ProjectAuthzApiTest : AbstractApiTest() {
    @Autowired
    private lateinit var encoder: PasswordEncoder

    private fun insertMember(
        username: String,
        password: String,
    ): String {
        val objectId = UUID.randomUUID()
        val workspaceId = jdbc.queryForObject("select object_id from workspace limit 1", java.util.UUID::class.java)!!
        jdbc.update(
            "insert into member (object_id, workspace_object_id, username, password_hash, display_name, created_at, updated_at) " +
                "values (?, ?, ?, ?, ?, now(), now())",
            objectId,
            workspaceId,
            username,
            encoder.encode(password),
            username,
        )
        return objectId.toString()
    }

    private fun clientFor(
        username: String,
        password: String,
    ): RestTestClient {
        val token = login(username, password)["token"].asText()
        return rest.mutate().defaultHeaders { it.setBearerAuth(token) }.build()
    }

    private fun pool(authed: RestTestClient, projectKey: String) = json.readTree(
        authed
            .get()
            .uri("/api/projects/$projectKey/members")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!,
    )

    private fun setRole(
        authed: RestTestClient,
        projectKey: String,
        memberId: String,
        role: String,
    ) = authed
        .put()
        .uri("/api/projects/$projectKey/members/$memberId/role")
        .body(mapOf("role" to role))
        .exchange()

    private fun createStatus(
        authed: RestTestClient,
        projectKey: String,
        name: String,
    ) = authed
        .post()
        .uri("/api/projects/$projectKey/statuses")
        .body(mapOf("name" to name))
        .exchange()

    private fun createMilestone(
        authed: RestTestClient,
        projectKey: String,
        name: String,
    ) = authed
        .post()
        .uri("/api/projects/$projectKey/milestones")
        .body(mapOf("name" to name))
        .exchange()

    private fun createItemType(
        authed: RestTestClient,
        projectKey: String,
    ) = authed
        .post()
        .uri("/api/projects/$projectKey/item-types")
        .body(mapOf("typeKey" to "epic", "name" to "Epic"))
        .exchange()

    private fun whoami(authed: RestTestClient) = json.readTree(
        authed
            .get()
            .uri("/api/members/me")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!,
    )

    @Test
    fun `池行不再承载角色 创建者入池为 pool 成员`() {
        val admin = bootstrapAndAuth()
        createProject(admin, "PAG")
        val members = pool(admin, "PAG")
        assertEquals(1, members.size())
        assertEquals("member", members[0]["role"].asText())
    }

    @Test
    fun `池内无角色 管理端点403(含R2废除反证与补门) item级与直访放行`() {
        val admin = bootstrapAndAuth()
        createProject(admin, "PAG")
        val poolOnlyId = insertMember("bee", "beepass123")
        addPoolMember(admin, "PAG", poolOnlyId)
        val poolOnly = clientFor("bee", "beepass123")

        // 管理:403(workflow 平移 / 删项目 C3 / milestone 补门)
        createStatus(poolOnly, "PAG", "评审").expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
        poolOnly.delete().uri("/api/projects/PAG").exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
        createMilestone(poolOnly, "PAG", "M1").expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
        // R2 废除反证:即使项目无任何 editor 行(去角色化后常态),池成员仍不可管理
        jdbc.update("delete from member_role where project_id is not null")
        createStatus(poolOnly, "PAG", "评审").expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
        // 干活与读:放行(item 级门接线归 S4;直访门归切片 b,现状放行)
        poolOnly
            .post().uri("/api/projects/PAG/items").body(mapOf("title" to "b-item"))
            .exchange().expectStatus().isCreated()
        poolOnly.get().uri("/api/projects/PAG").exchange().expectStatus().isOk()
    }

    @Test
    fun `授editor后管理端点放行 whoami projectRoles 有数据`() {
        val admin = bootstrapAndAuth()
        createProject(admin, "PAG")
        val editorId = insertMember("bee", "beepass123")
        addPoolMember(admin, "PAG", editorId)
        val editor = clientFor("bee", "beepass123")

        setRole(admin, "PAG", editorId, "editor").expectStatus().isOk()
        assertEquals("editor", pool(admin, "PAG")[1]["role"].asText())

        // 平移抽样:workflow / item-type / milestone 补门
        createStatus(editor, "PAG", "评审").expectStatus().isCreated()
        createItemType(editor, "PAG").expectStatus().isCreated()
        createMilestone(editor, "PAG", "M1").expectStatus().isCreated()

        // whoami(S2 形状,backfill/授行后自然有数据)
        val me = whoami(editor)
        assertEquals(1, me["projectRoles"].size())
        assertEquals("editor", me["projectRoles"][0]["role"].asText())
    }

    @Test
    fun `admin兜底 无池行无角色行可管理 删项目仅admin`() {
        val admin = bootstrapAndAuth()
        createProject(admin, "DEL")
        val secondAdminId = insertMember("bee", "beepass123")
        val secondAdmin = clientFor("bee", "beepass123")
        // 授 admin(S2 端点,由现有 admin 授);B 不入池不授角色
        secondAdmin
            .put().uri("/api/members/$secondAdminId/admin").body(mapOf("admin" to true))
            .exchange().expectStatus().isOk()
        createStatus(secondAdmin, "DEL", "评审").expectStatus().isCreated()
        // C3:删项目仅 admin
        secondAdmin.delete().uri("/api/projects/DEL").exchange().expectStatus().isNoContent()
    }

    @Test
    fun `setRole三值语义 虚拟成员400 非法值400`() {
        val admin = bootstrapAndAuth()
        createProject(admin, "PAG")
        val granteeId = insertMember("bee", "beepass123")
        addPoolMember(admin, "PAG", granteeId)

        // viewer → member(撤)循环
        setRole(admin, "PAG", granteeId, "viewer").expectStatus().isOk()
        assertEquals("viewer", pool(admin, "PAG")[1]["role"].asText())
        setRole(admin, "PAG", granteeId, "member").expectStatus().isOk()
        assertEquals("member", pool(admin, "PAG")[1]["role"].asText())

        // 虚拟成员(无凭据)只进池不进 role(开放决策 3)
        val virtualId =
            json
                .readTree(
                    admin
                        .post().uri("/api/members").body(mapOf("displayName" to "ghost"))
                        .exchange().expectStatus().isCreated()
                        .expectBody(String::class.java).returnResult().responseBody!!,
                )["memberId"]
                .asText()
        addPoolMember(admin, "PAG", virtualId)
        setRole(admin, "PAG", virtualId, "editor").expectStatus().isBadRequest()

        // 非法值 400
        setRole(admin, "PAG", granteeId, "boss").expectStatus().isBadRequest()
    }

    @Test
    fun `出池即撤角色 重新入池无角色`() {
        val admin = bootstrapAndAuth()
        createProject(admin, "PAG")
        val editorId = insertMember("bee", "beepass123")
        addPoolMember(admin, "PAG", editorId)
        val editor = clientFor("bee", "beepass123")
        setRole(admin, "PAG", editorId, "editor").expectStatus().isOk()

        admin.delete().uri("/api/projects/PAG/members/$editorId").exchange().expectStatus().isNoContent()
        assertEquals(1, pool(admin, "PAG").size())
        createStatus(editor, "PAG", "评审").expectStatus().isEqualTo(HttpStatus.FORBIDDEN)

        // 重新入池:无角色(member),角色行已被出池清除
        addPoolMember(admin, "PAG", editorId)
        assertEquals(2, pool(admin, "PAG").size())
        assertEquals("member", pool(admin, "PAG")[1]["role"].asText())
    }
}
