package yangzhou.api.business.itemgroup

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.business.item.ItemService
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.api.guard.LeaderGuard
import yangzhou.persistence.ItemGroup
import yangzhou.persistence.ItemGroupMember
import yangzhou.persistence.ItemGroupType
import yangzhou.persistence.repository.ItemGroupMemberHistoryRepository
import yangzhou.persistence.repository.ItemGroupMemberRepository
import yangzhou.persistence.repository.ItemGroupRepository
import yangzhou.persistence.repository.ItemGroupTypeRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ProjectRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 统一组服务(spec 0097 S2):吸收 SprintService 全部业务 + 0056 通用组端点。
 * Sprint 只是组名,「开始 Sprint」只是动作名;组实例只存 currentStatus(item_group.status varchar,值域由类型 workflow 定义)。
 * 组不进匹配输入——只影响展示与过滤,不参与可行性/忙闲聚合(领域规则 5)。
 * 归属正交(PRD §7.3):membership 按 groupType 替换该类型未完成行,sprint 与 release 互不干扰。
 */
@Service
class ItemGroupService(
    private val leaderGuard: LeaderGuard,
    private val projects: ProjectRepository,
    private val types: ItemGroupTypeRepository,
    private val workflow: GroupWorkflowService,
    private val groups: ItemGroupRepository,
    private val memberships: ItemGroupMemberRepository,
    private val itemRepo: ItemRepository,
    private val itemService: ItemService,
    private val memberHistory: ItemGroupMemberHistoryRepository,
    private val clock: Clock,
) {
    data class GroupTypeDto(
        val typeKey: String,
        val name: String,
        val builtin: Boolean,
        val level: Int? = null,
        val timed: Boolean = false,
    )

    data class CreateGroupTypeRequest(
        val typeKey: String?,
        val name: String?,
        val timed: Boolean = false,
    )

    data class UpdateGroupTypeRequest(
        val name: String?,
    )

    data class GroupDto(
        val groupId: UUID,
        val typeKey: String,
        val name: String,
        val status: String,
        val startDate: String? = null,
        val endDate: String? = null,
    )

    data class CreateGroupRequest(
        val typeKey: String?,
        val name: String?,
        val startDate: String? = null,
        val endDate: String? = null,
    )

    data class UpdateGroupRequest(
        val name: String? = null,
        val startDate: String? = null,
        val endDate: String? = null,
    )

    data class MembershipRequest(
        val groupId: UUID? = null,
        val groupType: String? = null,
    )

    /** spec 0110:组动作请求(仅 complete 用 dispositions)。 */
    data class GroupActionRequest(
        val dispositions: List<DispositionLine> = emptyList(),
    )

    data class DispositionLine(
        val itemId: String,
        val targetGroupId: String?,
    )

    private fun project(key: String) = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")

    private fun ItemGroupType.toDto() = GroupTypeDto(typeKey, name, builtin, level, timed)

    private fun ItemGroup.toDto() = GroupDto(
        groupId = objectId!!,
        typeKey = type,
        name = name,
        status = status,
        startDate = startDate?.toString(),
        endDate = endDate?.toString(),
    )

    private fun parseDate(
        text: String?,
        field: String,
    ): LocalDate? {
        if (text.isNullOrBlank()) return null
        return try {
            LocalDate.parse(text)
        } catch (_: Exception) {
            throw BadRequestException("$field 格式应为 yyyy-MM-dd")
        }
    }

    private fun typeOf(
        key: String,
        typeKey: String,
    ): ItemGroupType {
        val p = project(key)
        return types.findByProjectObjectIdAndTypeKey(p.objectId!!, typeKey)
            ?: throw NotFoundException("group 类型不存在:$typeKey")
    }

    private fun findGroup(
        key: String,
        groupId: UUID,
    ): ItemGroup {
        val p = project(key)
        return groups.findByProjectObjectIdAndObjectId(p.objectId!!, groupId) ?: throw NotFoundException("group 不存在")
    }

    /** 归属是交付/规划历史事实的类型(builtin 或 level-1,spec 0100 切片A):非空不可删;自定义类型 membership 视作展示组织,可删。 */
    private fun membershipIsHistory(
        key: String,
        typeKey: String,
    ): Boolean {
        val t = typeOf(key, typeKey)
        return t.builtin || t.level == 1
    }

    /** 唯一口径:项目 level-1 类型 typeKey(spec 0100 切片B,替代散落 "sprint" 字面量);无 level-1 → null。 */
    private fun level1Key(projectObjectId: UUID): String? = types.findByProjectObjectIdAndLevel(projectObjectId, 1)?.typeKey

    // ---------- 类型 CRUD(0056 语义不变 + workflow 种子) ----------

    fun listTypes(key: String): List<GroupTypeDto> = types.findByProjectObjectIdOrderByCreatedAt(project(key).objectId!!).map { it.toDto() }

    @Transactional
    fun createType(
        key: String,
        request: CreateGroupTypeRequest,
    ): GroupTypeDto {
        leaderGuard.requireLeader(key)
        val typeKey =
            request.typeKey?.trim().takeUnless { it.isNullOrEmpty() }
                ?: throw BadRequestException("typeKey 必填")
        if (!Regex("[a-z][a-z0-9-]{1,19}").matches(typeKey)) {
            throw BadRequestException("typeKey 需匹配 [a-z][a-z0-9-]{1,19}")
        }
        val name = request.name?.trim().takeUnless { it.isNullOrEmpty() } ?: throw BadRequestException("name 必填")
        val pid = project(key).objectId!!
        if (types.existsByProjectObjectIdAndTypeKey(pid, typeKey)) {
            throw ConflictException("group 类型已存在:$typeKey")
        }
        // spec 0100 切片A:「加 level」自底向上 = 最小未占正整数(恰一 level-1;删 level-1 后新类型回落补位);
        // release 等 builtin level=NULL 不参与;并发竞态由 (project_id, level) 唯一索引兜底。
        val used = types.findByProjectObjectIdOrderByCreatedAt(pid).mapNotNull { it.level }.toSet()
        val level = (1..used.size + 1).first { it !in used }
        val saved =
            try {
                types.save(ItemGroupType(projectObjectId = pid, typeKey = typeKey, name = name, level = level, timed = request.timed))
            } catch (_: DataIntegrityViolationException) {
                throw ConflictException("group 类型层级并发冲突,请重试:$typeKey")
            }
        workflow.seedFor(saved)
        return saved.toDto()
    }

    @Transactional
    fun updateType(
        key: String,
        typeKey: String,
        request: UpdateGroupTypeRequest,
    ): GroupTypeDto {
        leaderGuard.requireLeader(key)
        val t = typeOf(key, typeKey)
        val name =
            request.name?.trim().takeUnless { it.isNullOrEmpty() }
                ?: throw BadRequestException("name 必填")
        return types.save(t.copy(name = name)).toDto()
    }

    /** builtin 不可删;空类型即可删(含 level-1,2026-11-05 用户拍板);类型下仍有组即删除会丢组,409 拒绝;group_status 随 FK 级联清除。 */
    @Transactional
    fun deleteType(
        key: String,
        typeKey: String,
    ) {
        leaderGuard.requireLeader(key)
        val pid = project(key).objectId!!
        val t =
            types.findByProjectObjectIdAndTypeKey(pid, typeKey)
                ?: throw NotFoundException("group 类型不存在:$typeKey")
        if (t.builtin) throw ConflictException("内建类型不可删除:$typeKey")
        if (groups.findByProjectObjectIdOrderByCreatedAt(pid).any { it.type == typeKey }) {
            throw ConflictException("类型下仍有组,请先删除:$typeKey")
        }
        types.delete(t)
    }

    /** 类型 workflow 只读展示(status 集 + 内嵌出边,供动作渲染)。 */
    fun workflow(
        key: String,
        typeKey: String,
    ) = workflow.workflow(typeOf(key, typeKey))

    // ---------- 组 CRUD ----------

    /** level-1 组数据源(board/dashboard 等结构语义消费方;无 level-1 → 空表)。 */
    fun listLevel1Groups(key: String): List<GroupDto> {
        val l1 = level1Key(project(key).objectId!!) ?: return emptyList()
        return listGroups(key, l1)
    }

    fun listGroups(
        key: String,
        typeKey: String?,
        level: Int? = null,
    ): List<GroupDto> {
        val pid = project(key).objectId!!
        val all = groups.findByProjectObjectIdOrderByCreatedAt(pid)
        val byType = if (typeKey == null) all else all.filter { it.type == typeKey }
        val filtered =
            if (level == null) {
                byType
            } else {
                byType.filter { it.type == types.findByProjectObjectIdAndLevel(pid, level)?.typeKey }
            }
        return filtered.map { it.toDto() }
    }

    @Transactional
    fun createGroup(
        key: String,
        request: CreateGroupRequest,
    ): GroupDto {
        leaderGuard.requireLeader(key)
        val typeKey =
            request.typeKey?.trim().takeUnless { it.isNullOrEmpty() }
                ?: throw BadRequestException("typeKey 必填")
        val t = typeOf(key, typeKey)
        val name = request.name?.trim().takeUnless { it.isNullOrEmpty() } ?: throw BadRequestException("name 必填")
        val saved =
            groups.save(
                ItemGroup(
                    projectObjectId = project(key).objectId!!,
                    type = t.typeKey,
                    name = name,
                    status = workflow.startStatusName(t), // 初始态 = 类型 workflow 的 START
                    startDate = parseDate(request.startDate, "startDate"),
                    endDate = parseDate(request.endDate, "endDate"),
                ),
            )
        return saved.toDto()
    }

    fun getGroup(
        key: String,
        groupId: UUID,
    ): GroupDto = findGroup(key, groupId).toDto()

    /** PATCH 仅 name/日期(spec 0097:status 改走动作端点,不再自由 PATCH)。 */
    @Transactional
    fun updateGroup(
        key: String,
        groupId: UUID,
        request: UpdateGroupRequest,
    ): GroupDto {
        leaderGuard.requireLeader(key)
        val g = findGroup(key, groupId)
        val name = request.name?.trim()
        if (name != null && name.isEmpty()) throw BadRequestException("name 不能为空")
        val updated =
            g.copy(
                name = name ?: g.name,
                startDate =
                when {
                    request.startDate == null -> g.startDate
                    request.startDate.isEmpty() -> null
                    else -> parseDate(request.startDate, "startDate")
                },
                endDate =
                when {
                    request.endDate == null -> g.endDate
                    request.endDate.isEmpty() -> null
                    else -> parseDate(request.endDate, "endDate")
                },
                updatedAt = Instant.now(),
            )
        return groups.save(updated).toDto()
    }

    /** sprint/release:membership 是历史/在途事实,非空 409;自定义组:membership 展示性组织,级联删(含留痕行)。 */
    @Transactional
    fun deleteGroup(
        key: String,
        groupId: UUID,
    ) {
        leaderGuard.requireLeader(key)
        val g = findGroup(key, groupId)
        if (membershipIsHistory(key, g.type) && memberships.existsByGroupObjectId(g.objectId!!)) {
            throw ConflictException("组非空,请先移出 items")
        }
        val gid = g.objectId!!
        memberships.findByGroupObjectId(gid).forEach { m ->
            memberHistory.findByGroupObjectId(gid).filter { it.itemObjectId == m.itemObjectId }.forEach(memberHistory::delete)
            memberships.delete(m)
        }
        groups.delete(g)
    }

    // ---------- 动作(spec 0097:组状态变更白名单) ----------

    /** 查 currentStatus 出边,无匹配 function → 409;命中则迁移。 */
    @Transactional
    fun action(
        key: String,
        groupId: UUID,
        function: String,
        request: GroupActionRequest? = null,
    ): GroupDto {
        leaderGuard.requireLeader(key)
        val g = findGroup(key, groupId)
        val t = typeOf(key, g.type)
        val target = workflow.applyAction(t, g.status, function)
        val saved = groups.save(g.copy(status = target, updatedAt = Instant.now())).toDto()
        // spec 0110(YPJ-159):完成 + 处置同一事务——迁移已落库后逐行 replaceMembership,失败整体回滚(含状态迁移)
        if (function == "complete" && request != null && request.dispositions.isNotEmpty()) {
            applyDispositions(g, request.dispositions)
        }
        return saved
    }

    /** spec 0110:完成组处置计划落库。itemId=item 公开 ID(=objectId);targetGroupId=null 回池。 */
    private fun applyDispositions(
        g: ItemGroup,
        lines: List<DispositionLine>,
    ) {
        val ids = lines.map { it.itemId }
        if (ids.size != ids.toSet().size) throw BadRequestException("处置计划含重复 item")
        val memberIds = memberships.findByGroupObjectId(g.objectId!!).map { it.itemObjectId }.toSet()
        for (line in lines) {
            val id =
                try {
                    UUID.fromString(line.itemId)
                } catch (e: IllegalArgumentException) {
                    throw BadRequestException("处置行 itemId 不合法:${line.itemId}")
                }
            if (id !in memberIds) throw BadRequestException("处置行不属于本组:${line.itemId}")
            val tg =
                line.targetGroupId?.let {
                    try {
                        UUID.fromString(it)
                    } catch (e: IllegalArgumentException) {
                        throw BadRequestException("处置行 targetGroupId 不合法:$it")
                    }
                }
            replaceMembership(id, MembershipRequest(groupId = tg, groupType = if (tg == null) g.type else null))
        }
    }

    // ---------- 成员与派生视图 ----------

    /** 组页 items:含历史行(completed 后仍在其列,裁决 2)。 */
    fun items(
        key: String,
        groupId: UUID,
    ): List<ItemService.ItemDto> {
        val g = findGroup(key, groupId)
        val rows = memberships.findByGroupObjectId(g.objectId!!)
        if (rows.isEmpty()) return emptyList()
        val objectIds = itemRepo.findAllByObjectIdIn(rows.map { it.itemObjectId }).map { it.objectId }.toSet()
        return itemService.list(key).filter { it.itemId in objectIds }
    }

    /** backlog 派生桶:非终态 ∧ 不在任何 planned/active level-1 组(裁决 2/5,零建模;谓词筛 level-1 在 ItemService)。 */
    fun backlog(key: String): List<ItemService.ItemDto> = itemService.list(key, backlogOnly = true)

    data class BoardDto(
        val activeGroups: List<GroupDto>,
        val items: List<ItemService.ItemDto>,
    )

    /** 看板(YPJ-29):active sprint 组的 items 并集;无 active → backlog 补集(spec 0098:board ∩ backlog = ∅,即终态 ∪ planned sprint 成员)。 */
    fun board(key: String): BoardDto {
        val l1 = level1Key(project(key).objectId!!)
        val active = groups.findByProjectObjectIdOrderByCreatedAt(project(key).objectId!!).filter { it.type == l1 && it.status == "active" }
        val activeDto = active.map { it.toDto() }
        if (active.isEmpty()) {
            val backlogIds = itemService.list(key, backlogOnly = true).map { it.itemId }.toSet()
            return BoardDto(emptyList(), itemService.list(key).filterNot { it.itemId in backlogIds })
        }
        val rows = active.flatMap { memberships.findByGroupObjectId(it.objectId!!) }
        if (rows.isEmpty()) return BoardDto(activeDto, emptyList())
        val objectIds = itemRepo.findAllByObjectIdIn(rows.map { it.itemObjectId }.toSet()).map { it.objectId }.toSet()
        return BoardDto(activeDto, itemService.list(key).filter { it.itemId in objectIds || it.onBoard })
    }

    /**
     * 归属替换(PUT /items/{id}/group-membership):按 groupType 替换该类型未完成行(FINAL 态行 = 历史,保留);
     * groupId=null + groupType = 移出;目标组 FINAL 态 409(历史不可变,同 sprint 现状 completed 拒)。
     */
    @Transactional
    /** spec 0099:看板拉入/退回请求。 */
    data class BoardMembershipRequest(val onBoard: Boolean)

    fun replaceMembership(
        itemObjectId: UUID,
        request: MembershipRequest,
    ): ItemService.ItemDto {
        val item = itemRepo.findByObjectId(itemObjectId) ?: throw NotFoundException("item 不存在")
        val typeKey: String
        var target: ItemGroup? = null
        if (request.groupId != null) {
            target =
                groups.findByProjectObjectIdAndObjectId(item.projectObjectId, request.groupId)
                    ?: throw NotFoundException("group 不存在")
            typeKey = target.type
            if (request.groupType != null && request.groupType != typeKey) {
                throw BadRequestException("groupType 与目标组类型不一致:$request.groupType ≠ $typeKey")
            }
        } else {
            typeKey =
                request.groupType?.trim().takeUnless { it.isNullOrEmpty() }
                    ?: throw BadRequestException("groupType 必填(groupId 为空时)")
        }
        val t = types.findByProjectObjectIdAndTypeKey(item.projectObjectId, typeKey) ?: throw NotFoundException("group 类型不存在:$typeKey")
        val finalNames = workflow.finalStatusNames(t)

        val rows = memberships.findByItemObjectIdIn(listOf(item.objectId!!))
        val rowGroups =
            if (rows.isEmpty()) {
                emptyMap()
            } else {
                groups.findAllByObjectIdIn(rows.map { it.groupObjectId }.toSet()).associateBy { it.objectId!! }
            }
        val live = rows.filter { rowGroups[it.groupObjectId]?.type == typeKey && rowGroups[it.groupObjectId]?.status !in finalNames }
        // 0109:FINAL 组的残留行(组先行 completed 后旧行非 live 永不删,双行并存事故源)——迁移时一并清,member 表=当前归属真相
        val stale =
            rows.filter {
                rowGroups[it.groupObjectId]?.type == typeKey && rowGroups[it.groupObjectId]?.status in finalNames
            }

        if (target != null) {
            if (target.status in finalNames) throw ConflictException("组已完成,不能指派")
            if (stale.isEmpty() && live.size == 1 && live[0].groupObjectId == target.objectId) {
                return itemService.get(itemObjectId)
            }
        }
        (live + stale).forEach { m ->
            memberships.delete(m)
            memberHistory.recordRemove(m.groupObjectId, m.itemObjectId, clock.instant())
        }
        if (target != null && live.none { it.groupObjectId == target.objectId }) {
            memberships.save(ItemGroupMember(groupObjectId = target.objectId!!, itemObjectId = item.objectId!!))
            memberHistory.recordAdd(target.objectId!!, item.objectId!!, clock.instant())
        }
        return itemService.get(itemObjectId)
    }

    /** spec 0099:看板拉入/退回。拉入 = 置标记 + 同时挂最早 active sprint(若有,幂等);退回 = 清标记 + 移出全部非 completed sprint 成员行(复用归属移出,历史不可变)。 */
    @Transactional
    fun boardMembership(
        itemObjectId: UUID,
        request: BoardMembershipRequest,
    ): ItemService.ItemDto {
        val item = itemRepo.findByObjectId(itemObjectId) ?: throw NotFoundException("item 不存在")
        itemRepo.save(item.copy(onBoard = request.onBoard))
        if (request.onBoard) {
            val active =
                groups.findByProjectObjectIdOrderByCreatedAt(item.projectObjectId)
                    .firstOrNull { it.type == level1Key(item.projectObjectId) && it.status == "active" }
            if (active != null) {
                val rows = memberships.findByItemObjectIdIn(listOf(item.objectId!!))
                if (rows.none { it.groupObjectId == active.objectId }) {
                    memberships.save(ItemGroupMember(groupObjectId = active.objectId!!, itemObjectId = item.objectId!!))
                    memberHistory.recordAdd(active.objectId!!, item.objectId!!, clock.instant())
                }
            }
        } else {
            val l1 = level1Key(item.projectObjectId) ?: return itemService.get(itemObjectId)
            replaceMembership(itemObjectId, MembershipRequest(groupId = null, groupType = l1))
        }
        return itemService.get(itemObjectId)
    }
}
