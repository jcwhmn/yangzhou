package yangzhou.api.business.projectmember

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Member
import yangzhou.persistence.Project
import yangzhou.persistence.ProjectMember
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.ProjectMemberRepository
import yangzhou.persistence.repository.ProjectRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-87 单测补齐:ProjectMemberService(0047 严格化成员池)。 */
class ProjectMemberServiceUnitTest {
    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val members = mockk<MemberRepository>(relaxed = true)
    private val projectMembers = mockk<ProjectMemberRepository>(relaxed = true)
    private val leaderGuard = mockk<yangzhou.api.guard.LeaderGuard>(relaxed = true)
    private val service = ProjectMemberService(leaderGuard, projects, members, projectMembers)

    private val project = Project(id = 1L, objectId = UUID(0, 1), workspaceObjectId = UUID(0, 9), key = "CHE", name = "CHE")
    private val member =
        Member(
            id = 5L,
            objectId = UUID(0, 5),
            workspaceObjectId = UUID(0, 9),
            username = null,
            passwordHash = null,
            displayName = "alice",
        )

    @BeforeEach
    fun setup() {
        clearMocks(projects, members, projectMembers)
        every { projects.findByKey("CHE") } returns project
        every { members.findByObjectId(member.objectId) } returns member
    }

    @Nested
    inner class Add {
        @Test
        fun missingProjectRejected() {
            every { projects.findByKey("NOPE") } returns null
            assertFailsWith<NotFoundException> { service.add("NOPE", member.objectId) }
        }

        @Test
        fun missingMemberRejected() {
            every { members.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.add("CHE", UUID.randomUUID()) }
        }

        @Test
        fun duplicateRejected() {
            every { projectMembers.existsByProjectObjectIdAndMemberObjectId(UUID(0, 1), UUID(0, 5)) } returns true
            assertFailsWith<ConflictException> { service.add("CHE", member.objectId) }
        }

        @Test
        fun savedAndReturned() {
            every { projectMembers.existsByProjectObjectIdAndMemberObjectId(UUID(0, 1), UUID(0, 5)) } returns false
            every { projectMembers.save(any()) } answers { firstArg() }
            val dto = service.add("CHE", member.objectId)
            assertEquals("alice", dto.displayName)
            assertEquals(true, dto.virtual) // passwordHash 为空 = 虚拟
            verify { projectMembers.save(any()) }
        }
    }

    @Nested
    inner class ListPool {
        @Test
        fun orderedByJoin() {
            val other = member.copy(id = 6L, displayName = "bob")
            every { projectMembers.findByProjectObjectIdOrderByIdAsc(UUID(0, 1)) } returns
                listOf(
                    ProjectMember(id = 10L, projectObjectId = UUID(0, 1), memberObjectId = UUID(0, 5)),
                    ProjectMember(id = 11L, projectObjectId = UUID(0, 1), memberObjectId = UUID(0, 6)),
                )
            every { members.findByObjectId(UUID(0, 5)) } returns (member)
            every { members.findByObjectId(UUID(0, 6)) } returns (other)
            assertEquals(listOf("alice", "bob"), service.list("CHE").map { it.displayName })
        }
    }

    @Nested
    inner class Remove {
        @Test
        fun notInPoolRejected() {
            every { projectMembers.findByProjectObjectIdAndMemberObjectId(UUID(0, 1), UUID(0, 5)) } returns null
            assertFailsWith<NotFoundException> { service.remove("CHE", member.objectId) }
        }

        @Test
        fun rowDeleted() {
            val row = ProjectMember(id = 10L, projectObjectId = UUID(0, 1), memberObjectId = UUID(0, 5))
            every { projectMembers.findByProjectObjectIdAndMemberObjectId(UUID(0, 1), UUID(0, 5)) } returns row
            service.remove("CHE", member.objectId)
            verify { projectMembers.delete(row) }
        }
    }

    @Nested
    inner class RestrictedPool {
        @Test
        fun poolMemberObjectIdsOnly() {
            every { projectMembers.findByProjectObjectId(UUID(0, 1)) } returns
                listOf(
                    ProjectMember(id = 10L, projectObjectId = UUID(0, 1), memberObjectId = UUID(0, 5)),
                    ProjectMember(id = 11L, projectObjectId = UUID(0, 1), memberObjectId = UUID(0, 6)),
                )
            assertEquals(setOf(UUID(0, 5), UUID(0, 6)), service.restrictedPool(UUID(0, 1)))
        }

        @Test
        fun outsidePoolNotAssignable() {
            every { projectMembers.findByProjectObjectId(UUID(0, 1)) } returns emptyList()
            assertFailsWith<ConflictException> { service.assertAssignable(UUID(0, 1), UUID(0, 5)) }
        }
    }
}
