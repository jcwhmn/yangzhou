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
import java.util.UUID

/** Project = 编号(key-N)+ workflow + 视图容器(ADR-0001)。 */
@Service
class ProjectService(
    private val projects: ProjectRepository,
    private val statuses: StatusRepository,
    private val workspaceService: WorkspaceService,
) {

    data class StatusDto(val statusId: UUID, val name: String, val icon: String?, val isFinal: Boolean, val position: Int)
    data class ProjectDto(
        val projectId: UUID,
        val key: String,
        val name: String,
        val archived: Boolean,
        val statuses: List<StatusDto>,
    )

    @Transactional
    fun create(key: String, name: String): ProjectDto {
        if (projects.existsByKey(key)) throw ConflictException("项目 key 已存在:$key")
        val workspaceId = workspaceService.required().id!!
        val project = projects.save(Project(workspaceId = workspaceId, key = key, name = name))
        // 默认 workflow:To Do / In Progress / Done(Done 终态)——模板系统的最小预置
        defaultStatuses(project.id!!).forEach { statuses.save(it) }
        return get(key)
    }

    fun list(): List<ProjectDto> =
        projects.findAll().map { it.key }.mapNotNull { runCatching { get(it) }.getOrNull() }

    fun get(key: String): ProjectDto {
        val project = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")
        val statusDtos = statuses.findByProjectIdOrderByPosition(project.id!!).map {
            StatusDto(it.objectId, it.name, it.icon, it.isFinal, it.position)
        }
        return ProjectDto(project.objectId, project.key, project.name, project.archivedAt != null, statusDtos)
    }

    private fun defaultStatuses(projectId: Long) = listOf(
        Status(projectId = projectId, name = "To Do", position = 0),
        Status(projectId = projectId, name = "In Progress", position = 1),
        Status(projectId = projectId, name = "Done", isFinal = true, position = 2),
    )
}

data class CreateProjectRequest(
    @field:NotBlank
    @field:Pattern(regexp = "^[A-Z][A-Z0-9]{1,9}$", message = "key 为 2–10 位大写字母/数字,如 CHE")
    val key: String,
    @field:NotBlank val name: String,
)
