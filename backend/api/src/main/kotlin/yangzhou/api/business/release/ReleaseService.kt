package yangzhou.api.business.release

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.business.item.ItemService
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.ItemGroup
import yangzhou.persistence.ItemGroupMember
import yangzhou.persistence.repository.ItemGroupMemberHistoryRepository
import yangzhou.persistence.repository.ItemGroupMemberRepository
import yangzhou.persistence.repository.ItemGroupRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.MilestoneRepository
import yangzhou.api.guard.LeaderGuard
import yangzhou.persistence.repository.ProjectRepository
import jakarta.validation.constraints.NotBlank
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CreateReleaseRequest(
    @field:NotBlank val name: String?,
    val targetDate: String? = null,
    val status: String? = null,
    val releasedDate: String? = null,
    val milestoneId: UUID? = null,
)

data class UpdateReleaseRequest(
    val name: String? = null,
    val targetDate: String? = null,
    val status: String? = null,
    val releasedDate: String? = null,
    /** uuid=设置;空串=清除;null=不变(与日期字段语义一致)。 */
    val milestoneId: String? = null,
)

@Service
class ReleaseService(
    private val leaderGuard: LeaderGuard,
    private val projects: ProjectRepository,
    private val groups: ItemGroupRepository,
    private val groupMembers: ItemGroupMemberRepository,
    private val milestones: MilestoneRepository,
    private val itemRepo: ItemRepository,
    private val itemService: ItemService,
    private val memberHistory: ItemGroupMemberHistoryRepository,
    private val clock: Clock,
    private val groupTypes: yangzhou.persistence.repository.ItemGroupTypeRepository,
) {

    data class ReleaseDto(
        val releaseId: UUID,
        val name: String,
        val status: String,
        val targetDate: String? = null,
        val releasedDate: String? = null,
        val milestoneId: UUID? = null,
        val memberCount: Int = 0,
    )

    data class AssignReleaseRequest(val releaseId: UUID?)

    private val statusValues = setOf("planned", "released")

    private fun project(key: String) =
        projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")

    private fun find(key: String, releaseId: UUID): ItemGroup =
        groups.findByProjectObjectIdAndObjectId(project(key).objectId!!, releaseId)
            ?.takeIf { it.type == "release" }
            ?: throw NotFoundException("release 不存在")

    private fun milestoneIn(key: String, milestoneId: UUID) =
        milestones.findByProjectObjectIdAndObjectId(project(key).objectId!!, milestoneId)
            ?: throw NotFoundException("milestone 不存在")

    private fun parseDate(text: String, field: String): LocalDate =
        try {
            LocalDate.parse(text)
        } catch (_: Exception) {
            throw BadRequestException("$field 格式应为 yyyy-MM-dd")
        }

    private fun ItemGroup.toDto(memberCount: Int) = ReleaseDto(
        releaseId = objectId,
        name = name,
        status = status,
        targetDate = endDate?.toString(),
        releasedDate = releasedDate?.toString(),
        milestoneId = milestoneObjectId?.let { milestones.findByObjectId(it)?.objectId },
        memberCount = memberCount,
    )

    /** 排序:planned 先(target asc nulls last,再创建序);released 后(发布日 desc,最新在前)。 */
    fun list(key: String): List<ReleaseDto> {
        val pid = project(key).objectId!!
        val all = groups.findByProjectObjectIdOrderByCreatedAt(pid).filter { it.type == "release" }
        val counts = if (all.isEmpty()) emptyMap()
        else groupMembers.findByGroupObjectIdIn(all.map { it.objectId!! }).groupBy { it.groupObjectId }.mapValues { it.value.size }
        val (planned, released) = all.partition { it.status == "planned" }
        return planned.sortedWith(
            compareBy<ItemGroup> { it.endDate ?: LocalDate.MAX }.thenBy { it.createdAt },
        ).map { it.toDto(counts[it.objectId] ?: 0) } +
            released.sortedWith(
                compareByDescending<ItemGroup> { it.releasedDate ?: LocalDate.MIN }.thenBy { it.createdAt },
            ).map { it.toDto(counts[it.objectId] ?: 0) }
    }

    /** 事后补建:status='released' + releasedDate 直接创建(§8.2)。 */
    @Transactional
    fun create(key: String, request: CreateReleaseRequest): ReleaseDto {
        leaderGuard.requireLeader(key)
        val p = project(key)
        groupTypes.findByProjectObjectIdAndTypeKey(p.objectId!!, "release") ?: throw ConflictException("项目缺少 release 类型")
        val name = request.name?.trim().takeUnless { it.isNullOrEmpty() } ?: throw BadRequestException("name 必填")
        val status = request.status ?: "planned"
        if (status !in statusValues) throw BadRequestException("status 只能是 planned/released")
        val releasedDate = request.releasedDate?.takeIf { it.isNotEmpty() }?.let { parseDate(it, "releasedDate") }
        val saved = groups.save(
            ItemGroup(
                projectObjectId = p.objectId!!,
                type = "release",
                name = name,
                status = status,
                endDate = request.targetDate?.takeIf { it.isNotEmpty() }?.let { parseDate(it, "targetDate") },
                releasedDate = releasedDate,
                milestoneObjectId = request.milestoneId?.let { milestoneIn(key, it).objectId },
            ),
        )
        return saved.toDto(0)
    }

    @Transactional
    fun update(key: String, releaseId: UUID, request: UpdateReleaseRequest): ReleaseDto {
        leaderGuard.requireLeader(key)
        val g = find(key, releaseId)
        if (g.status == "released") {
            val touched = request.name != null || request.targetDate != null || request.status != null ||
                request.releasedDate != null || request.milestoneId != null
            if (touched) throw ConflictException("release 已发布,不可变更(历史保留)")
        }
        val reqName = request.name?.trim()
        if (reqName != null && reqName.isEmpty()) throw BadRequestException("name 不能为空")
        if (request.status != null && request.status !in statusValues) {
            throw BadRequestException("status 只能是 planned/released")
        }
        val newStatus = request.status ?: g.status
        val targetDate = when {
            request.targetDate == null -> g.endDate
            request.targetDate.isEmpty() -> null
            else -> parseDate(request.targetDate, "targetDate")
        }
        val releasedDate = when {
            request.releasedDate == null -> g.releasedDate
            request.releasedDate.isEmpty() -> null
            else -> parseDate(request.releasedDate, "releasedDate")
        }
        val msReq = request.milestoneId
        val milestoneId: UUID? = when (msReq) {
            null -> g.milestoneObjectId
            "" -> null
            else -> milestoneIn(key, UUID.fromString(msReq)).objectId
        }
        val saved = groups.save(
            g.copy(name = reqName ?: g.name, status = newStatus, endDate = targetDate, releasedDate = releasedDate, milestoneObjectId = milestoneId, updatedAt = Instant.now()),
        )
        return saved.toDto(groupMembers.findByGroupObjectId(saved.objectId!!).size)
    }

    /** 非空不可删(成员关系即交付历史,对齐 spec 0010 裁决 6)。 */
    @Transactional
    fun delete(key: String, releaseId: UUID) {
        leaderGuard.requireLeader(key)
        val g = find(key, releaseId)
        if (groupMembers.existsByGroupObjectId(g.objectId!!)) throw ConflictException("release 非空,先移出 items(成员关系即交付历史)")
        groups.delete(g)
    }

    /** 拉入/移出(值=指派,null=移出;一个 item 至多属一个 release,端点形状即约束,spec 0012)。 */
    @Transactional
    fun assignItem(itemObjectId: UUID, releaseId: UUID?): ItemService.ItemDto {
        val item = itemRepo.findByObjectId(itemObjectId) ?: throw NotFoundException("item 不存在")
        val rows = groupMembers.findByItemObjectIdIn(listOf(item.objectId!!))
        val rowGroups = if (rows.isEmpty()) emptyMap()
        else groups.findAllByObjectIdIn(rows.map { it.groupObjectId }.toSet()).associateBy { it.objectId!! }
        val current = rows.filter { rowGroups[it.groupObjectId]?.type == "release" }

        if (releaseId == null) {
            if (current.isNotEmpty()) {
                groupMembers.deleteAll(current)
                current.forEach { memberHistory.recordRemove(it.groupObjectId, it.itemObjectId, clock.instant()) } // YPJ-54 留痕
            }
            return itemService.get(itemObjectId)
        }
        val release = groups.findByProjectObjectIdAndObjectId(item.projectObjectId, releaseId)
            ?.takeIf { it.type == "release" }
            ?: throw NotFoundException("release 不存在")
        if (current.any { it.groupObjectId == release.objectId }) return itemService.get(itemObjectId)
        groupMembers.deleteAll(current)
        current.forEach { memberHistory.recordRemove(it.groupObjectId, it.itemObjectId, clock.instant()) }
        groupMembers.save(ItemGroupMember(groupObjectId = release.objectId!!, itemObjectId = item.objectId!!))
        memberHistory.recordAdd(release.objectId!!, item.objectId!!, clock.instant())
        return itemService.get(itemObjectId)
    }
}
