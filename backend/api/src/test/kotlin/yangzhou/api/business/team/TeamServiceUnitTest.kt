package yangzhou.api.business.team

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import yangzhou.api.business.member.MemberService
import yangzhou.api.business.workspace.WorkspaceService
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Member
import yangzhou.persistence.Team
import yangzhou.persistence.Workspace
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.TeamMemberRepository
import yangzhou.persistence.repository.TeamRepository
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** YPJ-87 单测补齐:TeamService(纯分组池,视图过滤用)。 */
class TeamServiceUnitTest {

    private val teams = mockk<TeamRepository>(relaxed = true)
    private val teamMembers = mockk<TeamMemberRepository>(relaxed = true)
    private val members = mockk<MemberRepository>(relaxed = true)
    private val memberService = mockk<MemberService>(relaxed = true)
    private val workspaceService = mockk<WorkspaceService>(relaxed = true)
    private val service = TeamService(teams, teamMembers, members, memberService, workspaceService)

    private val wsId = UUID(0, 9)
    private val team = Team(id = 1L, objectId = UUID(0, 1), workspaceObjectId = wsId, name = "后端")
    private val alice = Member(id = 5L, objectId = UUID(0, 5), workspaceObjectId = wsId, username = null, passwordHash = null, displayName = "alice")

    @BeforeEach
    fun setup() {
        clearMocks(teams, teamMembers, members, memberService, workspaceService)
        every { workspaceService.required() } returns Workspace(id = 1L, objectId = wsId)
        every { teams.findByObjectId(team.objectId) } returns team
    }

    @Nested
    inner class Create {

        @Test
        fun duplicateNameRejected() {
            every { teams.existsByWorkspaceObjectIdAndName(wsId, "后端") } returns true
            assertFailsWith<ConflictException> { service.create("后端") }
        }

        @Test
        fun createdEmpty() {
            every { teams.existsByWorkspaceObjectIdAndName(wsId, "前端") } returns false
            every { teams.save(any()) } answers { firstArg<Team>().copy(id = 2L, objectId = UUID(0, 2)) }
            val dto = service.create("前端")
            assertEquals("前端", dto.name)
            assertTrue(dto.members.isEmpty())
        }
    }

    @Nested
    inner class Rename {

        @Test
        fun missingTeamRejected() {
            every { teams.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.rename(UUID.randomUUID(), "x") }
        }

        @Test
        fun renamed() {
            every { teams.save(any<Team>()) } answers { firstArg<Team>() }
            assertEquals("新名", service.rename(team.objectId, "新名").name)
        }
    }

    @Nested
    inner class DeleteTeam {

        @Test
        fun deleted() {
            service.delete(team.objectId)
            verify { teams.delete(team) } // team_member FK 级联
        }
    }

    @Nested
    inner class Members {

        @Test
        fun addMemberObjectIdempotent() {
            every { memberService.requireMember(alice.objectId) } returns alice
            every { members.findByObjectId(alice.objectId) } returns alice
            every { teamMembers.existsByTeamObjectIdAndMemberObjectId(UUID(0, 1), UUID(0, 5)) } returns true
            val dto = service.addMember(team.objectId, alice.objectId)
            io.mockk.verify(exactly = 0) { teamMembers.save(any()) } // 已在组内不重插
            assertEquals("后端", dto.name)
        }

        @Test
        fun removeMemberWithoutRowIsNoop() {
            val bob = alice.copy(id = 6L, objectId = UUID(0, 6), displayName = "bob")
            every { members.findByObjectId(bob.objectId) } returns bob
            every { teamMembers.findByTeamObjectId(UUID(0, 1)) } returns emptyList()
            service.removeMember(team.objectId, bob.objectId) // 不在组内 → 静默
            io.mockk.verify(exactly = 0) { teamMembers.delete(any()) }
        }

        @Test
        fun toDtoMapsMemberNames() {
            val bob = alice.copy(id = 6L, objectId = UUID(0, 6), displayName = "bob")
            every { members.findAll() } returns listOf(alice, bob)
            every { teamMembers.findByTeamObjectId(UUID(0, 1)) } returns listOf(
                yangzhou.persistence.TeamMember(id = 10L, objectId = UUID(0, 10), teamObjectId = UUID(0, 1), memberObjectId = UUID(0, 6)),
            )
            every { teams.save(any<Team>()) } answers { firstArg<Team>() }
            val dto = service.rename(team.objectId, "后端")
            assertEquals(listOf("bob"), dto.members.map { it.displayName })
        }
    }
}
