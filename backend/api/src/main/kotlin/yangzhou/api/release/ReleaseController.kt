package yangzhou.api.release

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
import yangzhou.persistence.repository.MilestoneRepository
import yangzhou.persistence.repository.ProjectRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * V15 Release:交付集合(PRD §7.3/§8,spec 0012)。ItemGroup type='release'(§27 共享抽象)。
 * 生命周期 planned→released 单向(released 终态,历史保留);支持事后补建 = 创建即 released(§8.2)。
 * milestone_id 可空 N:1(§26.3 裁决);membership 不受状态限制(历史 release 可补录 items)。
 */
@RestController
@RequestMapping("/api")
class ReleaseController(private val service: ReleaseService) {

    @GetMapping("/projects/{key}/releases")
    fun list(@PathVariable key: String): List<ReleaseService.ReleaseDto> = service.list(key)

    @PostMapping("/projects/{key}/releases")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable key: String,
        @Valid @RequestBody request: CreateReleaseRequest,
    ): ReleaseService.ReleaseDto = service.create(key, request)

    @PatchMapping("/projects/{key}/releases/{releaseId}")
    fun update(
        @PathVariable key: String,
        @PathVariable releaseId: UUID,
        @RequestBody request: UpdateReleaseRequest,
    ): ReleaseService.ReleaseDto = service.update(key, releaseId, request)

    @DeleteMapping("/projects/{key}/releases/{releaseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable key: String, @PathVariable releaseId: UUID) = service.delete(key, releaseId)
}

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
    private val projects: ProjectRepository,
    private val groups: ItemGroupRepository,
    private val groupMembers: ItemGroupMemberRepository,
    private val milestones: MilestoneRepository,
    private val itemRepo: ItemRepository,
    private val itemService: ItemService,
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
        groups.findByProjectIdAndObjectId(project(key).id!!, releaseId)
            ?.takeIf { it.type == "release" }
            ?: throw NotFoundException("release 不存在")

    private fun milestoneIn(key: String, milestoneId: UUID) =
        milestones.findByProjectIdAndObjectId(project(key).id!!, milestoneId)
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
        milestoneId = milestoneId?.let { milestones.findById(it).orElse(null)?.objectId },
        memberCount = memberCount,
    )

    /** 排序:planned 先(target asc nulls last,再创建序);released 后(发布日 desc,最新在前)。 */
    fun list(key: String): List<ReleaseDto> {
        val pid = project(key).id!!
        val all = groups.findByProjectIdOrderByCreatedAt(pid).filter { it.type == "release" }
        val counts = if (all.isEmpty()) emptyMap()
        else groupMembers.findByGroupIdIn(all.map { it.id!! }).groupBy { it.groupId }.mapValues { it.value.size }
        val (planned, released) = all.partition { it.status == "planned" }
        return planned.sortedWith(
            compareBy<ItemGroup> { it.endDate ?: LocalDate.MAX }.thenBy { it.createdAt },
        ).map { it.toDto(counts[it.id] ?: 0) } +
            released.sortedWith(
                compareByDescending<ItemGroup> { it.releasedDate ?: LocalDate.MIN }.thenBy { it.createdAt },
            ).map { it.toDto(counts[it.id] ?: 0) }
    }

    /** 事后补建:status='released' + releasedDate 直接创建(§8.2)。 */
    @Transactional
    fun create(key: String, request: CreateReleaseRequest): ReleaseDto {
        val name = request.name?.trim().takeUnless { it.isNullOrEmpty() } ?: throw BadRequestException("name 必填")
        val status = request.status ?: "planned"
        if (status !in statusValues) throw BadRequestException("status 只能是 planned/released")
        val releasedDate = request.releasedDate?.takeIf { it.isNotEmpty() }?.let { parseDate(it, "releasedDate") }
        val saved = groups.save(
            ItemGroup(
                projectId = project(key).id!!,
                type = "release",
                name = name,
                status = status,
                endDate = request.targetDate?.takeIf { it.isNotEmpty() }?.let { parseDate(it, "targetDate") },
                releasedDate = releasedDate,
                milestoneId = request.milestoneId?.let { milestoneIn(key, it).id },
            ),
        )
        return saved.toDto(0)
    }

    @Transactional
    fun update(key: String, releaseId: UUID, request: UpdateReleaseRequest): ReleaseDto {
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
        val milestoneId: Long? = when (msReq) {
            null -> g.milestoneId
            "" -> null
            else -> milestoneIn(key, UUID.fromString(msReq)).id
        }
        val saved = groups.save(
            g.copy(name = reqName ?: g.name, status = newStatus, endDate = targetDate, releasedDate = releasedDate, milestoneId = milestoneId, updatedAt = Instant.now()),
        )
        return saved.toDto(groupMembers.findByGroupId(saved.id!!).size)
    }

    /** 非空不可删(成员关系即交付历史,对齐 spec 0010 裁决 6)。 */
    @Transactional
    fun delete(key: String, releaseId: UUID) {
        val g = find(key, releaseId)
        if (groupMembers.existsByGroupId(g.id!!)) throw ConflictException("release 非空,先移出 items(成员关系即交付历史)")
        groups.delete(g)
    }

    /** 拉入/移出(值=指派,null=移出;一个 item 至多属一个 release,端点形状即约束,spec 0012)。 */
    @Transactional
    fun assignItem(itemId: UUID, releaseId: UUID?): ItemService.ItemDto {
        val item = itemRepo.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val rows = groupMembers.findByItemIdIn(listOf(item.id!!))
        val rowGroups = if (rows.isEmpty()) emptyMap()
        else groups.findAllById(rows.map { it.groupId }.toSet()).associateBy { it.id!! }
        val current = rows.filter { rowGroups[it.groupId]?.type == "release" }

        if (releaseId == null) {
            if (current.isNotEmpty()) groupMembers.deleteAll(current)
            return itemService.get(itemId)
        }
        val release = groups.findByProjectIdAndObjectId(item.projectId, releaseId)
            ?.takeIf { it.type == "release" }
            ?: throw NotFoundException("release 不存在")
        if (current.any { it.groupId == release.id }) return itemService.get(itemId)
        groupMembers.deleteAll(current)
        groupMembers.save(ItemGroupMember(groupId = release.id!!, itemId = item.id!!))
        return itemService.get(itemId)
    }
}
