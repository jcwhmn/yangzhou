package yangzhou.api.business.member

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import yangzhou.api.business.workspace.WorkspaceService
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Member
import yangzhou.persistence.Status
import yangzhou.persistence.Workspace
import yangzhou.persistence.repository.CapabilityRepository
import yangzhou.persistence.repository.CommentRepository
import yangzhou.persistence.repository.ItemActivityRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ItemStatusHistoryRepository
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.NotificationRepository
import yangzhou.persistence.repository.ProjectViewRepository
import yangzhou.persistence.repository.StatusRepository
import yangzhou.persistence.repository.TimeEntryRepository
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-87 单测补齐:MemberService(虚拟成员/删除约束/github username/lastProject)。 */
class MemberServiceUnitTest {

    private val members = mockk<MemberRepository>(relaxed = true)
    private val capabilities = mockk<CapabilityRepository>(relaxed = true)
    private val workspaceService = mockk<WorkspaceService>(relaxed = true)
    private val items = mockk<ItemRepository>(relaxed = true)
    private val statuses = mockk<StatusRepository>(relaxed = true)
    private val comments = mockk<CommentRepository>(relaxed = true)
    private val itemActivities = mockk<ItemActivityRepository>(relaxed = true)
    private val itemStatusHistories = mockk<ItemStatusHistoryRepository>(relaxed = true)
    private val timeEntries = mockk<TimeEntryRepository>(relaxed = true)
    private val projectViews = mockk<ProjectViewRepository>(relaxed = true)
    private val notifications = mockk<NotificationRepository>(relaxed = true)
    private val service = MemberService(
        members, capabilities, workspaceService, items, statuses,
        comments, itemActivities, itemStatusHistories, timeEntries, projectViews, notifications,
    )

    private val wsId = 9L
    private fun member(
        id: Long? = 5L,
        name: String = "alice",
        passwordHash: String? = null,
        githubUsername: String? = null,
    ) = Member(
        id = id, workspaceId = wsId, username = null, passwordHash = passwordHash,
        displayName = name, githubUsername = githubUsername,
    )

    private val virtual = member()

    @BeforeEach
    fun setup() {
        clearMocks(
            members, capabilities, workspaceService, items, statuses,
            comments, itemActivities, itemStatusHistories, timeEntries, projectViews, notifications,
        )
        every { workspaceService.required() } returns Workspace(id = wsId)
        every { members.findByObjectId(virtual.objectId) } returns virtual
    }

    @Nested
    inner class Current {

        @Test
        fun onlyCredentialedMemberCounts() {
            every { members.findAll() } returns listOf(virtual, member(id = 1L, name = "me", passwordHash = "h"))
            assertEquals("me", service.current().displayName)
        }

        @Test
        fun notBootstrappedRejected() {
            every { members.findAll() } returns listOf(virtual)
            assertFailsWith<NotFoundException> { service.current() }
        }
    }

    @Nested
    inner class CreateVirtual {

        @Test
        fun duplicateNameRejected() {
            every { members.findByWorkspaceId(wsId) } returns listOf(virtual)
            assertFailsWith<ConflictException> { service.createVirtual("alice") }
        }

        @Test
        fun savedWithPaletteColor() {
            every { members.findByWorkspaceId(wsId) } returns emptyList()
            every { members.findAll() } returns emptyList()
            every { members.save(any()) } answers { firstArg<Member>().copy(id = 5L) }
            val dto = service.createVirtual("bob")
            assertEquals("bob", dto.displayName)
            assertEquals(true, dto.virtual)
            assertEquals("#e57373", dto.color) // 调色板第 0 色
        }
    }

    @Nested
    inner class Delete {

        @Test
        fun loginAccountRejected() {
            val me = member(id = 1L, name = "me", passwordHash = "h")
            every { members.findByObjectId(me.objectId) } returns me
            assertFailsWith<ConflictException> { service.delete(me.objectId) }
        }

        @Test
        fun activeAssigneeRejected() {
            val status = Status(projectId = 1L, name = "To Do")
            val item = yangzhou.persistence.Item(
                id = 1L, projectId = 1L, number = 1, title = "t",
                assigneeObjectId = virtual.objectId, statusObjectId = status.objectId,
            )
            every { items.findByAssigneeObjectIdAndDeletedAtIsNull(virtual.objectId) } returns listOf(item)
            every { statuses.findByObjectId(status.objectId) } returns status
            assertFailsWith<ConflictException> { service.delete(virtual.objectId) }
        }

        @Test
        fun referencedElsewhereRejected() {
            every { items.findByAssigneeObjectIdAndDeletedAtIsNull(virtual.objectId) } returns emptyList()
            every { comments.countByAuthorMemberId(5L) } returns 3L
            assertFailsWith<ConflictException> { service.delete(virtual.objectId) }
        }

        @Test
        fun cleanVirtualDeletedWithCapabilities() {
            every { items.findByAssigneeObjectIdAndDeletedAtIsNull(virtual.objectId) } returns emptyList()
            every { comments.countByAuthorMemberId(5L) } returns 0L
            every { itemActivities.countByActorMemberId(5L) } returns 0L
            every { itemStatusHistories.countByActorMemberId(5L) } returns 0L
            every { timeEntries.countByMemberId(5L) } returns 0L
            every { projectViews.countByCreatorId(5L) } returns 0L
            every { notifications.countByActorMemberId(5L) } returns 0L
            service.delete(virtual.objectId)
            verify { capabilities.deleteByMemberId(5L) }
            verify { members.delete(virtual) }
        }
    }

    @Nested
    inner class SetGithubUsername {

        @Test
        fun invalidFormatRejected() {
            assertFailsWith<BadRequestException> { service.setGithubUsername(virtual.objectId, "has space") }
        }

        @Test
        fun takenByOtherRejected() {
            val other = member(id = 6L, name = "bob", githubUsername = "bob")
            every { members.findByWorkspaceIdAndGithubUsername(wsId, "bob") } returns other
            assertFailsWith<ConflictException> { service.setGithubUsername(virtual.objectId, "bob") }
        }

        @Test
        fun emptyClearsUsername() {
            val withName = member(githubUsername = "alice")
            every { members.findByObjectId(withName.objectId) } returns withName
            every { members.save(any()) } answers { firstArg() }
            assertEquals(null, service.setGithubUsername(withName.objectId, "  ").githubUsername)
        }

        @Test
        fun sameNameOnSelfAllowed() {
            val withName = member(githubUsername = "alice")
            every { members.findByObjectId(withName.objectId) } returns withName
            every { members.findByWorkspaceIdAndGithubUsername(wsId, "alice") } returns withName
            every { members.save(any()) } answers { firstArg() }
            assertEquals("alice", service.setGithubUsername(withName.objectId, "alice").githubUsername)
        }
    }

    @Nested
    inner class SetLastProject {

        @Test
        fun trimmedKeySaved() {
            every { members.save(any()) } answers { firstArg() }
            assertEquals("CHE", service.setLastProjectKey(virtual.objectId, " CHE ").lastProjectKey)
        }

        @Test
        fun blankClearsKey() {
            every { members.save(any()) } answers { firstArg() }
            assertEquals(null, service.setLastProjectKey(virtual.objectId, "  ").lastProjectKey)
        }

        @Test
        fun missingMemberRejected() {
            every { members.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.setLastProjectKey(UUID.randomUUID(), "x") }
        }
    }
}
