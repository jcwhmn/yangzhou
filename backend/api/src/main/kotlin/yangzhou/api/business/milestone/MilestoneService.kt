package yangzhou.api.business.milestone

import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import org.springframework.stereotype.Service
import yangzhou.persistence.Milestone
import yangzhou.persistence.repository.MilestoneRepository
import yangzhou.persistence.repository.ProjectRepository
import jakarta.validation.constraints.NotBlank
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CreateMilestoneRequest(
    @field:NotBlank val name: String?,
    val targetDate: String? = null,
)

data class UpdateMilestoneRequest(
    val name: String? = null,
    val targetDate: String? = null,
    val status: String? = null,
)

@Service
class MilestoneService(
    private val projects: ProjectRepository,
    private val milestones: MilestoneRepository,
) {

    data class MilestoneDto(
        val milestoneId: UUID,
        val name: String,
        val status: String,
        val targetDate: String? = null,
    )

    private val transitions = mapOf(
        "planned" to setOf("in_progress", "cancelled"),
        "in_progress" to setOf("completed", "cancelled"),
        "completed" to emptySet(),
        "cancelled" to emptySet(),
    )

    private val statusValues = transitions.keys

    private fun project(key: String) =
        projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")

    private fun find(key: String, milestoneId: UUID): Milestone {
        val p = project(key)
        return milestones.findByProjectObjectIdAndObjectId(p.objectId!!, milestoneId) ?: throw NotFoundException("milestone 不存在")
    }

    private fun Milestone.toDto() = MilestoneDto(
        milestoneId = objectId,
        name = name,
        status = status,
        targetDate = targetDate?.toString(),
    )

    /** 排序:in_progress 优先,completed/cancelled 末尾(历史);余按 target_date asc nulls last,再按创建序。 */
    fun list(key: String): List<MilestoneDto> {
        val rank = mapOf("in_progress" to 0, "planned" to 1, "completed" to 2, "cancelled" to 3)
        return milestones.findByProjectObjectIdOrderByCreatedAt(project(key).objectId!!)
            .sortedWith(
                compareBy<Milestone> { rank.getValue(it.status) }
                    .thenBy { it.targetDate ?: LocalDate.MAX }
                    .thenBy { it.objectId },
            )
            .map { it.toDto() }
    }

    @Transactional
    fun create(key: String, request: CreateMilestoneRequest): MilestoneDto {
        val p = project(key)
        val name = request.name?.trim().takeUnless { it.isNullOrEmpty() } ?: throw BadRequestException("name 必填")
        val saved = milestones.save(
            Milestone(
                projectObjectId = p.objectId!!,
                name = name,
                targetDate = parseDate(request.targetDate),
            ),
        )
        return saved.toDto()
    }

    @Transactional
    fun update(key: String, milestoneId: UUID, request: UpdateMilestoneRequest): MilestoneDto {
        val m = find(key, milestoneId)
        if (request.status != null && request.status !in statusValues) {
            throw BadRequestException("status 只能是 planned/in_progress/completed/cancelled")
        }
        val newStatus = request.status ?: m.status
        if (request.status != null && request.status != m.status && request.status !in transitions.getValue(m.status)) {
            throw ConflictException("状态不可从 ${m.status} 迁移到 $newStatus(终态不可变)")
        }
        val name = request.name?.trim()
        if (m.status in setOf("completed", "cancelled") && (name != null && name != m.name || request.targetDate != null)) {
            throw ConflictException("终态 milestone 不可编辑(历史保留)")
        }
        if (request.status == "in_progress" && m.status != "in_progress") {
            val currents = milestones.findByProjectObjectIdAndStatus(m.projectObjectId, "in_progress")
            if (currents.any { it.objectId != m.objectId }) throw ConflictException("已有进行中的 milestone(当前节点至多一个,先完成或取消)")
        }
        val saved = milestones.save(
            m.copy(
                name = name ?: m.name,
                status = newStatus,
                targetDate = when {
                    request.targetDate == null -> m.targetDate
                    request.targetDate.isEmpty() -> null
                    else -> parseDate(request.targetDate)
                },
                updatedAt = Instant.now(),
            ),
        )
        return saved.toDto()
    }

    @Transactional
    fun delete(key: String, milestoneId: UUID) {
        val m = find(key, milestoneId)
        milestones.delete(m)
    }

    private fun parseDate(text: String?): LocalDate? {
        if (text.isNullOrBlank()) return null
        return try {
            LocalDate.parse(text)
        } catch (_: Exception) {
            throw BadRequestException("targetDate 格式应为 yyyy-MM-dd")
        }
    }
}
