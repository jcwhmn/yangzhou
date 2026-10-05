package yangzhou.api.milestone

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import yangzhou.api.support.BadRequestException
import yangzhou.api.support.ConflictException
import yangzhou.api.support.NotFoundException
import org.springframework.stereotype.Service
import yangzhou.persistence.Milestone
import yangzhou.persistence.repository.MilestoneRepository
import yangzhou.persistence.repository.ProjectRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * V14 Milestone:项目时间轴目标节点(PRD §9–12,spec 0011)。非 ItemGroup,独立实体。
 * 生命周期 planned→in_progress→completed(可提前 cancelled);终态不可变(§11 历史保留);
 * 0..1 in_progress:服务层预检 409,DB 部分唯一索引 ux_milestone_project_current 兜底。
 */
@RestController
@RequestMapping("/api")
class MilestoneController(private val service: MilestoneService) {

    @GetMapping("/projects/{key}/milestones")
    fun list(@PathVariable key: String): List<MilestoneService.MilestoneDto> = service.list(key)

    @PostMapping("/projects/{key}/milestones")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable key: String,
        @Valid @RequestBody request: CreateMilestoneRequest,
    ): MilestoneService.MilestoneDto = service.create(key, request)

    @PatchMapping("/projects/{key}/milestones/{milestoneId}")
    fun update(
        @PathVariable key: String,
        @PathVariable milestoneId: UUID,
        @RequestBody request: UpdateMilestoneRequest,
    ): MilestoneService.MilestoneDto = service.update(key, milestoneId, request)

    @DeleteMapping("/projects/{key}/milestones/{milestoneId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable key: String, @PathVariable milestoneId: UUID) = service.delete(key, milestoneId)
}

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
        return milestones.findByProjectIdAndObjectId(p.id!!, milestoneId) ?: throw NotFoundException("milestone 不存在")
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
        return milestones.findByProjectIdOrderByCreatedAt(project(key).id!!)
            .sortedWith(
                compareBy<Milestone> { rank.getValue(it.status) }
                    .thenBy { it.targetDate ?: LocalDate.MAX }
                    .thenBy { it.id },
            )
            .map { it.toDto() }
    }

    @Transactional
    fun create(key: String, request: CreateMilestoneRequest): MilestoneDto {
        val p = project(key)
        val name = request.name?.trim().takeUnless { it.isNullOrEmpty() } ?: throw BadRequestException("name 必填")
        val saved = milestones.save(
            Milestone(
                projectId = p.id!!,
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
            val currents = milestones.findByProjectIdAndStatus(m.projectId, "in_progress")
            if (currents.any { it.id != m.id }) throw ConflictException("已有进行中的 milestone(当前节点至多一个,先完成或取消)")
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
