package yangzhou.api.business.github

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import yangzhou.api.business.workspace.WorkspaceService
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.ItemCommitSnapshot
import yangzhou.persistence.ItemGitRef
import yangzhou.persistence.repository.ItemCommitSnapshotRepository
import yangzhou.persistence.repository.ItemGitRefRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ProjectRepoRepository
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * V16-2 按需拉取: item 关联分支的 remote 提交历史。
 * 只读直查 GitHub——不落库、不写活动、不依赖轮询开关;拉取失败给可操作报错。
 * YPJ-184(spec 0125):成功读到 commits 时顺手 upsert 快照(读时缓存,数据自持);
 * 另供 ItemFinalizeService 在 item 进 FINAL 时主动快照(captured_by=finalize,终止时间锚)。
 */
@Service
class GithubCommitService(
    private val items: ItemRepository,
    private val projectRepos: ProjectRepoRepository,
    private val gitRefs: ItemGitRefRepository,
    private val snapshots: ItemCommitSnapshotRepository,
    private val workspaceService: WorkspaceService,
    private val gateway: VcsGateway,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(GithubCommitService::class.java)
    data class CommitDto(
        val sha: String,
        val message: String,
        val author: String?,
        val date: String?,
        val url: String?,
    )

    data class RefCommitsDto(
        val repo: String,
        val ref: String,
        val viaPr: Boolean = false,
        val prNumber: Int? = null,
        val commits: List<CommitDto>,
    )

    fun commitsForItem(itemObjectId: UUID): List<RefCommitsDto> {
        val item = items.findByObjectId(itemObjectId) ?: throw NotFoundException("item 不存在")
        val mounts = projectRepos.findByProjectObjectId(item.projectObjectId)
        if (mounts.isEmpty()) throw BadRequestException("项目未挂载 GitHub 仓库(设置 → GitHub)")
        val token =
            workspaceService.required().githubToken
                ?: throw BadRequestException("未配置 workspace GitHub PAT(设置 → GitHub)")

        val branchRefs = gitRefs.findByItemObjectId(item.objectId!!).filter { it.kind == "branch" }
        return branchRefs.map { ref ->
            val fetch = fetchRefCommits(ref, token)
            // 读时缓存(YPJ-184):成功读到非空 commits(branch 路或 PR 路皆可)即 upsert 快照;失败/空不写
            if (fetch.commits.isNotEmpty()) {
                upsertSnapshot(item.objectId!!, ref.repo, ref.ref, fetch.via, fetch.commits, capturedBy = "read")
            }
            RefCommitsDto(
                repo = ref.repo,
                ref = ref.ref,
                viaPr = fetch.prNumber != null,
                prNumber = fetch.prNumber,
                commits = fetch.commits.map { CommitDto(it.sha, it.message, it.author, it.date, it.url) },
            )
        }
    }

    /**
     * YPJ-184:快照 item 全部关联分支的 commits(best-effort,供 FINAL 钩子调用)。
     * 单 ref 失败不中断整轮;无挂载/无 PAT 直接放弃(不抛)。返回 (成功 ref 数, 失败 ref 数)。
     */
    fun snapshotForItem(
        itemObjectId: UUID,
        capturedBy: String,
    ): Pair<Int, Int> {
        val item = items.findByObjectId(itemObjectId) ?: return 0 to 0
        if (projectRepos.findByProjectObjectId(item.projectObjectId).isEmpty()) return 0 to 0
        val token = workspaceService.required().githubToken ?: return 0 to 0
        var ok = 0
        var failed = 0
        for (ref in gitRefs.findByItemObjectId(item.objectId!!).filter { it.kind == "branch" }) {
            try {
                val fetch = fetchRefCommits(ref, token)
                if (fetch.commits.isNotEmpty()) {
                    upsertSnapshot(item.objectId!!, ref.repo, ref.ref, fetch.via, fetch.commits, capturedBy)
                    ok++
                }
            } catch (e: Exception) {
                failed++
                log.warn("分支快照失败(跳过) item={} ref={} cause={}", itemObjectId, ref.ref, e.message)
            }
        }
        return ok to failed
    }

    /** 降级链(YPJ-69)统一封装:分支在走分支路;空(已删)→ 按 head 分支名回溯 PR。 */
    private fun fetchRefCommits(
        ref: ItemGitRef,
        token: String,
    ): RefFetch {
        val branch = fetchBranchCommits(ref, token)
        if (branch.isNotEmpty()) return RefFetch(via = "branch", prNumber = null, commits = branch)
        val pr = fetchPrCommits(ref, token)
        return RefFetch(via = if (pr != null) "pr" else "branch", prNumber = pr?.first, commits = pr?.second ?: emptyList())
    }

    private data class RefFetch(
        val via: String,
        val prNumber: Int?,
        val commits: List<RepoCommit>,
    )

    private fun upsertSnapshot(
        itemObjectId: UUID,
        repo: String,
        ref: String,
        via: String,
        commits: List<RepoCommit>,
        capturedBy: String,
    ) {
        val json =
            objectMapper.writeValueAsString(
                commits.map { mapOf("sha" to it.sha, "message" to it.message, "author" to it.author, "date" to it.date, "url" to it.url) },
            )
        val now = clock.instant()
        val existing = snapshots.findByItemObjectIdAndRepoAndRef(itemObjectId, repo, ref)
        val row =
            when {
                existing == null ->
                    ItemCommitSnapshot(
                        itemObjectId = itemObjectId,
                        repo = repo,
                        ref = ref,
                        via = via,
                        commitsJson = json,
                        commitCount = commits.size,
                        capturedAt = now,
                        capturedBy = capturedBy,
                    )
                existing.capturedBy == "finalize" && capturedBy == "read" ->
                    // FINAL 锚不可被读时缓存冲掉(spec 0125:单提交段的终止时间 = Done 时刻):
                    // 只刷新提交数据,保留 capturedAt/capturedBy=finalize
                    existing.copy(via = via, commitsJson = json, commitCount = commits.size, updatedAt = now)
                else ->
                    existing.copy(
                        via = via,
                        commitsJson = json,
                        commitCount = commits.size,
                        capturedAt = now,
                        capturedBy = capturedBy,
                        updatedAt = now,
                    )
            }
        snapshots.save(row)
    }

    private fun fetchBranchCommits(
        ref: ItemGitRef,
        token: String,
    ): List<RepoCommit> = runCatching { gateway.listCommits(ref.repo, ref.ref, ref.createdAt, token) }
        .getOrElse { e -> wrap(e) }
        // 响应侧兜底:GitHub since 按 commit date 过滤,边缘仍可能有漏网(date 缺失则保留,宁多勿漏)
        .filter { it.date == null || runCatching { Instant.parse(it.date) >= ref.createdAt }.getOrDefault(true) }

    /** 分支查空 → PR 回溯:返回 (PR 号, 过滤后 commits);未命中 PR 或全被 since 过滤 → null。 */
    private fun fetchPrCommits(
        ref: ItemGitRef,
        token: String,
    ): Pair<Int, List<RepoCommit>>? {
        val pr =
            runCatching { gateway.findPrByHead(ref.repo, ref.ref, token) }
                .getOrElse { e -> wrap(e) } ?: return null
        val commits =
            runCatching { gateway.listPullRequestCommits(ref.repo, pr.number, token) }
                .getOrElse { e -> wrap(e) }
                // PR commits API 不支持 since,服务侧按 commit date 过滤(同兜底语义:date 缺失保留)
                .filter { it.date == null || runCatching { Instant.parse(it.date) >= ref.createdAt }.getOrDefault(true) }
        return if (commits.isEmpty()) null else pr.number to commits
    }

    private fun wrap(e: Throwable): Nothing {
        if (e is VcsApiException) throw BadRequestException("GitHub API 错误(${e.status}):先检查 PAT/仓库名")
        // 网络层失败(超时/连接重置)原样抛会兆成 500 INTERNAL;翻译成可操作错误让用户重试(YPJ-52 实测)
        if (e is java.io.IOException) throw BadRequestException("GitHub 暂时不可达(网络错误),请稍后重试")
        throw e
    }
}
