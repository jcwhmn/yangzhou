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
    fun `创建即管 创建者入池且落editor行 池行本身仍无角色列`() {
        val admin = bootstrapAndAuth()
        createProject(admin, "PAG")
        val members = pool(admin, "PAG")
        assertEquals(1, members.size())
        // YPJ-300 创建即管:池行只表成员资格,角色从 member_role 行读出(创建者=editor;矩阵行 25)
        assertEquals("editor", members[0]["role"].asText())
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
        // 干活与读:放行(item 级门接线归 S4;直访/池列表门=YPJ-300 requireMember,池行放行)
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
        admin
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

    // ---------- 切片 b(YPJ-300):可见性与门 ----------

    @Test
    fun `建项目门 池成员403 editor放行 创建即管落editor行`() {
        val admin = bootstrapAndAuth()
        createProject(admin, "PAG")
        val beeId = insertMember("bee", "beepass123")
        val bee = clientFor("bee", "beepass123")

        // 无 editor 行:建项目 403(行 25 editor-anywhere 门)
        bee
            .post().uri("/api/projects").body(mapOf("key" to "BEE", "name" to "B"))
            .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN)

        // admin 授 editor(入池+setRole)→ editor-anywhere 成立 → 201
        addPoolMember(admin, "PAG", beeId)
        setRole(admin, "PAG", beeId, "editor").expectStatus().isOk()
        bee
            .post().uri("/api/projects").body(mapOf("key" to "BEE", "name" to "B"))
            .exchange().expectStatus().isCreated()

        // 创建即管:BEE 池=创建者且 role=editor;whoami projectRoles 含两行(PAG+BEE 均 editor)
        val pool = pool(bee, "BEE")
        assertEquals(1, pool.size())
        assertEquals("editor", pool[0]["role"].asText())
        val me = whoami(bee)
        assertEquals(2, me["projectRoles"].size())
    }

    @Test
    fun `可见性 列表过滤 直访门 池列表门`() {
        val admin = bootstrapAndAuth()
        createProject(admin, "PAG")
        createProject(admin, "OTH")
        val beeId = insertMember("bee", "beepass123")
        val catId = insertMember("cat", "catpass1234")
        val bee = clientFor("bee", "beepass123")
        val cat = clientFor("cat", "catpass1234")
        addPoolMember(admin, "PAG", beeId) // bee 只入 PAG 池,OTH 无行

        fun keys(client: RestTestClient) = json
            .readTree(
                client
                    .get().uri("/api/projects")
                    .exchange().expectStatus().isOk()
                    .expectBody(String::class.java).returnResult().responseBody!!,
            ).map { it["key"].asText() }

        // 列表过滤(行 6):bee 角色行∪池行=仅 PAG;cat 全无=空;admin 全量
        assertEquals(listOf("PAG"), keys(bee))
        assertEquals(emptyList(), keys(cat))
        assertEquals(2, keys(admin).size)

        // 直访(行 7/8):池行可访;存在但无行 403;不存在 404(NotFound 先于 403)
        bee.get().uri("/api/projects/PAG").exchange().expectStatus().isOk()
        bee.get().uri("/api/projects/OTH").exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
        bee.get().uri("/api/projects/NOPE").exchange().expectStatus().isNotFound()
        cat.get().uri("/api/projects/PAG").exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN)

        // 池列表门(行 3a):池成员可读;无关者 403
        assertEquals(2, pool(bee, "PAG").size())
        cat
            .get().uri("/api/projects/PAG/members")
            .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `目录门与他人编辑门 池成员403 editor放行且池内互编辑 本人恒可`() {
        val admin = bootstrapAndAuth()
        createProject(admin, "PAG")
        createSkillAttribute(admin, "K")
        val beeId = insertMember("bee", "beepass123")
        val catId = insertMember("cat", "catpass1234")
        val bee = clientFor("bee", "beepass123")
        val cat = clientFor("cat", "catpass1234")
        addPoolMember(admin, "PAG", beeId)
        addPoolMember(admin, "PAG", catId)

        // 目录门(行 3b):纯池成员 403(admin 200 已由 §目录既有回归覆盖)
        cat.get().uri("/api/members").exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN)

        // 他人编辑门(行 4/5,C1):cat 无 editor 行,改 bee 的能力/github 403;本人恒可
        cat
            .put().uri("/api/members/$beeId/capabilities").body(mapOf("attribute" to "K", "level" to 2))
            .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
        cat
            .put().uri("/api/members/$beeId/github-username").body(mapOf("githubUsername" to "cat-gh"))
            .exchange().expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
        cat
            .put().uri("/api/members/$catId/capabilities").body(mapOf("attribute" to "K", "level" to 2))
            .exchange().expectStatus().isOk()

        // bee 授 editor 后:目录 200(editor-anywhere);池交集成立 → 可编辑同池 cat
        setRole(admin, "PAG", beeId, "editor").expectStatus().isOk()
        bee.get().uri("/api/members").exchange().expectStatus().isOk()
        bee
            .put().uri("/api/members/$catId/github-username").body(mapOf("githubUsername" to "cat-gh"))
            .exchange().expectStatus().isOk()
        bee
            .delete().uri("/api/members/$catId/capabilities/K")
            .exchange().expectStatus().isNoContent()
    }
}
