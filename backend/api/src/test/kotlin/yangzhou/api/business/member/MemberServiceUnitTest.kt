package yangzhou.api.business.member

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import yangzhou.api.business.workspace.WorkspaceService
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.api.guard.AdminGuard
import yangzhou.persistence.Member
import yangzhou.persistence.MemberRole
import yangzhou.persistence.Project
import yangzhou.persistence.ProjectMember
import yangzhou.persistence.Status
import yangzhou.persistence.Workspace
import yangzhou.persistence.repository.CapabilityRepository
import yangzhou.persistence.repository.CommentRepository
import yangzhou.persistence.repository.ItemActivityRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ItemStatusHistoryRepository
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.MemberRoleRepository
import yangzhou.persistence.repository.NotificationRepository
import yangzhou.persistence.repository.ProjectMemberRepository
import yangzhou.persistence.repository.ProjectRepository
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
    private val encoder = mockk<PasswordEncoder>(relaxed = true)
    private val blacklist = mockk<MemberBlacklistService>(relaxed = true)
    private val memberRoles = mockk<MemberRoleRepository>(relaxed = true)
    private val projectMembers = mockk<ProjectMemberRepository>(relaxed = true)
    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val adminGuard = mockk<AdminGuard>(relaxed = true)
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
            encoder,
            blacklist,
            memberRoles,
            projectMembers,
            projects,
            adminGuard,
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
            encoder,
            blacklist,
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
    inner class Create {
        @Test
        fun virtualDuplicateNameRejected() {
            every { members.findByWorkspaceObjectId(wsId) } returns listOf(virtual)
            assertFailsWith<ConflictException> { service.create("alice", null, null, null) }
        }

        @Test
        fun virtualSavedWithPaletteColor() {
            every { members.findByWorkspaceObjectId(wsId) } returns emptyList()
            every { members.findAll() } returns emptyList()
            every { members.save(any()) } answers { firstArg<Member>().copy(id = 5L, objectId = UUID(0, 5)) }
            val dto = service.create("bob", null, null, null)
            assertEquals("bob", dto.displayName)
            assertEquals(true, dto.virtual)
            assertEquals("#e57373", dto.color) // 调色板第 0 色
        }

        // ---- spec 0177(S2)真账号分支 ----

        private val projectId = UUID(0, 100)

        private fun stubRealCreate(name: String = "bob") {
            every { members.findByWorkspaceObjectId(wsId) } returns emptyList()
            every { members.findAll() } returns emptyList()
            every { members.save(any()) } answers { firstArg<Member>().copy(id = 5L, objectId = UUID(0, 5)) }
            every { members.findByUsername("bob") } returns null
            every { projects.findByObjectId(projectId) } returns Project(id = 9L, objectId = projectId, workspaceObjectId = wsId, key = "CHE", name = "CHE")
        }

        @Test
        fun realAccountPoolsIntoProjects() {
            stubRealCreate()
            // mockk 坑:relaxed 泛型 save 返回 Object 会 CCE,显式 firstArg
            every { projectMembers.save(any()) } answers { firstArg<ProjectMember>() }
            every { projectMembers.existsByProjectObjectIdAndMemberObjectId(projectId, UUID(0, 5)) } returns false
            val dto = service.create("bob", "bob", "password8", listOf(projectId))
            assertEquals(false, dto.virtual)
            assertEquals("bob", dto.username)
            verify { projectMembers.save(any()) }
        }

        @Test
        fun realAccountWithoutProjectRejected() {
            // C8:必挂 ≥1 项目
            stubRealCreate()
            assertFailsWith<BadRequestException> { service.create("bob", "bob", "password8", null) }
        }

        @Test
        fun usernameTakenRejected() {
            stubRealCreate()
            every { members.findByUsername("bob") } returns member(id = 6L, name = "bob6")
            assertFailsWith<ConflictException> { service.create("bob", "bob", "password8", listOf(projectId)) }
        }

        @Test
        fun halfCredentialsRejected() {
            stubRealCreate()
            assertFailsWith<BadRequestException> { service.create("bob", "bob", null, listOf(projectId)) }
            assertFailsWith<BadRequestException> { service.create("bob", null, "password8", listOf(projectId)) }
        }

        @Test
        fun virtualWithProjectIdsRejected() {
            stubRealCreate()
            assertFailsWith<BadRequestException> { service.create("bob", null, null, listOf(projectId)) }
        }
    }

    @Nested
    inner class Delete {
        /** S2 起 delete 走 current() 判自删,用例需登录态。 */
        private fun loginAs(me: Member) {
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken(me.objectId.toString(), null, emptyList())
            every { members.findByObjectId(me.objectId) } returns me
        }

        @Test
        fun selfDeleteRejected() {
            // spec 0177(S2):真人可删,唯自删 409(替代 S1「登录账号不可删」)
            val me = member(id = 1L, name = "me", passwordHash = "h")
            loginAs(me)
            assertFailsWith<ConflictException> { service.delete(me.objectId) }
        }

        @Test
        fun activeAssigneeRejected() {
            loginAs(member(id = 1L, name = "me", passwordHash = "h"))
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
            loginAs(member(id = 1L, name = "me", passwordHash = "h"))
            every { items.findByAssigneeObjectIdAndDeletedAtIsNull(virtual.objectId) } returns emptyList()
            every { comments.countByAuthorMemberObjectId(UUID(0, 5)) } returns 3
            assertFailsWith<ConflictException> { service.delete(virtual.objectId) }
        }

        @Test
        fun realAccountDeletedByAdmin() {
            // S2 放开:真账号(非自己)可删,守卫照走
            loginAs(member(id = 1L, name = "me", passwordHash = "h"))
            val real = member(id = 7L, name = "dev2", passwordHash = "h")
            every { members.findByObjectId(real.objectId) } returns real
            every { items.findByAssigneeObjectIdAndDeletedAtIsNull(real.objectId) } returns emptyList()
            every { comments.countByAuthorMemberObjectId(UUID(0, 7)) } returns 0
            every { itemActivities.countByActorMemberObjectId(UUID(0, 7)) } returns 0
            every { itemStatusHistories.countByActorMemberObjectId(UUID(0, 7)) } returns 0
            every { timeEntries.countByMemberObjectId(UUID(0, 7)) } returns 0
            every { projectViews.countByCreatorObjectId(UUID(0, 7)) } returns 0
            every { notifications.countByActorMemberObjectId(UUID(0, 7)) } returns 0
            service.delete(real.objectId)
            verify { members.delete(real) }
        }

        @Test
        fun cleanVirtualDeletedWithCapabilities() {
            loginAs(member(id = 1L, name = "me", passwordHash = "h"))
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

    @Nested
    inner class ChangeMyPassword {
        private val me = member(id = 1L, name = "me", passwordHash = "old-hash")

        private fun loginAs() {
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken(me.objectId.toString(), null, emptyList())
            every { members.findByObjectId(me.objectId) } returns me
        }

        @Test
        fun wrongOldPasswordRejected() {
            // spec 0177 §2.3 B 表:old 错 → 401(BadCredentials)
            loginAs()
            every { encoder.matches("wrong", "old-hash") } returns false
            assertFailsWith<BadCredentialsException> { service.changeMyPassword("wrong", "new-pass-123") }
        }

        @Test
        fun successRehashesAndKicksAllSessions() {
            // spec §4.3 R7 拍板「踢」:改密成功 → 黑名单 upsert,含当前会话
            loginAs()
            every { encoder.matches("old", "old-hash") } returns true
            every { encoder.encode("new-pass-123") } returns "new-hash"
            every { members.save(any()) } answers { firstArg() }
            service.changeMyPassword("old", "new-pass-123")
            verify {
                members.save(withArg { assertEquals("new-hash", it.passwordHash) })
                blacklist.upsert(me.id!!)
            }
        }
    }

    // ---- spec 0177(S2/YPJ-293)账号治理 ----

    @Nested
    inner class Whoami {
        @Test
        fun adminFlagFromGuard() {
            val me = member(id = 1L, name = "me", passwordHash = "h")
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken(me.objectId.toString(), null, emptyList())
            every { members.findByObjectId(me.objectId) } returns me
            every { adminGuard.isAdmin() } returns true
            every { memberRoles.findByMemberId(1L) } returns listOf(MemberRole(id = 1L, memberId = 1L, role = MemberRole.ROLE_ADMIN))
            val dto = service.whoami()
            assertEquals(true, dto.admin)
            assertEquals(false, dto.virtual)
        }

        @Test
        fun virtualNonAdminWithEmptyProjectRoles() {
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken(virtual.objectId.toString(), null, emptyList())
            every { members.findByObjectId(virtual.objectId) } returns virtual
            every { adminGuard.isAdmin() } returns false
            val dto = service.whoami()
            assertEquals(false, dto.admin)
            assertEquals(true, dto.virtual)
            assertEquals(0, dto.projectRoles.size) // S3 前恒空(形状现在定死)
        }
    }

    @Nested
    inner class ResetCredentials {
        private val dev2 = member(id = 7L, name = "dev2", passwordHash = "old-hash")

        @Test
        fun realResetRehashesAndKicks() {
            every { members.findByObjectId(dev2.objectId) } returns dev2
            every { encoder.encode("new-pass-123") } returns "new-hash"
            every { members.save(any()) } answers { firstArg() }
            service.resetCredentials(dev2.objectId, null, "new-pass-123")
            verify { blacklist.upsert(7L) }
        }

        @Test
        fun virtualUpgradeRequiresUsername() {
            every { members.findByObjectId(virtual.objectId) } returns virtual
            assertFailsWith<BadRequestException> { service.resetCredentials(virtual.objectId, null, "password8") }
        }

        @Test
        fun virtualUpgradeSetsUsernameAndHash() {
            every { members.findByObjectId(virtual.objectId) } returns virtual
            every { members.findByUsername("bob") } returns null
            every { encoder.encode("password8") } returns "hash"
            every { members.save(any()) } answers { firstArg() }
            val dto = service.resetCredentials(virtual.objectId, " bob ", "password8")
            assertEquals("bob", dto.username)
            assertEquals(false, dto.virtual)
        }

        @Test
        fun virtualUpgradeUsernameTakenRejected() {
            every { members.findByObjectId(virtual.objectId) } returns virtual
            every { members.findByUsername("bob") } returns member(id = 6L, name = "bob6")
            assertFailsWith<ConflictException> { service.resetCredentials(virtual.objectId, "bob", "password8") }
        }
    }

    @Nested
    inner class SetStatus {
        private fun loginAs(me: Member) {
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken(me.objectId.toString(), null, emptyList())
            every { members.findByObjectId(me.objectId) } returns me
        }

        @Test
        fun invalidStatusRejected() {
            assertFailsWith<BadRequestException> { service.setStatus(virtual.objectId, "paused") }
        }

        @Test
        fun disableUpsertsBlacklistCaseNormalized() {
            loginAs(member(id = 1L, name = "me", passwordHash = "h"))
            every { adminGuard.isAdmin() } returns true
            every { adminGuard.countWorkspaceAdmins() } returns 1
            every { members.findByObjectId(virtual.objectId) } returns virtual
            every { members.save(any()) } answers { firstArg() }
            service.setStatus(virtual.objectId, "DISABLED")
            verify { blacklist.upsert(5L) }
        }

        @Test
        fun enableClearsBlacklist() {
            every { members.findByObjectId(virtual.objectId) } returns virtual
            every { members.save(any()) } answers { firstArg() }
            service.setStatus(virtual.objectId, "active")
            verify { blacklist.clear(5L) }
        }

        @Test
        fun lastAdminSelfDisableRejected() {
            val me = member(id = 1L, name = "me", passwordHash = "h")
            loginAs(me)
            every { adminGuard.isAdmin() } returns true
            every { adminGuard.countWorkspaceAdmins() } returns 1
            assertFailsWith<ConflictException> { service.setStatus(me.objectId, "disabled") }
        }
    }

    @Nested
    inner class SetAdmin {
        @Test
        fun grantInsertsAdminRow() {
            val target = member(id = 7L, name = "dev2", passwordHash = "h")
            every { members.findByObjectId(target.objectId) } returns target
            every { memberRoles.findByMemberIdAndRoleAndProjectIdIsNull(7L, MemberRole.ROLE_ADMIN) } returns null
            every { memberRoles.save(any()) } answers { firstArg<MemberRole>() }
            service.setAdmin(target.objectId, true)
            verify { memberRoles.save(any()) }
        }

        @Test
        fun grantIdempotentNoExtraRow() {
            val target = member(id = 7L, name = "dev2", passwordHash = "h")
            every { members.findByObjectId(target.objectId) } returns target
            every { memberRoles.findByMemberIdAndRoleAndProjectIdIsNull(7L, MemberRole.ROLE_ADMIN) } returns MemberRole(id = 9L, memberId = 7L, role = MemberRole.ROLE_ADMIN)
            service.setAdmin(target.objectId, true)
            verify(exactly = 0) { memberRoles.save(any()) }
        }

        @Test
        fun removeDeletesRow() {
            val target = member(id = 7L, name = "dev2", passwordHash = "h")
            every { members.findByObjectId(target.objectId) } returns target
            every { memberRoles.findByMemberIdAndRoleAndProjectIdIsNull(7L, MemberRole.ROLE_ADMIN) } returns MemberRole(id = 9L, memberId = 7L, role = MemberRole.ROLE_ADMIN)
            every { adminGuard.countWorkspaceAdmins() } returns 2
            service.setAdmin(target.objectId, false)
            verify { memberRoles.deleteByMemberIdAndRoleAndProjectIdIsNull(7L, MemberRole.ROLE_ADMIN) }
        }

        @Test
        fun lastAdminRemoveRejected() {
            val target = member(id = 7L, name = "dev2", passwordHash = "h")
            every { members.findByObjectId(target.objectId) } returns target
            every { memberRoles.findByMemberIdAndRoleAndProjectIdIsNull(7L, MemberRole.ROLE_ADMIN) } returns MemberRole(id = 9L, memberId = 7L, role = MemberRole.ROLE_ADMIN)
            every { adminGuard.countWorkspaceAdmins() } returns 1
            assertFailsWith<ConflictException> { service.setAdmin(target.objectId, false) }
        }
    }
}
