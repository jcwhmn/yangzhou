package yangzhou.api.github

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import yangzhou.persistence.Item
import yangzhou.persistence.ItemGitRef
import yangzhou.persistence.ItemActivity
import yangzhou.persistence.Project
import yangzhou.persistence.repository.ItemActivityRepository
import yangzhou.persistence.repository.ItemGitRefRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ProjectRepoRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.ProjectWorkflowRuleRepository
import yangzhou.persistence.repository.StatusRepository
import yangzhou.persistence.repository.WorkspaceRepository
import java.time.Instant

/**
 * V6-S2 轮询同步:GitHub 分支/PR → item_git_ref 关联 + 按项目映射自动移列。
 * 幂等锚点 = item_git_ref 的 uk(repo,kind,ref);防呆:只前进/final 封口/WIP 超限跳过留痕。
 */
@Service
class GithubSyncService(
    private val workspaces: WorkspaceRepository,
    private val projectRepos: ProjectRepoRepository,
    private val projects: ProjectRepository,
    private val items: ItemRepository,
    private val statuses: StatusRepository,
    private val rules: ProjectWorkflowRuleRepository,
    private val gitRefs: ItemGitRefRepository,
    private val activityRepo: ItemActivityRepository,
    private val gateway: GithubGateway,
) {
    companion object {
        private val log = LoggerFactory.getLogger(GithubSyncService::class.java)

        /** 分支/PR head 内嵌 item 键(Jira 同款):`KEY-123-slug`;手工分支 `cwjiang/KEY-123-x` 同样命中。 */
        private val ITEM_KEY = Regex("([A-Z][A-Z0-9]+)-(\\d+)")
        private const val KIND_BRANCH = "branch"
        private const val KIND_PR = "pr"
        private const val ACT_GITHUB = "github_status_changed"
    }

    /** 每轮同步:无 PAT / 无挂载仓库静默跳过;单仓库失败只 WARN,不炸下一轮。 */
    fun syncAll() {
        val ws = workspaces.findAll().firstOrNull() ?: return
        val token = ws.githubToken ?: return
        val mounts = projectRepos.findAll().toList()
        if (mounts.isEmpty()) return
        for (mount in mounts) {
            val project = projects.findById(mount.projectId).orElse(null) ?: continue
            try {
                syncRepo(project, mount.repo, token)
            } catch (e: GithubApiException) {
                if (e.status == 401) log.warn("GitHub 认证失败(401),请检查 workspace PAT:{}", mount.repo)
                else log.warn("GitHub API 失败({}):{}", e.status, mount.repo)
            } catch (e: Exception) {
                log.warn("GitHub 同步失败,下轮重试:{}:{}", mount.repo, e.message)
            }
        }
    }

    private fun syncRepo(project: Project, repo: String, token: String) {
        for (name in gateway.listBranches(repo, token)) {
            val m = ITEM_KEY.find(name) ?: continue
            if (m.groupValues[1] != project.key) continue
            val item = items.findByProjectIdAndNumber(project.id!!, m.groupValues[2].toInt()) ?: continue
            if (gitRefs.findByRepoAndKindAndRef(repo, KIND_BRANCH, name) != null) continue
            gitRefs.save(ItemGitRef(itemId = item.id!!, kind = KIND_BRANCH, repo = repo, ref = name))
            applyEvent(project, item.id!!, EVENT_BRANCH_CREATED, "branch $name")
        }

        for (pr in gateway.listPullRequests(repo, token)) {
            val m = ITEM_KEY.find(pr.headRef) ?: continue
            if (m.groupValues[1] != project.key) continue
            val item = items.findByProjectIdAndNumber(project.id!!, m.groupValues[2].toInt()) ?: continue
            val existing = gitRefs.findByRepoAndKindAndRef(repo, KIND_PR, pr.number.toString())
            when {
                existing == null -> {
                    gitRefs.save(
                        ItemGitRef(itemId = item.id!!, kind = KIND_PR, repo = repo, ref = pr.number.toString(), url = pr.url, state = pr.state),
                    )
                    applyEvent(project, item.id!!, eventForState(pr.state), "pr #${pr.number} ${pr.url.orEmpty()}")
                }
                existing.state != pr.state -> {
                    gitRefs.save(existing.copy(state = pr.state, updatedAt = Instant.now()))
                    applyEvent(project, item.id!!, eventForState(pr.state), "pr #${pr.number} ${pr.url.orEmpty()}")
                }
            }
        }
    }

    private fun eventForState(state: String) = when (state) {
        "open" -> EVENT_PR_OPENED
        "merged" -> EVENT_PR_MERGED
        else -> EVENT_PR_CLOSED_UNMERGED
    }

    /** 防呆:未配映射忽略;当前 final 封口;只按 position 前进;WIP 超限跳过并留痕。S3 建分支后也走此管道。 */
    fun applyEvent(project: Project, itemId: Long, event: String, detail: String) {
        val projectId = project.id ?: return
        val rule = rules.findByProjectIdAndEventType(projectId, event) ?: return
        val target = statuses.findByProjectIdAndObjectId(projectId, rule.statusObjectId) ?: return
        val item = items.findById(itemId).orElse(null) ?: return
        val current = statuses.findByProjectIdOrderByPosition(projectId)
            .firstOrNull { it.objectId == item.statusObjectId }
        if (current?.isFinal == true) return
        if (current != null && target.position <= current.position) return
        val limit = target.wipLimit
        if (limit != null && limit > 0 &&
            items.findByProjectIdAndStatusObjectId(projectId, target.objectId).size >= limit
        ) {
            logActivity(itemId, current?.name, "WIP 满跳过:${target.name}($event $detail)")
            return
        }
        items.save(item.copy(statusObjectId = target.objectId))
        logActivity(itemId, current?.name, "${target.name}($event $detail)")
    }

    private fun logActivity(itemId: Long, oldValue: String?, newValue: String?, kind: String = ACT_GITHUB) {
        activityRepo.save(ItemActivity(itemId = itemId, kind = kind, oldValue = oldValue, newValue = newValue))
    }
}
