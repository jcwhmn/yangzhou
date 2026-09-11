package yangzhou.api.github

import jakarta.validation.constraints.NotBlank
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.support.BadRequestException
import yangzhou.api.support.ConflictException
import yangzhou.api.support.NotFoundException
import yangzhou.api.workspace.WorkspaceService
import yangzhou.persistence.ProjectRepo
import yangzhou.persistence.ProjectWorkflowRule
import yangzhou.persistence.repository.ProjectRepoRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.ProjectWorkflowRuleRepository
import yangzhou.persistence.repository.StatusRepository
import yangzhou.persistence.repository.WorkspaceRepository
import java.time.Instant
import java.util.UUID

/** GitHub 事件类型(V6 四槽;扩展=加枚举值+CHECK)。 */
const val EVENT_BRANCH_CREATED = "branch_created"
const val EVENT_PR_OPENED = "pr_opened"
const val EVENT_PR_MERGED = "pr_merged"
const val EVENT_PR_CLOSED_UNMERGED = "pr_closed_unmerged"
val GITHUB_EVENT_TYPES = listOf(EVENT_BRANCH_CREATED, EVENT_PR_OPENED, EVENT_PR_MERGED, EVENT_PR_CLOSED_UNMERGED)

/**
 * V6-S1 配置面:workspace PAT / 项目仓库挂载 / 事件→状态映射。
 * PAT 永不回显,只回尾 4 位(spec 安全规则)。
 */
@Service
class GithubConfigService(
    private val workspaces: WorkspaceRepository,
    private val projects: ProjectRepository,
    private val repos: ProjectRepoRepository,
    private val rules: ProjectWorkflowRuleRepository,
    private val statuses: StatusRepository,
    private val workspaceService: WorkspaceService,
) {

    // ---------- workspace PAT ----------

    data class TokenResponse(val configured: Boolean, val tokenHint: String?)

    @Transactional
    fun setToken(token: String): TokenResponse {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) throw BadRequestException("token 不能为空")
        val ws = workspaceService.required()
        workspaces.save(ws.copy(githubToken = trimmed, updatedAt = Instant.now()))
        return TokenResponse(configured = true, tokenHint = hint(trimmed))
    }

    @Transactional
    fun clearToken() {
        val ws = workspaceService.required()
        workspaces.save(ws.copy(githubToken = null, updatedAt = Instant.now()))
    }

    private fun hint(token: String) = "…" + token.takeLast(4)

    // ---------- 项目仓库 ----------

    data class RepoDto(val repoId: UUID, val repo: String)

    fun listRepos(projectKey: String): List<RepoDto> =
        requireProject(projectKey).let { p ->
            repos.findByProjectId(p.id!!).map { RepoDto(it.objectId, it.repo) }
        }

    @Transactional
    fun addRepo(projectKey: String, repo: String): RepoDto {
        val project = requireProject(projectKey)
        val trimmed = repo.trim()
        if (!Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(trimmed)) {
            throw BadRequestException("repo 格式应为 owner/name:$trimmed")
        }
        if (repos.existsByProjectIdAndRepo(project.id!!, trimmed)) {
            throw ConflictException("仓库已挂载:$trimmed")
        }
        val saved = repos.save(ProjectRepo(projectId = project.id!!, repo = trimmed))
        return RepoDto(saved.objectId, saved.repo)
    }

    @Transactional
    fun deleteRepo(projectKey: String, repoId: UUID) {
        val project = requireProject(projectKey)
        val repo = repos.findByObjectId(repoId) ?: throw NotFoundException("仓库挂载不存在")
        if (repo.projectId != project.id) throw NotFoundException("仓库挂载不存在")
        repos.delete(repo)
    }

    // ---------- 事件→状态映射 ----------

    data class RuleDto(val eventType: String, val statusId: UUID?, val statusName: String?)

    fun listRules(projectKey: String): List<RuleDto> {
        val project = requireProject(projectKey)
        val byEvent = rules.findByProjectId(project.id!!).associateBy { it.eventType }
        val statusNames = statuses.findByProjectIdOrderByPosition(project.id!!)
            .associate { it.objectId to it.name }
        return GITHUB_EVENT_TYPES.map { event ->
            byEvent[event]?.let {
                RuleDto(event, it.statusObjectId, statusNames[it.statusObjectId])
            } ?: RuleDto(event, null, null)
        }
    }

    @Transactional
    fun replaceRules(projectKey: String, input: List<RuleInput>): List<RuleDto> {
        val project = requireProject(projectKey)
        val validStatuses = statuses.findByProjectIdOrderByPosition(project.id!!).associateBy { it.objectId }
        input.forEach { rule ->
            if (rule.eventType !in GITHUB_EVENT_TYPES) {
                throw BadRequestException("未知事件类型:${rule.eventType}")
            }
            if (rule.statusId == null) {
                throw BadRequestException("规则 ${rule.eventType} 缺少 statusId(清空槽位请从列表中去掉该规则)")
            }
            if (rule.statusId !in validStatuses) {
                throw BadRequestException("状态不属于本项目:${rule.statusId}")
            }
        }
        rules.deleteByProjectId(project.id!!)
        input.forEach { rule ->
            rules.save(
                ProjectWorkflowRule(projectId = project.id!!, eventType = rule.eventType, statusObjectId = rule.statusId!!),
            )
        }
        return listRules(projectKey)
    }

    private fun requireProject(key: String) =
        projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")
}

data class SetTokenRequest(
    @field:NotBlank val token: String,
)

data class AddRepoRequest(
    @field:NotBlank val repo: String,
)

data class RuleInput(
    val eventType: String,
    val statusId: UUID?,
)

data class ReplaceRulesRequest(
    val rules: List<RuleInput>,
)
