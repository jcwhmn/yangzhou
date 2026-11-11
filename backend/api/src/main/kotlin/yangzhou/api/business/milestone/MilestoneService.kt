package yangzhou.api.business.milestone

import jakarta.validation.constraints.NotBlank
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.business.item.isDueSoon
import yangzhou.api.business.item.isOverdue
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Milestone
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.MilestoneRepository
import yangzhou.persistence.repository.ProjectRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CreateMilestoneRequest(
    @field:NotBlank val name: String?,
    val targetDate: String? = null,
    val description: String? = null,
    /** 成员 ID(uuid 文本);空串 = 无负责人。 */
    val owner: String? = null,
)

data class UpdateMilestoneRequest(
    val name: String? = null,
    val targetDate: String? = null,
    val status: String? = null,
    /** null = 不动,空串 = 清空(同 targetDate 语义)。 */
    val description: String? = null,
    /** null = 不动,空串 = 清空;成员 ID(uuid 文本)。 */
    val owner: String? = null,
)

@Service
class MilestoneService(
    private val projects: ProjectRepository,
    private val milestones: MilestoneRepository,
    private val members: MemberRepository,
    private val clock: Clock,
) {
    data class MilestoneDto(
        val milestoneId: UUID,
        val name: String,
        val description: String? = null,
        val owner: UUID? = null,
        val status: String,
        val targetDate: String? = null,
        val overdue: Boolean = false,
        val dueSoon: Boolean = false,
    )

    private val transitions =
        mapOf(
            "planned" to setOf("in_progress", "cancelled"),
            "in_progress" to setOf("completed", "cancelled"),
            "completed" to emptySet(),
            "cancelled" to emptySet(),
        )

    private val statusValues = transitions.keys

    private val terminalStatuses = setOf("completed", "cancelled")

    private fun project(key: String) = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")

    private fun find(
        key: String,
        milestoneId: UUID,
    ): Milestone {
        val p = project(key)
        return milestones.findByProjectObjectIdAndObjectId(p.objectId!!, milestoneId) ?: throw NotFoundException("milestone 不存在")
    }

    private fun today() = LocalDate.now(clock)

    /** 徽标纯函数与 item 同口径(item 包 isOverdue/isDueSoon,spec 0116 §3.1)。 */
    private fun Milestone.toDto(today: LocalDate) = MilestoneDto(
        milestoneId = objectId,
        name = name,
        description = description,
        owner = ownerObjectId,
        status = status,
        targetDate = targetDate?.toString(),
        overdue = isOverdue(targetDate, today, status in terminalStatuses),
        // 纯函数 isDueSoon 无终态参数(与 item 同口径);终态不显黄标由服务层门控(spec 0116 §四.2)
        dueSoon = status !in terminalStatuses && isDueSoon(targetDate, today),
    )

    /** owner 文本 → 校验存在的成员 objectId;格式坏 400,成员不存在 404。 */
    private fun resolveOwner(text: String): UUID {
        val oid =
            try {
                UUID.fromString(text)
            } catch (_: Exception) {
                throw BadRequestException("owner 应为成员 ID(uuid)")
            }
        members.findByObjectId(oid) ?: throw NotFoundException("成员不存在")
        return oid
    }

    /** 排序:in_progress 优先,completed/cancelled 末尾(历史);余按 target_date asc nulls last,再按创建序。 */
    fun list(key: String): List<MilestoneDto> {
        val rank = mapOf("in_progress" to 0, "planned" to 1, "completed" to 2, "cancelled" to 3)
        val today = today()
        return milestones
            .findByProjectObjectIdOrderByCreatedAt(project(key).objectId!!)
            .sortedWith(
                compareBy<Milestone> { rank.getValue(it.status) }
                    .thenBy { it.targetDate ?: LocalDate.MAX }
                    .thenBy { it.objectId },
            ).map { it.toDto(today) }
    }

    @Transactional
    fun create(
        key: String,
        request: CreateMilestoneRequest,
    ): MilestoneDto {
        val p = project(key)
        val name = request.name?.trim().takeUnless { it.isNullOrEmpty() } ?: throw BadRequestException("name 必填")
        val saved =
            milestones.save(
                Milestone(
                    projectObjectId = p.objectId!!,
                    name = name,
                    targetDate = parseDate(request.targetDate),
                    description = request.description?.takeIf { it.isNotEmpty() },
                    ownerObjectId = request.owner?.takeIf { it.isNotEmpty() }?.let { resolveOwner(it) },
                ),
            )
        return saved.toDto(today())
    }

    @Transactional
    fun update(
        key: String,
        milestoneId: UUID,
        request: UpdateMilestoneRequest,
    ): MilestoneDto {
        val m = find(key, milestoneId)
        if (request.status != null && request.status !in statusValues) {
            throw BadRequestException("status 只能是 planned/in_progress/completed/cancelled")
        }
        val newStatus = request.status ?: m.status
        if (request.status != null && request.status != m.status && request.status !in transitions.getValue(m.status)) {
            throw ConflictException("状态不可从 ${m.status} 迁移到 $newStatus(终态不可变)")
        }
        val name = request.name?.trim()
        if (m.status in terminalStatuses) {
            val editing =
                (name != null && name != m.name) ||
                    request.targetDate != null ||
                    request.description != null ||
                    request.owner != null
            if (editing) throw ConflictException("终态 milestone 不可编辑(历史保留)")
        }
        if (request.status == "in_progress" && m.status != "in_progress") {
            val currents = milestones.findByProjectObjectIdAndStatus(m.projectObjectId, "in_progress")
            if (currents.any { it.objectId != m.objectId }) throw ConflictException("已有进行中的 milestone(当前节点至多一个,先完成或取消)")
        }
        val saved =
            milestones.save(
                m.copy(
                    name = name ?: m.name,
                    status = newStatus,
                    targetDate =
                    when {
                        request.targetDate == null -> m.targetDate
                        request.targetDate.isEmpty() -> null
                        else -> parseDate(request.targetDate)
                    },
                    description =
                    when {
                        request.description == null -> m.description
                        request.description.isEmpty() -> null
                        else -> request.description
                    },
                    ownerObjectId =
                    when {
                        request.owner == null -> m.ownerObjectId
                        request.owner.isEmpty() -> null
                        else -> resolveOwner(request.owner)
                    },
                    updatedAt = Instant.now(clock),
                ),
            )
        return saved.toDto(today())
    }

    @Transactional
    fun delete(
        key: String,
        milestoneId: UUID,
    ) {
        val m = find(key, milestoneId)
        // spec 0116 §6 #1(2026-11-12 拍板):终态守卫——UI 置灰之外 API 层也拦,防脚本误删历史
        if (m.status in terminalStatuses) throw ConflictException("终态 milestone 不可删除(历史保留)")
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
