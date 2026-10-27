package yangzhou.api.business.projectmember

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Member
import yangzhou.persistence.Project
import yangzhou.persistence.ProjectMember
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.ProjectMemberRepository
import yangzhou.persistence.repository.ProjectRepository
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
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

    private val project = Project(id = 1L, workspaceId = 9L, key = "CHE", name = "CHE")
    private val member = Member(
        id = 5L,
        workspaceId = 9L,
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
            every { projectMembers.existsByProjectIdAndMemberId(1L, 5L) } returns true
            assertFailsWith<ConflictException> { service.add("CHE", member.objectId) }
        }

        @Test
        fun savedAndReturned() {
            every { projectMembers.existsByProjectIdAndMemberId(1L, 5L) } returns false
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
            every { projectMembers.findByProjectIdOrderByIdAsc(1L) } returns listOf(
                ProjectMember(id = 10L, projectId = 1L, memberId = 5L),
                ProjectMember(id = 11L, projectId = 1L, memberId = 6L),
            )
            every { members.findById(5L) } returns java.util.Optional.of(member)
            every { members.findById(6L) } returns java.util.Optional.of(other)
            assertEquals(listOf("alice", "bob"), service.list("CHE").map { it.displayName })
        }
    }

    @Nested
    inner class Remove {

        @Test
        fun notInPoolRejected() {
            every { projectMembers.findByProjectIdAndMemberId(1L, 5L) } returns null
            assertFailsWith<NotFoundException> { service.remove("CHE", member.objectId) }
        }

        @Test
        fun rowDeleted() {
            val row = ProjectMember(id = 10L, projectId = 1L, memberId = 5L)
            every { projectMembers.findByProjectIdAndMemberId(1L, 5L) } returns row
            service.remove("CHE", member.objectId)
            verify { projectMembers.delete(row) }
        }
    }

    @Nested
    inner class RestrictedPool {

        @Test
        fun poolMemberIdsOnly() {
            every { projectMembers.findByProjectId(1L) } returns listOf(
                ProjectMember(id = 10L, projectId = 1L, memberId = 5L),
                ProjectMember(id = 11L, projectId = 1L, memberId = 6L),
            )
            assertEquals(setOf(5L, 6L), service.restrictedPool(1L))
        }

        @Test
        fun outsidePoolNotAssignable() {
            every { projectMembers.findByProjectId(1L) } returns emptyList()
            assertFailsWith<ConflictException> { service.assertAssignable(1L, 5L) }
        }
    }
}
