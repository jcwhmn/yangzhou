package yangzhou.api.business.member

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import yangzhou.api.business.workspace.WorkspaceService
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
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
    private val service =
        MemberService(
            members,
            capabilities,
            workspaceService,
            items,
            statuses,
            comments,
            itemActivities,
            itemStatusHistories,
            timeEntries,
            projectViews,
            notifications,
        )

    private val wsId = UUID(0, 9)

    private fun member(
        id: Long? = 5L,
        name: String = "alice",
        passwordHash: String? = null,
        githubUsername: String? = null,
    ) = Member(
        id = id,
        objectId = id?.let { UUID(0, it) } ?: UUID.randomUUID(),
        workspaceObjectId = wsId,
        username = null,
        passwordHash = passwordHash,
        displayName = name,
        githubUsername = githubUsername,
    )

    private val virtual = member()

    @BeforeEach
    fun setup() {
        clearMocks(
            members,
            capabilities,
            workspaceService,
            items,
            statuses,
            comments,
            itemActivities,
            itemStatusHistories,
            timeEntries,
            projectViews,
            notifications,
        )
        every { workspaceService.required() } returns Workspace(id = 1L, objectId = wsId)
        every { members.findByObjectId(virtual.objectId) } returns virtual
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Nested
    inner class Current {
        @Test
        fun resolvesByJwtSubjectNotFirstCredentialed() {
            // 第二个有密码账号(dev2)排在 findAll 首位也必须按 token 身份取 me
            // (YPJ-131 复验:me 行被 last-project 更新挪页 → 堆序漂移 → 视图/收藏串号)
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken(UUID(0, 1).toString(), null, emptyList())
            every { members.findAll() } returns
                listOf(member(id = 2L, name = "dev2", passwordHash = "h"), member(id = 1L, name = "me", passwordHash = "h"))
            every { members.findByObjectId(UUID(0, 1)) } returns member(id = 1L, name = "me", passwordHash = "h")
            assertEquals("me", service.current().displayName)
        }

        @Test
        fun unknownSubjectRejected() {
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken(UUID(0, 99).toString(), null, emptyList())
            every { members.findByObjectId(UUID(0, 99)) } returns null
            assertFailsWith<NotFoundException> { service.current() }
        }

        @Test
        fun notAuthenticatedRejected() {
            assertFailsWith<NotFoundException> { service.current() }
        }
    }

    @Nested
    inner class CreateVirtual {
        @Test
        fun duplicateNameRejected() {
            every { members.findByWorkspaceObjectId(wsId) } returns listOf(virtual)
            assertFailsWith<ConflictException> { service.createVirtual("alice") }
        }

        @Test
        fun savedWithPaletteColor() {
            every { members.findByWorkspaceObjectId(wsId) } returns emptyList()
            every { members.findAll() } returns emptyList()
            every { members.save(any()) } answers { firstArg<Member>().copy(id = 5L, objectId = UUID(0, 5)) }
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
            val status = Status(projectObjectId = UUID(0, 1), name = "To Do")
            val item =
                yangzhou.persistence.Item(
                    id = 1L,
                    objectId = UUID(0, 1),
                    projectObjectId = UUID(0, 1),
                    number = 1,
                    title = "t",
                    assigneeObjectId = virtual.objectId,
                    statusObjectId = status.objectId,
                )
            every { items.findByAssigneeObjectIdAndDeletedAtIsNull(virtual.objectId) } returns listOf(item)
            every { statuses.findByObjectId(status.objectId) } returns status
            assertFailsWith<ConflictException> { service.delete(virtual.objectId) }
        }

        @Test
        fun referencedElsewhereRejected() {
            every { items.findByAssigneeObjectIdAndDeletedAtIsNull(virtual.objectId) } returns emptyList()
            every { comments.countByAuthorMemberObjectId(UUID(0, 5)) } returns 3
            assertFailsWith<ConflictException> { service.delete(virtual.objectId) }
        }

        @Test
        fun cleanVirtualDeletedWithCapabilities() {
            every { items.findByAssigneeObjectIdAndDeletedAtIsNull(virtual.objectId) } returns emptyList()
            every { comments.countByAuthorMemberObjectId(UUID(0, 5)) } returns 0
            every { itemActivities.countByActorMemberObjectId(UUID(0, 5)) } returns 0
            every { itemStatusHistories.countByActorMemberObjectId(UUID(0, 5)) } returns 0
            every { timeEntries.countByMemberObjectId(UUID(0, 5)) } returns 0
            every { projectViews.countByCreatorObjectId(UUID(0, 5)) } returns 0
            every { notifications.countByActorMemberObjectId(UUID(0, 5)) } returns 0
            service.delete(virtual.objectId)
            verify { capabilities.deleteByMemberObjectId(UUID(0, 5)) }
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
            every { members.findByWorkspaceObjectIdAndGithubUsername(wsId, "bob") } returns other
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
            every { members.findByWorkspaceObjectIdAndGithubUsername(wsId, "alice") } returns withName
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
