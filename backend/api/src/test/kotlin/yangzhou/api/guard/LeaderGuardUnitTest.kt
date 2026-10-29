package yangzhou.api.guard

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import yangzhou.api.error.ForbiddenException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Member
import yangzhou.persistence.ProjectMember
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.ProjectMemberRepository
import yangzhou.persistence.repository.ProjectRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** YPJ-102 单测(spec 0081):LeaderGuard 规则矩阵。R1 落在 ProjectService.create,此处测 R2/门槛判定。 */
class LeaderGuardUnitTest {

    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val projectMembers = mockk<ProjectMemberRepository>(relaxed = true)
    private val membersRepo = mockk<MemberRepository>(relaxed = true)
    private val guard = LeaderGuard(projects, projectMembers, membersRepo)

    private val projectObjectId = UUID(0, 7)
    private val leaderId = UUID(0, 1)
    private val plainId = UUID(0, 2)
    private val outsiderId = UUID(0, 3)

    private val leaderRow = ProjectMember(id = 11L, projectObjectId = projectObjectId, memberObjectId = leaderId, role = ProjectMember.ROLE_LEADER)
    private val plainRow = ProjectMember(id = 12L, projectObjectId = projectObjectId, memberObjectId = plainId, role = ProjectMember.ROLE_MEMBER)

    @BeforeEach
    fun clearAuth() {
        SecurityContextHolder.clearContext()
        every { projects.findByKey("LDR") } returns yangzhou.persistence.Project(id = 1, objectId = projectObjectId, workspaceObjectId = UUID(0, 9), key = "LDR", name = "LDR")
        every { projectMembers.findByProjectObjectId(projectObjectId) } returns listOf(leaderRow, plainRow)
    }

    private fun loginAs(memberObjectId: UUID) {
        val objectId = UUID.randomUUID()
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(objectId.toString(), null, emptyList())
        every { membersRepo.findByObjectId(objectId) } returns Member(id = 1, objectId = memberObjectId, workspaceObjectId = UUID(0, 9), username = null, passwordHash = null, displayName = "m$memberObjectId")
    }

    @Nested
    inner class R2NoLeader兼容 {
        @Test
        fun `无 leader 行时放行(与历史登录即可管一致)`() {
            every { projectMembers.findByProjectObjectId(projectObjectId) } returns listOf(plainRow)
            loginAs(plainId) // 甚至不在池内的调用者也放行(单人/存量零感知的读法,As-built 记录)
            guard.requireLeader(projectObjectId)
        }

        @Test
        fun `空池且无 leader 放行`() {
            every { projectMembers.findByProjectObjectId(projectObjectId) } returns emptyList()
            loginAs(plainId)
            guard.requireLeader(projectObjectId)
        }
    }

    @Nested
    inner class 有Leader时门槛 {
        @Test
        fun `leader 通过`() {
            loginAs(leaderId)
            guard.requireLeader(projectObjectId)
            guard.requireLeader("LDR") // key 入口同判
        }

        @Test
        fun `池内非 leader 403`() {
            loginAs(plainId)
            assertFailsWith<ForbiddenException> { guard.requireLeader(projectObjectId) }
        }

        @Test
        fun `池外成员 403`() {
            loginAs(outsiderId)
            assertFailsWith<ForbiddenException> { guard.requireLeader(projectObjectId) }
        }

        @Test
        fun `无身份上下文 403`() {
            assertFailsWith<ForbiddenException> { guard.requireLeader(projectObjectId) }
        }

        @Test
        fun `principal 非合法 objectId 403`() {
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken("not-a-uuid", null, emptyList())
            every { membersRepo.findByObjectId(any()) } returns null
            assertFailsWith<ForbiddenException> { guard.requireLeader(projectObjectId) }
        }
    }

    @Test
    fun `项目不存在先于权限判 NotFound`() {
        every { projects.findByKey("NOPE") } returns null
        assertFailsWith<NotFoundException> { guard.requireLeader("NOPE") }
    }
}
