package yangzhou.api.business.sprint

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.business.item.ItemService
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.ItemGroup
import yangzhou.persistence.ItemGroupMember
import yangzhou.persistence.repository.ItemGroupMemberRepository
import yangzhou.persistence.repository.ItemGroupMemberHistoryRepository
import yangzhou.persistence.repository.ItemGroupRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.api.guard.LeaderGuard
import yangzhou.persistence.repository.ProjectRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Sprint:item 的时间盒分组。Phase 1 唯一 ItemGroup type('sprint');Milestone/Release 见 PRD §28 Phase 2/3。
 * 不变量:item 至多一个 planned/active membership(替换式写入);行指向 completed 后即历史,不可变。
 * 组不进匹配输入——Sprint 只影响展示与过滤,不参与可行性/忙闲聚合(领域规则,对齐规则 5)。
 */
@Service
class SprintService(
    private val leaderGuard: LeaderGuard,
    private val projects: ProjectRepository,
    private val groups: ItemGroupRepository,
    private val memberships: ItemGroupMemberRepository,
    private val itemRepo: ItemRepository,
    private val itemService: ItemService,
    private val memberHistory: ItemGroupMemberHistoryRepository,
    private val clock: Clock,
    private val groupTypes: yangzhou.persistence.repository.ItemGroupTypeRepository,
) {

    data class SprintDto(
        val sprintId: UUID,
        val name: String,
        val status: String,
        val startDate: String? = null,
        val endDate: String? = null,
    )

    data class CreateSprintRequest(val name: String?, val startDate: String? = null, val endDate: String? = null)
    data class UpdateSprintRequest(
        val name: String? = null,
        val startDate: String? = null,
        val endDate: String? = null,
        val status: String? = null,
    )
    data class AssignSprintRequest(val sprintId: UUID? = null)

    private val statusValues = setOf("planned", "active", "completed")

    private fun project(key: String) =
        projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")

    private fun find(key: String, groupObjectId: UUID): ItemGroup {
        val p = project(key)
        return groups.findByProjectObjectIdAndObjectId(p.objectId!!, groupObjectId) ?: throw NotFoundException("sprint 不存在")
    }

    private fun ItemGroup.toDto() = SprintDto(
        sprintId = objectId,
        name = name,
        status = status,
        startDate = startDate?.toString(),
        endDate = endDate?.toString(),
    )

    private fun parseDate(text: String?, field: String): LocalDate? {
        if (text.isNullOrBlank()) return null
        return try {
            LocalDate.parse(text)
        } catch (_: Exception) {
            throw BadRequestException("$field 格式应为 yyyy-MM-dd")
        }
    }

    fun list(key: String): List<SprintDto> =
        groups.findByProjectObjectIdOrderByCreatedAt(project(key).objectId!!).map { it.toDto() }

    @Transactional
    fun create(key: String, request: CreateSprintRequest): SprintDto {
        leaderGuard.requireLeader(key)
        val name = request.name?.trim().takeUnless { it.isNullOrEmpty() } ?: throw BadRequestException("name 必填")
        val p = project(key)
        groupTypes.findByProjectObjectIdAndTypeKey(p.objectId!!, "sprint") ?: throw ConflictException("项目缺少 sprint 类型")
        val saved = groups.save(
            ItemGroup(
                projectObjectId = p.objectId!!,
                name = name,
                startDate = parseDate(request.startDate, "startDate"),
                endDate = parseDate(request.endDate, "endDate"),
            ),
        )
        return saved.toDto()
    }

    fun get(key: String, groupObjectId: UUID): SprintDto = find(key, groupObjectId).toDto()

    @Transactional
    fun update(key: String, groupObjectId: UUID, request: UpdateSprintRequest): SprintDto {
        leaderGuard.requireLeader(key)
        val g = find(key, groupObjectId)
        val name = request.name?.trim()
        if (name != null && name.isEmpty()) throw BadRequestException("name 不能为空")
        if (request.status != null && request.status !in statusValues) {
            throw BadRequestException("status 只能是 planned/active/completed")
        }
        val updated = g.copy(
            name = name ?: g.name,
            status = request.status ?: g.status,
            startDate = when {
                request.startDate == null -> g.startDate
                request.startDate.isEmpty() -> null
                else -> parseDate(request.startDate, "startDate")
            },
            endDate = when {
                request.endDate == null -> g.endDate
                request.endDate.isEmpty() -> null
                else -> parseDate(request.endDate, "endDate")
            },
            updatedAt = Instant.now(),
        )
        return groups.save(updated).toDto()
    }

    /** 仅空 sprint 可删(裁决 6):有 membership 即历史/在途事实,删了就丢,409 拒绝。 */
    @Transactional
    fun delete(key: String, groupObjectId: UUID) {
        leaderGuard.requireLeader(key)
        val g = find(key, groupObjectId)
        if (memberships.existsByGroupObjectId(g.objectId!!)) throw ConflictException("sprint 非空,请先移出 items")
        groups.delete(g)
    }

    /** sprint 页 items:含历史行(completed 后仍在其列,裁决 2)。 */
    fun items(key: String, groupObjectId: UUID): List<ItemService.ItemDto> {
        val g = find(key, groupObjectId)
        val rows = memberships.findByGroupObjectId(g.objectId!!)
        if (rows.isEmpty()) return emptyList()
        val objectIds = itemRepo.findAllByObjectIdIn(rows.map { it.itemObjectId }).map { it.objectId }.toSet()
        return itemService.list(key).filter { it.itemId in objectIds }
    }

    /** backlog 派生桶:非终态 ∧ 不在任何 planned/active sprint(裁决 2/5,零建模)。 */
    fun backlog(key: String): List<ItemService.ItemDto> = itemService.list(key, backlogOnly = true)

    data class BoardDto(val activeSprints: List<SprintDto>, val items: List<ItemService.ItemDto>)

    /** 看板(YPJ-29):active sprint 的 items 并集;无 active → 全量(降级语义在后端,前端零 sprint 逻辑)。 */
    fun board(key: String): BoardDto {
        val active = groups.findByProjectObjectIdOrderByCreatedAt(project(key).objectId!!).filter { it.status == "active" }
        val activeDto = active.map { it.toDto() }
        if (active.isEmpty()) return BoardDto(emptyList(), itemService.list(key))
        val rows = active.flatMap { memberships.findByGroupObjectId(it.objectId!!) }
        if (rows.isEmpty()) return BoardDto(activeDto, emptyList())
        val objectIds = itemRepo.findAllByObjectIdIn(rows.map { it.itemObjectId }.toSet()).map { it.objectId }.toSet()
        return BoardDto(activeDto, itemService.list(key).filter { it.itemId in objectIds })
    }

    /**
     * 指派/移出 item 的当前 sprint。替换式:先删旧 planned/active 行再插新行;
     * completed sprint 拒绝指派(历史不可变);sprintId=null = 移出(仅删 planned/active 行)。
     */
    @Transactional
    fun assignItem(itemObjectId: UUID, sprintId: UUID?): ItemService.ItemDto {
        val item = itemRepo.findByObjectId(itemObjectId) ?: throw NotFoundException("item 不存在")
        val rows = memberships.findByItemObjectIdIn(listOf(item.objectId!!))
        val rowGroups = if (rows.isEmpty()) emptyMap()
        else groups.findAllByObjectIdIn(rows.map { it.groupObjectId }.toSet()).associateBy { it.objectId!! }
        val current = rows.firstOrNull { rowGroups[it.groupObjectId]?.status != "completed" }

        if (sprintId == null) {
            if (current != null) {
                memberships.delete(current)
                memberHistory.recordRemove(current.groupObjectId, current.itemObjectId, clock.instant()) // YPJ-54 留痕
            }
            return itemService.get(itemObjectId)
        }
        val sprint = groups.findByProjectObjectIdAndObjectId(item.projectObjectId, sprintId)
            ?: throw NotFoundException("sprint 不存在")
        if (sprint.status == "completed") throw ConflictException("sprint 已完成,不能指派")
        if (current != null && current.groupObjectId == sprint.objectId) return itemService.get(itemObjectId)
        if (current != null) {
            memberships.delete(current)
            memberHistory.recordRemove(current.groupObjectId, current.itemObjectId, clock.instant())
        }
        memberships.save(ItemGroupMember(groupObjectId = sprint.objectId!!, itemObjectId = item.objectId!!))
        memberHistory.recordAdd(sprint.objectId!!, item.objectId!!, clock.instant())
        return itemService.get(itemObjectId)
    }
}
