package yangzhou.api.github

import org.springframework.stereotype.Service
import yangzhou.api.support.BadRequestException
import yangzhou.api.support.NotFoundException
import yangzhou.persistence.ItemGitRef
import yangzhou.persistence.repository.ItemGitRefRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ProjectRepoRepository
import yangzhou.api.workspace.WorkspaceService
import java.time.Instant
import java.util.UUID

/**
 * V16-2 按需拉取: item 关联分支的 remote 提交历史。
 * 只读直查 GitHub——不落库、不写活动、不依赖轮询开关;拉取失败给可操作报错。
 */
@Service
class GithubCommitService(
    private val items: ItemRepository,
    private val projectRepos: ProjectRepoRepository,
    private val gitRefs: ItemGitRefRepository,
    private val workspaceService: WorkspaceService,
    private val gateway: VcsGateway,
) {
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

    fun commitsForItem(itemId: UUID): List<RefCommitsDto> {
        val item = items.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val mounts = projectRepos.findByProjectId(item.projectId)
        if (mounts.isEmpty()) throw BadRequestException("项目未挂载 GitHub 仓库(设置 → GitHub)")
        val token = workspaceService.required().githubToken
            ?: throw BadRequestException("未配置 workspace GitHub PAT(设置 → GitHub)")

        val branchRefs = gitRefs.findByItemId(item.id!!).filter { it.kind == "branch" }
        return branchRefs.map { ref ->
            // 降级链(YPJ-69):分支仍在走原路;空表(分支已删)→ 按 head 分支名匹配 PR 回溯
            val commits = fetchBranchCommits(ref, token)
            if (commits.isNotEmpty()) {
                RefCommitsDto(repo = ref.repo, ref = ref.ref, commits = commits.map { CommitDto(it.sha, it.message, it.author, it.date, it.url) })
            } else {
                val via = fetchPrCommits(ref, token)
                RefCommitsDto(
                    repo = ref.repo, ref = ref.ref, viaPr = via != null, prNumber = via?.first,
                    commits = via?.second?.map { CommitDto(it.sha, it.message, it.author, it.date, it.url) } ?: emptyList(),
                )
            }
        }
    }

    private fun fetchBranchCommits(ref: ItemGitRef, token: String): List<RepoCommit> =
        runCatching { gateway.listCommits(ref.repo, ref.ref, ref.createdAt, token) }
            .getOrElse { e -> wrap(e) }
            // 响应侧兜底:GitHub since 按 commit date 过滤,边缘仍可能有漏网(date 缺失则保留,宁多勿漏)
            .filter { it.date == null || runCatching { Instant.parse(it.date) >= ref.createdAt }.getOrDefault(true) }

    /** 分支查空 → PR 回溯:返回 (PR 号, 过滤后 commits);未命中 PR 或全被 since 过滤 → null。 */
    private fun fetchPrCommits(ref: ItemGitRef, token: String): Pair<Int, List<RepoCommit>>? {
        val pr = runCatching { gateway.listPullRequests(ref.repo, token).firstOrNull { it.headRef == ref.ref } }
            .getOrElse { e -> wrap(e) } ?: return null
        val commits = runCatching { gateway.listPullRequestCommits(ref.repo, pr.number, token) }
            .getOrElse { e -> wrap(e) }
            // PR commits API 不支持 since,服务侧按 commit date 过滤(同兜底语义:date 缺失保留)
            .filter { it.date == null || runCatching { Instant.parse(it.date) >= ref.createdAt }.getOrDefault(true) }
        return if (commits.isEmpty()) null else pr.number to commits
    }

    private fun wrap(e: Throwable): Nothing {
        if (e is VcsApiException) throw BadRequestException("GitHub API 错误(${e.status}):先检查 PAT/仓库名")
        throw e
    }
}
