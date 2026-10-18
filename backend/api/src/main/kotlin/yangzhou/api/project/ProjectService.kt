package yangzhou.api.project

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.support.ConflictException
import yangzhou.api.support.NotFoundException
import yangzhou.api.workspace.WorkspaceService
import yangzhou.api.github.EVENT_BRANCH_CREATED
import yangzhou.api.github.EVENT_PR_MERGED
import yangzhou.api.github.EVENT_PR_OPENED
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
    private val projects: ProjectRepository,
    private val statuses: StatusRepository,
    private val workflowRules: ProjectWorkflowRuleRepository,
    private val items: yangzhou.persistence.repository.ItemRepository,
    private val workspaceService: WorkspaceService,
    private val deletions: ProjectDeletionRepository,
    private val members: yangzhou.api.member.MemberService,
    private val projectMembers: yangzhou.persistence.repository.ProjectMemberRepository,
    private val groupTypes: yangzhou.persistence.repository.ItemGroupTypeRepository,
) {

    private val log = LoggerFactory.getLogger(ProjectService::class.java)

    data class StatusDto(val statusId: UUID, val name: String, val icon: String?, val isStart: Boolean, val isFinal: Boolean, val position: Int)
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
        val workspaceId = workspaceService.required().id!!
        val project = projects.save(Project(workspaceId = workspaceId, key = key, name = name))
        // 默认 workflow:To Do / In Progress / In Review / QA / Done(final)——模板系统的最小预置(V7 5 列)
        val saved = defaultStatuses(project.id!!).map { statuses.save(it) }
        seedDefaultRules(project.id!!, saved)
        // 0047:创建者自动入池(池=合法指派范围,保证创建者至少能给自己指派)
        projectMembers.save(yangzhou.persistence.ProjectMember(projectId = project.id!!, memberId = members.current().id!!))
        // 0056:内建 group 类型种子(sprint/release);既有项目由 V35 迁移种子
        listOf("sprint" to "Sprint", "release" to "Release").forEach { (k, n) ->
            groupTypes.save(yangzhou.persistence.ItemGroupType(projectId = project.id!!, typeKey = k, name = n, builtin = true))
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
        val statusDtos = statuses.findByProjectIdOrderByPosition(project.id!!).map {
            StatusDto(it.objectId, it.name, it.icon, it.isStart, it.isFinal, it.position)
        }
        return ProjectDto(project.objectId, project.key, project.name, project.archivedAt != null, project.feasSignal, statusDtos)
    }

    /** YPJ-60:删除预览——将随项目物理删除的数据量(只读,不动库)。 */
    fun deletionPreview(key: String): DeletionPreviewDto {
        val project = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")
        val pid = project.id ?: error("no id")
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
        val project = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")
        val pid = project.id ?: error("no id")
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
        val projectId = project.id ?: error("no id")
        val all = items.findByProjectIdAndDeletedAtIsNullOrderByNumber(projectId)
        val byParent = all.groupBy { it.parentObjectId }
        val statusById = statuses.findByProjectIdOrderByPosition(projectId).associateBy { it.objectId }
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

    /** YPJ-36:默认规则 3 槽(branch→In Progress、pr_open→In Review、pr_merged→Done);closed_unmerged 留空=人管(票面裁决)。按名字映射,非标 workflow 缺列自然少种。 */
    private fun seedDefaultRules(projectId: Long, saved: List<Status>) {
        val byName = saved.associateBy { it.name }
        mapOf(EVENT_BRANCH_CREATED to "In Progress", EVENT_PR_OPENED to "In Review", EVENT_PR_MERGED to "Done")
            .forEach { (event, statusName) ->
                byName[statusName]?.let {
                    workflowRules.save(ProjectWorkflowRule(projectId = projectId, eventType = event, statusObjectId = it.objectId))
                }
            }
    }

    private fun defaultStatuses(projectId: Long) = listOf( // V7:5 列(评审环节天然有位置);存量项目不动
        Status(projectId = projectId, name = "To Do", isStart = true, position = 0),
        Status(projectId = projectId, name = "In Progress", position = 1),
        Status(projectId = projectId, name = "In Review", position = 2),
        Status(projectId = projectId, name = "QA", position = 3),
        Status(projectId = projectId, name = "Done", isFinal = true, position = 4),
    )
}

data class CreateProjectRequest(
    @field:NotBlank
    @field:Pattern(regexp = "^[A-Z][A-Z0-9]{1,9}$", message = "key 为 2–10 位大写字母/数字,如 CHE")
    val key: String,
    @field:NotBlank val name: String,
)
