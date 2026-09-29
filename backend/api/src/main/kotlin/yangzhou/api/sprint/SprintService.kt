package yangzhou.api.sprint

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.item.ItemService
import yangzhou.api.support.BadRequestException
import yangzhou.api.support.ConflictException
import yangzhou.api.support.NotFoundException
import yangzhou.persistence.ItemGroup
import yangzhou.persistence.ItemGroupMember
import yangzhou.persistence.repository.ItemGroupMemberRepository
import yangzhou.persistence.repository.ItemGroupRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ProjectRepository
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
    private val projects: ProjectRepository,
    private val groups: ItemGroupRepository,
    private val memberships: ItemGroupMemberRepository,
    private val itemRepo: ItemRepository,
    private val itemService: ItemService,
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

    private fun find(key: String, groupId: UUID): ItemGroup {
        val p = project(key)
        return groups.findByProjectIdAndObjectId(p.id!!, groupId) ?: throw NotFoundException("sprint 不存在")
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
        groups.findByProjectIdOrderByCreatedAt(project(key).id!!).map { it.toDto() }

    @Transactional
    fun create(key: String, request: CreateSprintRequest): SprintDto {
        val name = request.name?.trim().takeUnless { it.isNullOrEmpty() } ?: throw BadRequestException("name 必填")
        val p = project(key)
        val saved = groups.save(
            ItemGroup(
                projectId = p.id!!,
                name = name,
                startDate = parseDate(request.startDate, "startDate"),
                endDate = parseDate(request.endDate, "endDate"),
            ),
        )
        return saved.toDto()
    }

    fun get(key: String, groupId: UUID): SprintDto = find(key, groupId).toDto()

    @Transactional
    fun update(key: String, groupId: UUID, request: UpdateSprintRequest): SprintDto {
        val g = find(key, groupId)
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
    fun delete(key: String, groupId: UUID) {
        val g = find(key, groupId)
        if (memberships.existsByGroupId(g.id!!)) throw ConflictException("sprint 非空,请先移出 items")
        groups.delete(g)
    }

    /** sprint 页 items:含历史行(completed 后仍在其列,裁决 2)。 */
    fun items(key: String, groupId: UUID): List<ItemService.ItemDto> {
        val g = find(key, groupId)
        val rows = memberships.findByGroupId(g.id!!)
        if (rows.isEmpty()) return emptyList()
        val objectIds = itemRepo.findAllById(rows.map { it.itemId }).map { it.objectId }.toSet()
        return itemService.list(key).filter { it.itemId in objectIds }
    }

    /** backlog 派生桶:非终态 ∧ 不在任何 planned/active sprint(裁决 2/5,零建模)。 */
    fun backlog(key: String): List<ItemService.ItemDto> = itemService.list(key, backlogOnly = true)

    /**
     * 指派/移出 item 的当前 sprint。替换式:先删旧 planned/active 行再插新行;
     * completed sprint 拒绝指派(历史不可变);sprintId=null = 移出(仅删 planned/active 行)。
     */
    @Transactional
    fun assignItem(itemId: UUID, sprintId: UUID?): ItemService.ItemDto {
        val item = itemRepo.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val rows = memberships.findByItemIdIn(listOf(item.id!!))
        val rowGroups = if (rows.isEmpty()) emptyMap()
        else groups.findAllById(rows.map { it.groupId }.toSet()).associateBy { it.id!! }
        val current = rows.firstOrNull { rowGroups[it.groupId]?.status != "completed" }

        if (sprintId == null) {
            if (current != null) memberships.delete(current)
            return itemService.get(itemId)
        }
        val sprint = groups.findByProjectIdAndObjectId(item.projectId, sprintId)
            ?: throw NotFoundException("sprint 不存在")
        if (sprint.status == "completed") throw ConflictException("sprint 已完成,不能指派")
        if (current != null && current.groupId == sprint.id) return itemService.get(itemId)
        if (current != null) memberships.delete(current)
        memberships.save(ItemGroupMember(groupId = sprint.id!!, itemId = item.id!!))
        return itemService.get(itemId)
    }
}
