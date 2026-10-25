package yangzhou.api.business.github

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import yangzhou.persistence.Item
import yangzhou.persistence.ItemGitRef
import yangzhou.persistence.ItemActivity
import yangzhou.persistence.ItemStatusHistory
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
 * 幂等锚点 = item_git_ref 的每票 uk(item,repo,kind,ref)(YPJ-39 多对多);防呆:只前进/final 封口/WIP 超限跳过留痕。
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
    private val historyRepo: yangzhou.persistence.repository.ItemStatusHistoryRepository,
    private val members: yangzhou.persistence.repository.MemberRepository,
    private val projectMembers: yangzhou.api.business.projectmember.ProjectMemberService,
    private val notificationService: yangzhou.api.business.notification.NotificationService,
    private val gateway: VcsGateway,
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
            } catch (e: VcsApiException) {
                if (e.status == 401) log.warn("GitHub 认证失败(401),请检查 workspace PAT:{}", mount.repo)
                else log.warn("GitHub API 失败({}):{}", e.status, mount.repo)
            } catch (e: Exception) {
                log.warn("GitHub 同步失败,下轮重试:{}:{}", mount.repo, e.message)
            }
        }
    }

    internal fun syncRepo(project: Project, repo: String, token: String) {
        for (name in gateway.listBranches(repo, token)) {
            val item = headItem(project, name) ?: continue
            if (gitRefs.findByRepoAndKindAndRef(repo, KIND_BRANCH, name).isNotEmpty()) continue
            gitRefs.save(ItemGitRef(itemId = item.id!!, kind = KIND_BRANCH, repo = repo, ref = name))
            applyEvent(project, item.id!!, VcsEvent.BRANCH_CREATED.wire, "branch $name")
        }

        for (pr in gateway.listPullRequests(repo, token)) {
            val existingRows = gitRefs.findByRepoAndKindAndRef(repo, KIND_PR, pr.number.toString())
            when {
                existingRows.isNotEmpty() -> {
                    // YPJ-39:PR 行按票各持一行,状态更新扇出到全部行
                    for (row in existingRows) {
                        if (row.state == pr.state) continue
                        gitRefs.save(row.copy(state = pr.state, updatedAt = Instant.now()))
                        applyEvent(project, row.itemId, eventForState(pr.state), "pr #${pr.number} ${pr.url.orEmpty()}")
                    }
                }
                else -> {
                    // YPJ-39:PR 扇出——head 分支挂多票则各落一行;分支未入册回退 head 内嵌键(既有行为)
                    val targets = gitRefs.findByRepoAndKindAndRef(repo, KIND_BRANCH, pr.headRef)
                        .mapNotNull { items.findById(it.itemId).orElse(null) }
                        .distinctBy { it.id }
                        .ifEmpty { listOfNotNull(headItem(project, pr.headRef)) }
                    for (item in targets) {
                        gitRefs.save(
                            ItemGitRef(itemId = item.id!!, kind = KIND_PR, repo = repo, ref = pr.number.toString(), url = pr.url, state = pr.state),
                        )
                        // V10-S5:PR author → github_username 映射成员 → 池内自动 assign(未配置池/找不到则留痕跳过)
                        val wsId = projects.findById(project.id).orElse(null)?.workspaceId ?: 0L
                        if (item.assigneeObjectId == null && pr.authorLogin != null) {
                            val authorMember = members.findByWorkspaceIdAndGithubUsername(wsId, pr.authorLogin)
                            if (authorMember != null && authorMember.id != null) {
                                val authorId = authorMember.id
                                runCatching { projectMembers.assertAssignable(project.id!!, authorId!!) }
                                    .onFailure { log.warn("PR 自动指派跳过(作者不在指派池?),事件照常应用: {}", it.message) }
                                    .onSuccess {
                                        items.save(item.copy(assigneeObjectId = authorMember.objectId))
                                    }
                            }
                        }
                        applyEvent(project, item.id!!, eventForState(pr.state), "pr #${pr.number} ${pr.url.orEmpty()}")
                    }
                }
            }
        }
    }

    /** head/分支名内嵌 item 键解析(YPJ-39 自 PR 循环抽出复用):跨项目/解析不出返回 null。 */
    private fun headItem(project: Project, name: String): Item? {
        val m = ITEM_KEY.find(name) ?: return null
        if (m.groupValues[1] != project.key) return null
        return items.findByProjectIdAndNumber(project.id!!, m.groupValues[2].toInt())
    }

    private fun eventForState(state: String) = when (state) {
        "open" -> VcsEvent.PR_OPENED.wire
        "merged" -> VcsEvent.PR_MERGED.wire
        else -> VcsEvent.PR_CLOSED_UNMERGED.wire
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
        val saved = items.save(item.copy(statusObjectId = target.objectId))
        logActivity(itemId, current?.name, "${target.name}($event $detail)")
        historyRepo.save(ItemStatusHistory(itemId = itemId, fromStatusId = current?.id, toStatusId = target.id!!, source = "github"))
        notificationService.notifyStatusChange(saved, null, current?.name, target.name)
    }

    private fun logActivity(itemId: Long, oldValue: String?, newValue: String?, kind: String = ACT_GITHUB) {
        activityRepo.save(ItemActivity(itemId = itemId, kind = kind, oldValue = oldValue, newValue = newValue))
    }
}
