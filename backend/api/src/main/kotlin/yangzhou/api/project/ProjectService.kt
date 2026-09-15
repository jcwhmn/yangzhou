package yangzhou.api.project

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.support.ConflictException
import yangzhou.api.support.NotFoundException
import yangzhou.api.workspace.WorkspaceService
import yangzhou.persistence.Project
import yangzhou.persistence.Status
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.StatusRepository
import java.time.LocalDate
import java.util.UUID

/** Project = 编号(key-N)+ workflow + 视图容器(ADR-0001)。 */
@Service
class ProjectService(
    private val projects: ProjectRepository,
    private val statuses: StatusRepository,
    private val items: yangzhou.persistence.repository.ItemRepository,
    private val workspaceService: WorkspaceService,
) {

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
        // 默认 workflow:To Do / Development / QA / Done(final)——模板系统的最小预置
        defaultStatuses(project.id!!).forEach { statuses.save(it) }
        return get(key)
    }

    fun list(): List<ProjectDto> =
        projects.findAll().map { it.key }.mapNotNull { runCatching { get(it) }.getOrNull() }

    fun get(key: String): ProjectDto {
        val project = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")
        val statusDtos = statuses.findByProjectIdOrderByPosition(project.id!!).map {
            StatusDto(it.objectId, it.name, it.icon, it.isStart, it.isFinal, it.position)
        }
        return ProjectDto(project.objectId, project.key, project.name, project.archivedAt != null, project.feasSignal, statusDtos)
    }

    /** V8-S2 甘特数据:全量 item 按树序(父先子后,number 次序);日期可空;overdue 同看板语义。 */
    fun gantt(key: String): GanttDto {
        val project = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")
        val projectId = project.id ?: error("no id")
        val all = items.findByProjectIdOrderByNumber(projectId)
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
