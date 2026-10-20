package yangzhou.api.github

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.support.BadRequestException
import yangzhou.api.support.ConflictException
import yangzhou.api.support.NotFoundException
import yangzhou.api.workspace.WorkspaceService
import yangzhou.persistence.ItemActivity
import yangzhou.persistence.ItemGitRef
import yangzhou.persistence.repository.ItemActivityRepository
import yangzhou.persistence.repository.ItemGitRefRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ProjectRepoRepository
import yangzhou.persistence.repository.ProjectRepository
import java.util.UUID

/** V6-S3:yangzhou 发起建分支(spec:校验 → 建 ref → 落链接 → 留痕 → 走 branch_created 映射)。 */
@Service
class GithubBranchService(
    private val items: ItemRepository,
    private val projects: ProjectRepository,
    private val projectRepos: ProjectRepoRepository,
    private val gitRefs: ItemGitRefRepository,
    private val activityRepo: ItemActivityRepository,
    private val workspaceService: WorkspaceService,
    private val syncService: GithubSyncService,
    private val gateway: VcsGateway,
) {

    data class BranchDto(val repo: String, val branch: String, val url: String)

    @Transactional
    fun createBranch(itemId: UUID, repoId: UUID, baseBranch: String, name: String): BranchDto {
        val item = items.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val project = projects.findById(item.projectId).orElse(null) ?: throw NotFoundException("项目不存在")
        val mount = projectRepos.findByObjectId(repoId) ?: throw NotFoundException("仓库挂载不存在")
        if (mount.projectId != item.projectId) throw BadRequestException("仓库不属于该项目")

        val token = workspaceService.required().githubToken
            ?: throw BadRequestException("未配置 workspace GitHub PAT")
        val branch = requireValidBranch(name, "分支名")
        val base = requireValidBranch(baseBranch, "基线分支名")

        try {
            gateway.createBranch(mount.repo, branch, base, token)
        } catch (e: VcsApiException) {
            if (e.status == 422) throw ConflictException("分支已存在:$branch")
            if (e.status == 404) throw BadRequestException("基线分支不存在:$base")
            throw BadRequestException("GitHub API 错误(${e.status}):先检查 PAT/仓库名")
        }

        val url = "https://github.com/${mount.repo}/tree/$branch"
        gitRefs.save(ItemGitRef(itemId = item.id!!, kind = "branch", repo = mount.repo, ref = branch, url = url))
        activityRepo.save(
            ItemActivity(itemId = item.id!!, kind = "github_branch_created", newValue = "分支 $branch(${mount.repo})"),
        )
        syncService.applyEvent(project, item.id!!, VcsEvent.BRANCH_CREATED.wire, "branch $branch")
        return BranchDto(mount.repo, branch, url)
    }


    /** YPJ-39:既有分支补挂多票(纯库操作,不调 gateway;只收库内已有行)。 */
    @Transactional
    fun attach(itemId: UUID, repo: String, ref: String): BranchDto {
        val item = items.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        if (!projectRepos.existsByProjectIdAndRepo(item.projectId, repo)) {
            throw BadRequestException("仓库未挂载到该项目:$repo")
        }
        val branch = requireValidBranch(ref, "分支名")
        val rows = gitRefs.findByRepoAndKindAndRef(repo, "branch", branch)
        if (rows.isEmpty()) {
            if (gitRefs.findByRepoAndKindAndRef(repo, "pr", branch).isNotEmpty()) {
                throw ConflictException("PR 行不可补挂:$repo#$branch")
            }
            throw NotFoundException("分支未入册:$repo#$branch(只可补挂 yangzhou 已关联的分支)")
        }
        if (rows.any { it.itemId == item.id }) throw ConflictException("该票已挂此分支:$branch")
        val anchor = rows.first()
        val saved = gitRefs.save(
            ItemGitRef(itemId = item.id!!, kind = "branch", repo = repo, ref = branch, url = anchor.url, state = anchor.state, createdAt = anchor.createdAt),
        )
        // 审计会覆盖实体显式 createdAt,落库后校正为锚点行时刻(commits 的 since 过滤依赖它)
        gitRefs.updateCreatedAt(saved.id!!, anchor.createdAt)
        return BranchDto(repo, branch, anchor.url.orEmpty())
    }
    private fun requireValidBranch(raw: String, label: String): String {
        val n = raw.trim()
        if (n.isEmpty()) throw BadRequestException("$label 不能为空")
        if (!Regex("^[A-Za-z0-9._,\\-/]{1,200}$").matches(n) || n.contains("..") ||
            n.startsWith("/") || n.endsWith("/") || n.endsWith(".lock")
        ) {
            throw BadRequestException("$label 非法:$n")
        }
        return n
    }
}

data class CreateBranchRequest(
    @field:NotNull val repoId: UUID?,
    @field:NotBlank val baseBranch: String,
    @field:NotBlank val name: String,
)


data class AttachGitRefRequest(
    @field:NotBlank val repo: String,
    @field:NotBlank val ref: String,
)
