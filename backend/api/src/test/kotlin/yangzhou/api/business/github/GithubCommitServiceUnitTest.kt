package yangzhou.api.business.github

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import yangzhou.api.business.workspace.WorkspaceService
import yangzhou.api.error.BadRequestException
import yangzhou.persistence.Item
import yangzhou.persistence.ItemCommitSnapshot
import yangzhou.persistence.ItemGitRef
import yangzhou.persistence.ProjectRepo
import yangzhou.persistence.Workspace
import yangzhou.persistence.repository.ItemCommitSnapshotRepository
import yangzhou.persistence.repository.ItemGitRefRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ProjectRepoRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals

/** YPJ-184(spec 0125):读时缓存——commitsForItem 成功读到非空 commits 即 upsert 快照;失败/空不写。 */
class GithubCommitServiceUnitTest {
    private val items = mockk<ItemRepository>()
    private val projectRepos = mockk<ProjectRepoRepository>()
    private val gitRefs = mockk<ItemGitRefRepository>()
    private val snapshots = mockk<ItemCommitSnapshotRepository>(relaxed = true)
    private val workspaceService = mockk<WorkspaceService>()
    private val gateway = mockk<VcsGateway>()
    private val objectMapper = ObjectMapper()
    private val clock = Clock.fixed(Instant.parse("2026-11-14T12:00:00Z"), ZoneOffset.UTC)

    private val service =
        GithubCommitService(items, projectRepos, gitRefs, snapshots, workspaceService, gateway, objectMapper, clock)

    private val itemObjectId = UUID(0, 7)
    private val item =
        Item(id = 1L, objectId = itemObjectId, projectObjectId = UUID(0, 100), number = 7, title = "x", statusObjectId = UUID(0, 200))
    private val gitRef =
        ItemGitRef(
            id = 2L,
            objectId = UUID(0, 8),
            itemObjectId = itemObjectId,
            kind = "branch",
            repo = "octo/r",
            ref = "YPJ-7-x",
            createdAt = Instant.parse("2026-01-01T00:00:00Z"), // since 过滤基准:早于 commits 日期
        )
    private val commits =
        listOf(
            RepoCommit("c1", "首次", "me", "2026-11-10T09:00:00Z", null),
            RepoCommit("c2", "末次", "me", "2026-11-13T18:00:00Z", null),
        )

    @BeforeEach
    fun stub() {
        every { items.findByObjectId(itemObjectId) } returns item
        every { projectRepos.findByProjectObjectId(item.projectObjectId) } returns
            listOf(ProjectRepo(id = 3L, objectId = UUID(0, 9), projectObjectId = item.projectObjectId, repo = "octo/r"))
        every { workspaceService.required() } returns Workspace(id = 4L, objectId = UUID(0, 10), githubToken = "tok")
        every { gitRefs.findByItemObjectId(itemObjectId) } returns listOf(gitRef)
        // 台账 #16:relaxed 也须显式 stub nullable 返回
        every { snapshots.findByItemObjectIdAndRepoAndRef(any(), any(), any()) } returns null
        every { snapshots.save(any()) } answers { firstArg() }
    }

    @Test
    fun `成功读取非空 commits 后 upsert 快照 captured_by read`() {
        every { gateway.listCommits(any(), any(), any(), any()) } returns commits

        val out = service.commitsForItem(itemObjectId)

        assertEquals(1, out.size)
        assertEquals(2, out[0].commits.size)
        val saved = slot<ItemCommitSnapshot>()
        verify(exactly = 1) { snapshots.save(capture(saved)) }
        assertEquals("octo/r", saved.captured.repo)
        assertEquals("YPJ-7-x", saved.captured.ref)
        assertEquals("read", saved.captured.capturedBy)
        assertEquals("branch", saved.captured.via)
        assertEquals(2, saved.captured.commitCount)
        assertEquals(Instant.parse("2026-11-14T12:00:00Z"), saved.captured.capturedAt)
    }

    @Test
    fun `分支路失败不写快照——错误照旧可操作报错`() {
        every { gateway.listCommits(any(), any(), any(), any()) } throws VcsApiException(404, "not found")

        val err = kotlin.runCatching { service.commitsForItem(itemObjectId) }.exceptionOrNull()

        assertEquals(BadRequestException::class, err?.let { it::class })
        verify(exactly = 0) { snapshots.save(any()) }
    }
}
