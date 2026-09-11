package yangzhou.api.github

/**
 * GitHub 出网唯一 seam(V6-S2);S2 测试用 fake 替换。
 * token 由调用方传入(PAT 存 workspace,永不进日志)。
 */
interface GithubGateway {
    fun listBranches(repo: String, token: String): List<String>
    fun listPullRequests(repo: String, token: String): List<GithubPr>

    /** 从 fromBranch 建新分支;已存在 → GithubApiException(422)。 */
    fun createBranch(repo: String, branch: String, fromBranch: String, token: String)
}

/** state ∈ open|merged|closed(GitHub 的 merged=closed+merged,在此归一)。 */
data class GithubPr(
    val number: Int,
    val headRef: String,
    val state: String,
    val url: String? = null,
)

class GithubApiException(val status: Int, message: String) : RuntimeException(message)
