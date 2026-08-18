package yangzhou.api.workflow

import jakarta.validation.constraints.NotBlank
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.support.ConflictException
import yangzhou.api.support.NotFoundException
import yangzhou.persistence.Status
import yangzhou.persistence.StatusTransition
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.StatusRepository
import yangzhou.persistence.repository.StatusTransitionRepository
import java.util.UUID

/**
 * Workflow = Project 上的数据(DOMAIN.md):{Status 集合 + 迁移表}。
 * 迁移表无行 = 自由迁移;有行 = 仅列出的 from→to 合法。
 */
@Service
class WorkflowService(
    private val projects: ProjectRepository,
    private val statuses: StatusRepository,
    private val transitions: StatusTransitionRepository,
) {

    data class StatusDto(
        val statusId: UUID,
        val name: String,
        val icon: String?,
        val isFinal: Boolean,
        val position: Int,
    )

    data class TransitionDto(val from: String, val to: String)

    // ---------- Status ----------

    @Transactional
    fun createStatus(projectKey: String, name: String, icon: String?, isFinal: Boolean, position: Int?): StatusDto {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        val projectId = project.id!!
        val existing = statuses.findByProjectIdOrderByPosition(projectId)
        if (existing.any { it.name == name }) throw ConflictException("状态名已存在:$name")
        val saved = statuses.save(
            Status(
                projectId = projectId,
                name = name,
                icon = icon,
                isFinal = isFinal,
                position = position ?: ((existing.maxOfOrNull { it.position } ?: -1) + 1),
            ),
        )
        return saved.toDto()
    }

    @Transactional
    fun updateStatus(statusId: UUID, name: String?, icon: String?, isFinal: Boolean?, position: Int?): StatusDto {
        val status = statuses.findByObjectId(statusId) ?: throw NotFoundException("状态不存在")
        try {
            val updated = statuses.save(
                status.copy(
                    name = name ?: status.name,
                    icon = icon ?: status.icon,
                    isFinal = isFinal ?: status.isFinal,
                    position = position ?: status.position,
                ),
            )
            return updated.toDto()
        } catch (_: DataIntegrityViolationException) {
            throw ConflictException("状态名已存在:$name")
        }
    }

    @Transactional
    fun deleteStatus(statusId: UUID) {
        val status = statuses.findByObjectId(statusId) ?: throw NotFoundException("状态不存在")
        try {
            statuses.delete(status) // 关联迁移行由 FK 级联清除
        } catch (_: DataIntegrityViolationException) {
            throw ConflictException("状态仍被 item 引用,先把它们迁到其他状态")
        }
    }

    // ---------- Transitions(整表替换) ----------

    fun listTransitions(projectKey: String): List<TransitionDto> {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        val projectId = project.id!!
        val names = statuses.findByProjectIdOrderByPosition(projectId).associate { it.id!! to it.name }
        return transitions.findByProjectId(projectId).map {
            TransitionDto(names[it.fromStatusId] ?: "?", names[it.toStatusId] ?: "?")
        }
    }

    @Transactional
    fun replaceTransitions(projectKey: String, list: List<TransitionDto>): List<TransitionDto> {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        val projectId = project.id!!
        val byName = statuses.findByProjectIdOrderByPosition(projectId).associateBy { it.name }
        list.forEach { t ->
            if (t.from !in byName || t.to !in byName) {
                throw yangzhou.api.support.BadRequestException("迁移引用了不存在的状态:${t.from}→${t.to}")
            }
        }
        transitions.deleteByProjectId(projectId)
        list.map { t ->
            transitions.save(
                StatusTransition(
                    projectId = projectId,
                    fromStatusId = byName.getValue(t.from).id!!,
                    toStatusId = byName.getValue(t.to).id!!,
                ),
            )
        }
        return listTransitions(projectKey)
    }

    /** Item 状态迁移校验:迁移表空 = 全放行;否则 from→to 必须在表内(from==to 恒放行)。 */
    fun assertTransitionAllowed(projectId: Long, fromStatusId: Long, toStatusId: Long) {
        if (fromStatusId == toStatusId) return
        val table = transitions.findByProjectId(projectId)
        if (table.isEmpty()) return // 自由迁移(V1 默认)
        if (table.none { it.fromStatusId == fromStatusId && it.toStatusId == toStatusId }) {
            throw ConflictException("工作流不允许此状态迁移")
        }
    }

    private fun Status.toDto() = StatusDto(objectId, name, icon, isFinal, position)
}

data class CreateStatusRequest(
    @field:NotBlank val name: String,
    val icon: String? = null,
    val isFinal: Boolean = false,
    val position: Int? = null,
)

data class UpdateStatusRequest(
    val name: String? = null,
    val icon: String? = null,
    val isFinal: Boolean? = null,
    val position: Int? = null,
)

data class ReplaceTransitionsRequest(val transitions: List<WorkflowService.TransitionDto> = emptyList())
