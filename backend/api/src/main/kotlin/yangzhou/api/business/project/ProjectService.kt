package yangzhou.api.business.project

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.api.guard.LeaderGuard
import yangzhou.api.business.workspace.WorkspaceService
import yangzhou.api.business.github.VcsEvent
import yangzhou.persistence.Project
import yangzhou.persistence.ProjectWorkflowRule
import yangzhou.persistence.Status
import yangzhou.persistence.repository.ProjectDeletionRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.ProjectWorkflowRuleRepository
import yangzhou.persistence.repository.StatusRepository
import java.time.LocalDate
import java.util.UUID

/** Project = 编号(key-N)+ workflow + 视图容器(ADR-0001)。 */
@Service
class ProjectService(
    private val leaderGuard: LeaderGuard,
    private val projects: ProjectRepository,
    private val statuses: StatusRepository,
    private val workflowRules: ProjectWorkflowRuleRepository,
    private val items: yangzhou.persistence.repository.ItemRepository,
    private val workspaceService: WorkspaceService,
    private val deletions: ProjectDeletionRepository,
    private val members: yangzhou.api.business.member.MemberService,
    private val projectMembers: yangzhou.persistence.repository.ProjectMemberRepository,
    private val groupTypes: yangzhou.persistence.repository.ItemGroupTypeRepository,
) {

    private val log = LoggerFactory.getLogger(ProjectService::class.java)

    data class StatusDto(val statusId: UUID, val name: String, val icon: String?, val isStart: Boolean, val isFinal: Boolean, val isInProgress: Boolean, val position: Int)
    data class ProjectDto(
        val projectId: UUID,
        val key: String,
        val name: String,
        val archived: Boolean,
        val feasSignal: String?,
        val statuses: List<StatusDto>,
    )

    @Transactional
    fun create(key: String, name: String): ProjectDto {
        if (projects.existsByKey(key)) throw ConflictException("项目 key 已存在:$key")
        val workspaceObjectId = workspaceService.required().objectId!!
        val project = projects.save(Project(workspaceObjectId = workspaceObjectId, key = key, name = name))
        // 默认 workflow:To Do / In Progress / In Review / QA / Done(final)——模板系统的最小预置(V7 5 列)
        val saved = defaultStatuses(project.objectId!!).map { statuses.save(it) }
        seedDefaultRules(project.objectId!!, saved)
        // 0047:创建者自动入池(池=合法指派范围,保证创建者至少能给自己指派)
        // 0047+R1(spec 0081):创建者自动入池,且为项目 leader
        projectMembers.save(
            yangzhou.persistence.ProjectMember(
                projectObjectId = project.objectId!!, memberObjectId = members.current().objectId!!, role = yangzhou.persistence.ProjectMember.ROLE_LEADER,
            ),
        )
        // 0056:内建 group 类型种子(sprint/release);既有项目由 V35 迁移种子
        listOf("sprint" to "Sprint", "release" to "Release").forEach { (k, n) ->
            groupTypes.save(yangzhou.persistence.ItemGroupType(projectObjectId = project.objectId!!, typeKey = k, name = n, builtin = true))
        }
        return get(key)
    }

    fun list(): List<ProjectDto> =
        projects.findAll().map { it.key }.mapNotNull { key ->
            runCatching { get(key) }
                .onFailure { log.warn("项目组装失败,列表中跳过 key={}", key, it) }
                .getOrNull()
        }

    fun get(key: String): ProjectDto {
        val project = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")
        val statusDtos = statuses.findByProjectObjectIdOrderByPosition(project.objectId!!).map {
            StatusDto(it.objectId, it.name, it.icon, it.isStart, it.isFinal, it.isInProgress, it.position)
        }
        return ProjectDto(project.objectId, project.key, project.name, project.archivedAt != null, project.feasSignal, statusDtos)
    }

    /** YPJ-60:删除预览——将随项目物理删除的数据量(只读,不动库)。 */
    fun deletionPreview(key: String): DeletionPreviewDto {
        val project = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")
        val pid = project.objectId ?: error("no id")
        val r = deletions.deletionPreview(pid)
        return DeletionPreviewDto(
            key = project.key,
            name = project.name,
            itemCount = r.itemCount,
            groupCount = r.groupCount,
            milestoneCount = r.milestoneCount,
            viewCount = r.viewCount,
            statusCount = r.statusCount,
            memberCount = r.memberCount,
        )
    }

    data class DeletionPreviewDto(
        val key: String,
        val name: String,
        val itemCount: Long,
        val groupCount: Long,
        val milestoneCount: Long,
        val viewCount: Long,
        val statusCount: Long,
        val memberCount: Long,
    )

    /** YPJ-60:物理删除项目及全部从属数据(含回收站软删 item);FK 依赖序见 spec 0046。 */
    @Transactional
    fun delete(key: String) {
        leaderGuard.requireLeader(key)
        val project = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")
        val pid = project.objectId ?: error("no id")
        deletions.purgeRequirements(pid)
        deletions.purgeItemGroupMembers(pid)
        // 子表(评论/工时/动态/状态历史/依赖/清单/git_ref/通知/群成员历史)随 item 级联;软删 item 一并物理删
        deletions.purgeItems(pid)
        // 先于 milestone:release/sprint 同表,item_group.milestone_id restrict
        deletions.purgeItemGroups(pid)
        deletions.purgeItemGroupTypes(pid)
        deletions.purgeMilestones(pid)
        deletions.purgeStatuses(pid) // transition/workflow_rule 级联
        deletions.purgeProjectViews(pid)
        deletions.clearLastProjectKey(key)
        deletions.deleteProject(pid) // repo/rule/favorite/project_member 级联
    }

    /** V8-S2 甘特数据:全量 item 按树序(父先子后,number 次序);日期可空;overdue 同看板语义。 */
    fun gantt(key: String): GanttDto {
        val project = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")
        val projectId = project.objectId ?: error("no id")
        val all = items.findByProjectObjectIdAndDeletedAtIsNullOrderByNumber(projectId)
        val byParent = all.groupBy { it.parentObjectId }
        val statusById = statuses.findByProjectObjectIdOrderByPosition(projectId).associateBy { it.objectId }
        val today = LocalDate.now()

        fun depthOf(item: yangzhou.persistence.Item): Int {
            var depth = 0
            var cursor = item.parentObjectId
            while (cursor != null) {
                depth++
                cursor = all.firstOrNull { it.objectId == cursor }?.parentObjectId
            }
            return depth
        }

        fun walk(parent: UUID?): List<GanttRow> =
            (byParent[parent] ?: emptyList()).sortedBy { it.number }.flatMap { item ->
                val status = statusById[item.statusObjectId]
                val row = GanttRow(
                    itemId = item.objectId,
                    parentItemId = item.parentObjectId,
                    number = "${project.key}-${item.number}",
                    title = item.title,
                    startDate = item.startDate?.toString(),
                    dueDate = item.dueDate?.toString(),
                    statusName = status?.name ?: "?",
                    overdue = item.dueDate?.let { it < today } == true && status?.isFinal != true,
                    depth = depthOf(item),
                )
                listOf(row) + walk(item.objectId)
            }

        return GanttDto(rows = walk(null))
    }

    data class GanttRow(
        val itemId: UUID,
        val parentItemId: UUID?,
        val number: String,
        val title: String,
        val startDate: String?,
        val dueDate: String?,
        val statusName: String,
        val overdue: Boolean,
        val depth: Int,
    )

    data class GanttDto(val rows: List<GanttRow>)

    /** YPJ-40 方案 A:默认规则按语义推导(branch→首个非 start、pr_open→次个非 start(无则不种=人管)、pr_merged→首个 final、unmerged 恒不种);标准 V7 五列下与旧按名映射结果一致。 */
    private fun seedDefaultRules(projectId: UUID, saved: List<Status>) {
        defaultRuleSeeds(saved).forEach { (event, status) ->
            workflowRules.save(ProjectWorkflowRule(projectObjectId = projectId, eventType = event.wire, statusObjectId = status.objectId))
        }
    }

    private fun defaultStatuses(projectId: UUID) = listOf( // V7:5 列(评审环节天然有位置);存量项目不动
        Status(projectObjectId = projectId, name = "To Do", isStart = true, position = 0),
        Status(projectObjectId = projectId, name = "In Progress", isInProgress = true, position = 1), // YPJ-120:进行态属预置模板语义,V42 只负责存量回填
        Status(projectObjectId = projectId, name = "In Review", position = 2),
        Status(projectObjectId = projectId, name = "QA", position = 3),
        Status(projectObjectId = projectId, name = "Done", isFinal = true, position = 4),
    )

    companion object {
        /** YPJ-40 方案 A 的推导核心(纯函数,单测直测):branch→首个非 start、pr_open→次个非 start(无则不种=人管)、pr_merged→首个 final;缺失即不种,非标 workflow 开箱有定义。 */
        fun defaultRuleSeeds(statuses: List<Status>): List<Pair<VcsEvent, Status>> = buildList {
            val nonStart = statuses.filter { !it.isStart }
            nonStart.firstOrNull()?.let { add(VcsEvent.BRANCH_CREATED to it) }
            nonStart.getOrNull(1)?.let { add(VcsEvent.PR_OPENED to it) }
            statuses.firstOrNull { it.isFinal }?.let { add(VcsEvent.PR_MERGED to it) }
        }
    }
}

data class CreateProjectRequest(
    @field:NotBlank
    @field:Pattern(regexp = "^[A-Z][A-Z0-9]{1,9}$", message = "key 为 2–10 位大写字母/数字,如 CHE")
    val key: String,
    @field:NotBlank val name: String,
)
