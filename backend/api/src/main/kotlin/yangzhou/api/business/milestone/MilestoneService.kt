package yangzhou.api.business.milestone

import jakarta.validation.constraints.NotBlank
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.business.item.isDueSoon
import yangzhou.api.business.item.isOverdue
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Item
import yangzhou.persistence.Milestone
import yangzhou.persistence.MilestoneItem
import yangzhou.persistence.StatusType
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.MilestoneItemRepository
import yangzhou.persistence.repository.MilestoneRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.StatusRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
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
    private val milestoneItems: MilestoneItemRepository,
    private val items: ItemRepository,
    private val statuses: StatusRepository,
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
        /** 北极星卡(US-M10):剩/超 N 天(负=已超);targetDate 空为 null。与徽标同一 Clock 口径。 */
        val daysToTarget: Int? = null,
        /** 关口就绪度(US-M9):零 gate 站双 null(不显示)。 */
        val gateDone: Int? = null,
        val gateTotal: Int? = null,
    )

    /** gate 依据行(US-M7):number = 项目 key-item number;final = workflow FINAL(与看板/逾期同源)。 */
    data class GateItemDto(
        val itemId: UUID,
        val number: String,
        val title: String,
        val status: String,
        val final: Boolean,
    )

    data class SetMilestoneItemsRequest(val itemIds: List<UUID> = emptyList())

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
        daysToTarget = targetDate?.let { ChronoUnit.DAYS.between(today, it).toInt() },
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
            ).map { m ->
                val rows = gateRows(m.objectId)
                m.toDto(today).copy(
                    gateDone = rows.takeIf { it.isNotEmpty() }?.count { it.final },
                    gateTotal = rows.takeIf { it.isNotEmpty() }?.size,
                )
            }
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
        // 关口硬门禁(US-M8):迁往 completed 时 gate 有未终态 → 409;零 gate/全终态/软删放行
        if (request.status == "completed" && m.status != "completed") {
            val notReady = gateRows(m.objectId).filter { !it.final }
            if (notReady.isNotEmpty()) {
                throw ConflictException("关口未就绪:" + notReady.joinToString(", ") { "$key-${it.item.number} ${it.item.title}" })
            }
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
        // 接棒级联(YPJ-176/spec 0121):完成当前后同事务自动开始排序第一的 planned;cancelled 不级联,无 planned 静默
        if (newStatus == "completed" && m.status == "in_progress") {
            nextPlannedOf(milestones.findByProjectObjectIdAndStatus(m.projectObjectId, "planned"))?.let {
                milestones.save(it.copy(status = "in_progress", updatedAt = Instant.now(clock)))
            }
        }
        // 就绪度随 DTO 回传(与 list() 同口径;完成后前端可就地刷新徽标)
        val rows = gateRows(m.objectId)
        return saved
            .toDto(today())
            .copy(
                gateDone = rows.takeIf { it.isNotEmpty() }?.count { it.final },
                gateTotal = rows.takeIf { it.isNotEmpty() }?.size,
            )
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

    private data class GateRow(val item: Item, val statusName: String, val final: Boolean)

    /** 挂接依据(US-M7):软删 item 滤除(回收站语义,与看板同源);按 item number 升序。 */
    private fun gateRows(milestoneObjectId: UUID): List<GateRow> {
        val rows = milestoneItems.findByMilestoneObjectId(milestoneObjectId)
        if (rows.isEmpty()) return emptyList()
        val itemMap =
            items.findAllByObjectIdIn(rows.map { it.itemObjectId }.toSet())
                .filter { it.deletedAt == null }
                .associateBy { it.objectId }
        val statusMap =
            statuses.findAllByObjectIdIn(itemMap.values.map { it.statusObjectId }.toSet())
                .associateBy { it.objectId }
        return rows.mapNotNull { itemMap[it.itemObjectId] }
            .sortedBy { it.number }
            .map {
                val s = statusMap[it.statusObjectId]
                GateRow(it, s?.name ?: "?", s?.statusType == StatusType.FINAL)
            }
    }

    /** 依据清单(US-M7)。 */
    fun gateItems(
        key: String,
        milestoneId: UUID,
    ): List<GateItemDto> {
        find(key, milestoneId)
        return gateRows(milestoneId).map { GateItemDto(it.item.objectId, "$key-${it.item.number}", it.item.title, it.statusName, it.final) }
    }

    /** 全量替换挂接(US-M7);item 存在且属本项目校验,返回替换后的清单。 */
    @Transactional
    fun setGateItems(
        key: String,
        milestoneId: UUID,
        request: SetMilestoneItemsRequest,
    ): List<GateItemDto> {
        val p = project(key)
        val m = milestones.findByProjectObjectIdAndObjectId(p.objectId!!, milestoneId) ?: throw NotFoundException("milestone 不存在")
        val ids = request.itemIds.distinct()
        if (ids.isNotEmpty()) {
            val found = items.findAllByObjectIdIn(ids.toSet()).filter { it.deletedAt == null }
            if (found.size != ids.size) throw BadRequestException("item 不存在或已删除")
            if (found.any { it.projectObjectId != p.objectId }) throw BadRequestException("item 不属于该项目")
        }
        milestoneItems.deleteByMilestoneObjectId(m.objectId)
        ids.forEach { milestoneItems.save(MilestoneItem(milestoneObjectId = m.objectId, itemObjectId = it)) }
        return gateItems(key, milestoneId)
    }
}

/** 接棒选择(YPJ-176/spec 0121):targetDate 最早的 planned(nulls last),并列取创建早者;不新增序列字段。 */
internal fun nextPlannedOf(planned: List<Milestone>): Milestone? = planned.minWithOrNull(compareBy({ it.targetDate ?: LocalDate.MAX }, { it.createdAt }))
