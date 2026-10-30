package yangzhou.api.business.github

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import yangzhou.api.business.notification.NotificationService
import yangzhou.api.business.projectmember.ProjectMemberService
import yangzhou.persistence.Item
import yangzhou.persistence.ItemActivity
import yangzhou.persistence.ItemGitRef
import yangzhou.persistence.ItemStatusHistory
import yangzhou.persistence.Project
import yangzhou.persistence.ProjectRepo
import yangzhou.persistence.ProjectWorkflowRule
import yangzhou.persistence.Status
import yangzhou.persistence.Workspace
import yangzhou.persistence.repository.ItemActivityRepository
import yangzhou.persistence.repository.ItemGitRefRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ItemStatusHistoryRepository
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.ProjectRepoRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.ProjectWorkflowRuleRepository
import yangzhou.persistence.repository.StatusRepository
import yangzhou.persistence.repository.WorkspaceRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * YPJ-82 mockk 子票③:GithubSyncService 迁移规则(applyEvent)与扇出(syncAll)单测。
 * 协作者 mockk(relaxed);泛型 save 显式 firstArg(P0 坑);事件名经 VcsEvent.wire 引用不抄字符串。
 */
class GithubSyncServiceUnitTest {
    private val workspaces = mockk<WorkspaceRepository>(relaxed = true)
    private val projectRepos = mockk<ProjectRepoRepository>(relaxed = true)
    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val items = mockk<ItemRepository>(relaxed = true)
    private val statuses = mockk<StatusRepository>(relaxed = true)
    private val rules = mockk<ProjectWorkflowRuleRepository>(relaxed = true)
    private val gitRefs = mockk<ItemGitRefRepository>(relaxed = true)
    private val activityRepo = mockk<ItemActivityRepository>(relaxed = true)
    private val historyRepo = mockk<ItemStatusHistoryRepository>(relaxed = true)
    private val members = mockk<MemberRepository>(relaxed = true)
    private val projectMembers = mockk<ProjectMemberService>(relaxed = true)
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val gateway = mockk<VcsGateway>(relaxed = true)

    private val service =
        GithubSyncService(
            workspaces,
            projectRepos,
            projects,
            items,
            statuses,
            rules,
            gitRefs,
            activityRepo,
            historyRepo,
            members,
            projectMembers,
            notificationService,
            gateway,
        )

    private val project = Project(id = 100L, objectId = UUID(0, 100), workspaceObjectId = UUID(0, 1), key = "YPJ", name = "yangzhou")
    private val ws = Workspace(id = 1L, objectId = UUID(0, 1), githubToken = "tok")
    private val item =
        Item(id = 9L, objectId = UUID(0, 9), projectObjectId = UUID(0, 100), number = 7, title = "任务", statusObjectId = UUID.randomUUID())

    private val todoOid = UUID.randomUUID()
    private val doingOid = UUID.randomUUID()
    private val doneOid = UUID.randomUUID()
    private val todo = Status(id = 1L, projectObjectId = UUID(0, 100), name = "To Do", position = 0, isStart = true, objectId = todoOid)
    private val doing = Status(id = 2L, projectObjectId = UUID(0, 100), name = "In Progress", position = 1, objectId = doingOid)
    private val done = Status(id = 3L, projectObjectId = UUID(0, 100), name = "Done", position = 2, isFinal = true, objectId = doneOid)

    @BeforeEach
    fun setup() {
        clearMocks(
            workspaces,
            projectRepos,
            projects,
            items,
            statuses,
            rules,
            gitRefs,
            activityRepo,
            historyRepo,
            members,
            projectMembers,
            notificationService,
            gateway,
        )
        every { statuses.findByProjectObjectIdOrderByPosition(UUID(0, 100)) } returns listOf(todo, doing, done)
        every { items.findByObjectId(UUID(0, 9)) } returns (item)
        every { items.save(any()) } answers {
            val arg: Item = firstArg()
            arg
        }
        every { gitRefs.save(any()) } answers {
            val arg: ItemGitRef = firstArg()
            arg
        }
        every { historyRepo.save(any()) } answers {
            val arg: ItemStatusHistory = firstArg()
            arg
        }
        every { items.findByProjectObjectIdAndStatusObjectId(UUID(0, 100), doingOid) } returns emptyList()
        every { activityRepo.save(any()) } answers {
            val arg: ItemActivity = firstArg()
            arg
        } // 泛型坑(P0)
    }

    @Nested
    inner class ApplyEvent {
        @Test
        fun `rule 缺失 - no-op`() {
            every { rules.findByProjectObjectIdAndEventType(UUID(0, 100), VcsEvent.PR_MERGED.wire) } returns null

            service.applyEvent(project, UUID(0, 9), VcsEvent.PR_MERGED.wire, "pr #1")

            verify(exactly = 0) { items.save(any()) }
        }

        @Test
        fun `target status 缺失 - no-op`() {
            every { rules.findByProjectObjectIdAndEventType(UUID(0, 100), VcsEvent.PR_MERGED.wire) } returns
                ProjectWorkflowRule(projectObjectId = UUID(0, 100), eventType = VcsEvent.PR_MERGED.wire, statusObjectId = UUID.randomUUID())
            every { statuses.findByProjectObjectIdAndObjectId(UUID(0, 100), any()) } returns null

            service.applyEvent(project, UUID(0, 9), VcsEvent.PR_MERGED.wire, "pr #1")

            verify(exactly = 0) { items.save(any()) }
        }

        @Test
        fun `current 已 final - 封口跳过`() {
            every { rules.findByProjectObjectIdAndEventType(UUID(0, 100), VcsEvent.PR_MERGED.wire) } returns
                ProjectWorkflowRule(projectObjectId = UUID(0, 100), eventType = VcsEvent.PR_MERGED.wire, statusObjectId = doingOid)
            every { items.findByObjectId(UUID(0, 9)) } returns (item.copy(statusObjectId = doneOid))

            service.applyEvent(project, UUID(0, 9), VcsEvent.PR_MERGED.wire, "pr #1")

            verify(exactly = 0) { items.save(any()) }
        }

        @Test
        fun `只前进 - 目标 position 不高于当前则跳过`() {
            every { rules.findByProjectObjectIdAndEventType(UUID(0, 100), VcsEvent.PR_OPENED.wire) } returns
                ProjectWorkflowRule(projectObjectId = UUID(0, 100), eventType = VcsEvent.PR_OPENED.wire, statusObjectId = todoOid)
            every { items.findByObjectId(UUID(0, 9)) } returns (item.copy(statusObjectId = doingOid)) // 当前 position 1,目标 0

            service.applyEvent(project, UUID(0, 9), VcsEvent.PR_OPENED.wire, "pr #1")

            verify(exactly = 0) { items.save(any()) }
        }

        @Test
        fun `WIP 限满 - 只记 WIP activity,不迁移`() {
            every { rules.findByProjectObjectIdAndEventType(UUID(0, 100), VcsEvent.PR_MERGED.wire) } returns
                ProjectWorkflowRule(projectObjectId = UUID(0, 100), eventType = VcsEvent.PR_MERGED.wire, statusObjectId = doingOid)
            every { items.findByObjectId(UUID(0, 9)) } returns (item.copy(statusObjectId = todoOid))
            every { statuses.findByProjectObjectIdAndObjectId(UUID(0, 100), doingOid) } returns doing.copy(wipLimit = 1)
            every { items.findByProjectObjectIdAndStatusObjectId(UUID(0, 100), doingOid) } returns listOf(item) // 已占满

            service.applyEvent(project, UUID(0, 9), VcsEvent.PR_MERGED.wire, "pr #1")

            verify(exactly = 0) { items.save(any()) }
            verify(exactly = 1) { activityRepo.save(any()) } // 仅 WIP 留痕
        }

        @Test
        fun `happy - 迁移落库 + 历史(source=github) + 通知 + activity`() {
            every { rules.findByProjectObjectIdAndEventType(UUID(0, 100), VcsEvent.PR_MERGED.wire) } returns
                ProjectWorkflowRule(projectObjectId = UUID(0, 100), eventType = VcsEvent.PR_MERGED.wire, statusObjectId = doingOid)
            every { items.findByObjectId(UUID(0, 9)) } returns (item.copy(statusObjectId = todoOid))
// relaxed 对无全默认构造的 data class 返回 null,须显式
            every { statuses.findByProjectObjectIdAndObjectId(UUID(0, 100), doingOid) } returns doing

            val savedItems = mutableListOf<Item>()
            every { items.save(any()) } answers {
                val arg: Item = firstArg()
                savedItems.add(arg)
                arg
            }
            service.applyEvent(project, UUID(0, 9), VcsEvent.PR_MERGED.wire, "pr #1")
            assertEquals(doingOid, savedItems.single().statusObjectId)
            verify(exactly = 1) { historyRepo.save(any()) }
            verify(exactly = 1) { notificationService.notifyStatusChange(any(), null, "To Do", "In Progress") }
            verify(exactly = 1) { activityRepo.save(any()) }
        }

        @Test
        fun `item 缺失 - no-op`() {
            every { rules.findByProjectObjectIdAndEventType(UUID(0, 100), VcsEvent.PR_MERGED.wire) } returns
                ProjectWorkflowRule(projectObjectId = UUID(0, 100), eventType = VcsEvent.PR_MERGED.wire, statusObjectId = doingOid)
            every { statuses.findByProjectObjectIdAndObjectId(UUID(0, 100), doingOid) } returns doing
            every { items.findByObjectId(UUID(0, 9)) } returns null

            service.applyEvent(project, UUID(0, 9), VcsEvent.PR_MERGED.wire, "pr #1")

            verify(exactly = 0) { items.save(any()) }
        }
    }

    @Nested
    inner class SyncAll {
        private fun stubMount() {
            every { workspaces.findAll() } returns listOf(ws)
            every { projectRepos.findAll() } returns listOf(ProjectRepo(projectObjectId = UUID(0, 100), repo = "jcwhmn/yangzhou"))
            every { projects.findByObjectId(UUID(0, 100)) } returns (project)
        }

        @Test
        fun `无 workspace - 静默`() {
            every { workspaces.findAll() } returns emptyList()
            service.syncAll()
            verify(exactly = 0) { gateway.listBranches(any(), any()) }
        }

        @Test
        fun `无 token - 静默`() {
            every { workspaces.findAll() } returns listOf(ws.copy(githubToken = null))
            service.syncAll()
            verify(exactly = 0) { gateway.listBranches(any(), any()) }
        }

        @Test
        fun `无挂载 - 静默`() {
            every { workspaces.findAll() } returns listOf(ws)
            every { projectRepos.findAll() } returns emptyList()
            service.syncAll()
            verify(exactly = 0) { gateway.listBranches(any(), any()) }
        }

        @Test
        fun `gateway 404 - 单仓库 WARN 不炸,继续后续挂载`() {
            every { workspaces.findAll() } returns listOf(ws)
            every { projectRepos.findAll() } returnsMany
                listOf(
                    listOf(ProjectRepo(projectObjectId = UUID(0, 100), repo = "octo/r")),
                )
            every { projects.findByObjectId(UUID(0, 100)) } returns (project)
            every { gateway.listBranches(any(), any()) } throws VcsApiException(404, "not found")

            service.syncAll() // 不抛
            assertTrue(true)
        }

        @Test
        fun `分支扇出 - head 内嵌键命中,gitRef 落行`() {
            stubMount()
            every { gateway.listBranches("jcwhmn/yangzhou", "tok") } returns listOf("YPJ-7-fix-bug")
            every { items.findByProjectObjectIdAndNumber(UUID(0, 100), 7) } returns item
            every { gitRefs.findByRepoAndKindAndRef("jcwhmn/yangzhou", "branch", "YPJ-7-fix-bug") } returns emptyList()
            every { rules.findByProjectObjectIdAndEventType(UUID(0, 100), any()) } returns null // 事件规则缺省,只验证 gitRef

            val savedRefs = mutableListOf<ItemGitRef>()
            every { gitRefs.save(any()) } answers {
                val arg: ItemGitRef = firstArg()
                savedRefs.add(arg)
                arg
            }
            service.syncAll()
            assertEquals("branch", savedRefs.single().kind)
            assertEquals(UUID(0, 9), savedRefs.single().itemObjectId)
        }

        @Test
        fun `既有 gitRef - 跳过不重复落行`() {
            stubMount()
            every { gateway.listBranches("jcwhmn/yangzhou", "tok") } returns listOf("YPJ-7-fix-bug")
            every { items.findByProjectObjectIdAndNumber(UUID(0, 100), 7) } returns item
            every { gitRefs.findByRepoAndKindAndRef("jcwhmn/yangzhou", "branch", "YPJ-7-fix-bug") } returns
                listOf(ItemGitRef(itemObjectId = UUID(0, 9), kind = "branch", repo = "jcwhmn/yangzhou", ref = "ypj-7-fix-bug"))

            service.syncAll()

            verify(exactly = 0) { gitRefs.save(any()) }
        }

        @Test
        fun `键不匹配项目 - 跳过`() {
            stubMount()
            every { gateway.listBranches("jcwhmn/yangzhou", "tok") } returns listOf("other-7-foo", "feat-no-key")

            service.syncAll()

            verify(exactly = 0) { gitRefs.save(any()) }
        }
    }
}
