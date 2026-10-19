package yangzhou.api.github

import java.time.Instant

/**
 * GitHub 出网唯一 seam(V6-S2);S2 测试用 fake 替换。
 * token 由调用方传入(PAT 存 workspace,永不进日志)。
 */
interface VcsGateway {
    fun listBranches(repo: String, token: String): List<String>
    fun listPullRequests(repo: String, token: String): List<RepoPr>

    /** 按 head 分支定位 PR(open/merged/closed 全量);无 → null。默认实现遍历列表,真实现用平台 head 过滤(不受首页窗口限制)。 */
    fun findPrByHead(repo: String, headRef: String, token: String): RepoPr? =
        listPullRequests(repo, token).firstOrNull { it.headRef == headRef }

    /** 从 fromBranch 建新分支;已存在 → GithubApiException(422)。 */
    fun createBranch(repo: String, branch: String, fromBranch: String, token: String)

    /** sha/分支上的提交(第一页,新→旧,since 过滤分支创建前);ref 已删 → 空表(真实现归一)。 */
    fun listCommits(repo: String, sha: String, since: Instant?, token: String): List<RepoCommit>

    /** PR 上的提交(第一页,新→旧;API 不支持 since,由调用方按 date 过滤);PR 不存在 → 空表。 */
    fun listPullRequestCommits(repo: String, number: Int, token: String): List<RepoCommit>
}

/** state ∈ open|merged|closed(GitHub 的 merged=closed+merged,在此归一)。 */
data class RepoPr(
    val number: Int,
    val headRef: String,
    val state: String,
    val url: String? = null,
    val authorLogin: String? = null,
)

/** message 只留首行(列表语义);author 优先 login,无则 commit.author.name。 */
data class RepoCommit(
    val sha: String,
    val message: String,
    val author: String? = null,
    val date: String? = null,
    val url: String? = null,
)

class VcsApiException(val status: Int, message: String) : RuntimeException(message)

/** 平台无关事件集(wire 值 = workflow_rules.event 存量字符串,不可改);未来平台事件超集在此扩。 */
enum class VcsEvent(val wire: String) {
    BRANCH_CREATED("branch_created"),
    PR_OPENED("pr_opened"),
    PR_MERGED("pr_merged"),
    PR_CLOSED_UNMERGED("pr_closed_unmerged"),
}
