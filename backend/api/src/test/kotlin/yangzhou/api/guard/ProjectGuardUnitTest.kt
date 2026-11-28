package yangzhou.api.guard

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import yangzhou.api.error.ForbiddenException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Member
import yangzhou.persistence.MemberRole
import yangzhou.persistence.Project
import yangzhou.persistence.ProjectMember
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.MemberRoleRepository
import yangzhou.persistence.repository.ProjectMemberRepository
import yangzhou.persistence.repository.ProjectRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** YPJ-299 单测(spec 0177 §5):ProjectGuard 四门判定矩阵。 */
class ProjectGuardUnitTest {
    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val projectMembers = mockk<ProjectMemberRepository>(relaxed = true)
    private val membersRepo = mockk<MemberRepository>(relaxed = true)
    private val memberRoles = mockk<MemberRoleRepository>(relaxed = true)
    private val adminGuard = mockk<AdminGuard>(relaxed = true)
    private val guard = ProjectGuard(projects, projectMembers, membersRepo, memberRoles, adminGuard)

    private val projectObjectId = UUID(0, 7)
    private val projectId = 100L
    private val memberId = 1L
    private val meObjectId = UUID(0, 1)
    private val otherObjectId = UUID(0, 2)

    private val project =
        Project(id = projectId, objectId = projectObjectId, workspaceObjectId = UUID(0, 9), key = "PAG", name = "PAG")

    @BeforeEach
    fun setUp() {
        SecurityContextHolder.clearContext()
        every { projects.findByKey("PAG") } returns project
        every { projects.findByObjectId(projectObjectId) } returns project
        every { adminGuard.isAdmin() } returns false
    }

    private fun loginAs(objectId: UUID = meObjectId) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(objectId.toString(), null, emptyList())
        every { membersRepo.findByObjectId(objectId) } returns
            Member(
                id = memberId,
                objectId = objectId,
                workspaceObjectId = UUID(0, 9),
                username = null,
                passwordHash = null,
                displayName = "m$objectId",
            )
    }

    private fun roleRow(role: String, pid: Long? = projectId) = MemberRole(id = 21, memberId = memberId, role = role, projectId = pid)

    private fun givenPoolMember() {
        every {
            projectMembers.existsByProjectObjectIdAndMemberObjectId(projectObjectId, meObjectId)
        } returns true
    }

    private fun givenRoleRows(vararg roles: String) {
        every { memberRoles.findByMemberId(memberId) } returns roles.map { roleRow(it) }
    }

    // ---------- requireEditor ----------

    @Test
    fun `editor 行通过(key 与 objectId 双入口)`() {
        loginAs()
        givenRoleRows(MemberRole.ROLE_EDITOR)
        guard.requireEditor("PAG")
        guard.requireEditor(projectObjectId)
    }

    @Test
    fun `viewer 行与池内无角色 均 403`() {
        loginAs()
        givenRoleRows(MemberRole.ROLE_VIEWER)
        assertFailsWith<ForbiddenException> { guard.requireEditor("PAG") }
        givenRoleRows()
        assertFailsWith<ForbiddenException> { guard.requireEditor(projectObjectId) }
    }

    @Test
    fun `admin 兜底通过(无池行无角色行)`() {
        loginAs()
        every { adminGuard.isAdmin() } returns true
        guard.requireEditor("PAG")
        guard.requireEditor(projectObjectId)
    }

    @Test
    fun `项目不存在先于权限判 NotFound`() {
        every { projects.findByKey("NOPE") } returns null
        every { projects.findByObjectId(any()) } returns null
        assertFailsWith<NotFoundException> { guard.requireEditor("NOPE") }
        assertFailsWith<NotFoundException> { guard.requireEditor(UUID(9, 9)) }
    }

    @Test
    fun `无身份上下文 403`() {
        assertFailsWith<ForbiddenException> { guard.requireEditor("PAG") }
    }

    // ---------- requireMember ----------

    @Test
    fun `池行通过`() {
        loginAs()
        givenPoolMember()
        guard.requireMember(projectObjectId)
        guard.requireMember("PAG")
    }

    @Test
    fun `editor 行与 viewer 行均通过(requireMember)`() {
        loginAs()
        givenRoleRows(MemberRole.ROLE_VIEWER)
        guard.requireMember(projectObjectId)
        givenRoleRows(MemberRole.ROLE_EDITOR)
        guard.requireMember(projectObjectId)
    }

    @Test
    fun `requireMember 全无行 403 admin 兜底通过`() {
        loginAs()
        givenRoleRows()
        assertFailsWith<ForbiddenException> { guard.requireMember(projectObjectId) }
        every { adminGuard.isAdmin() } returns true
        guard.requireMember(projectObjectId)
    }

    // ---------- requireEditorAnywhere ----------

    @Test
    fun `任一 editor 行通过 仅 viewer 403`() {
        loginAs()
        givenRoleRows(MemberRole.ROLE_EDITOR)
        guard.requireEditorAnywhere()
        givenRoleRows(MemberRole.ROLE_VIEWER)
        assertFailsWith<ForbiddenException> { guard.requireEditorAnywhere() }
    }

    @Test
    fun `editorAnywhere admin 兜底通过`() {
        every { adminGuard.isAdmin() } returns true
        guard.requireEditorAnywhere()
    }

    // ---------- requirePoolEditor ----------

    @Test
    fun `目标池行与我 editor 行项目交集非空 通过`() {
        loginAs()
        val sharedProjectId = 200L
        val sharedProjectObjectId = UUID(0, 11)
        every { memberRoles.findByMemberId(memberId) } returns listOf(roleRow(MemberRole.ROLE_EDITOR, sharedProjectId))
        every { projectMembers.findByMemberObjectId(otherObjectId) } returns
            listOf(ProjectMember(id = 31, projectObjectId = sharedProjectObjectId, memberObjectId = otherObjectId))
        every { projects.findByObjectId(sharedProjectObjectId) } returns
            Project(id = sharedProjectId, objectId = sharedProjectObjectId, workspaceObjectId = UUID(0, 9), key = "SHR", name = "SHR")
        guard.requirePoolEditor(otherObjectId)
    }

    @Test
    fun `无交集 403 我无 editor 行 403`() {
        loginAs()
        val foreignObjectId = UUID(0, 12)
        every { memberRoles.findByMemberId(memberId) } returns listOf(roleRow(MemberRole.ROLE_EDITOR, projectId))
        every { projectMembers.findByMemberObjectId(otherObjectId) } returns
            listOf(ProjectMember(id = 32, projectObjectId = UUID(0, 13), memberObjectId = otherObjectId))
        every { projects.findByObjectId(UUID(0, 13)) } returns
            Project(id = 300L, objectId = UUID(0, 13), workspaceObjectId = UUID(0, 9), key = "FRN", name = "FRN")
        assertFailsWith<ForbiddenException> { guard.requirePoolEditor(otherObjectId) }

        every { memberRoles.findByMemberId(memberId) } returns emptyList()
        assertFailsWith<ForbiddenException> { guard.requirePoolEditor(foreignObjectId) }
    }

    @Test
    fun `poolEditor admin 兜底通过`() {
        every { adminGuard.isAdmin() } returns true
        guard.requirePoolEditor(otherObjectId)
    }
}
