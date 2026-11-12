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
import yangzhou.persistence.StatusType
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
        /** 父组公开 ID(邻层:类型 level=自身+1 的组);顶层组恒 null(spec 0114 多层泛化)。 */
        val parentGroupId: UUID? = null,
    )

    data class CreateGroupRequest(
        val typeKey: String?,
        val name: String?,
        val startDate: String? = null,
        val endDate: String? = null,
        /** 仅非顶层组可挂;父组类型须邻层(level=自身+1)且自身无父(spec 0114 泛化)。 */
        val parentGroupId: String? = null,
    )

    data class UpdateGroupRequest(
        val name: String? = null,
        val startDate: String? = null,
        val endDate: String? = null,
        val parentGroupId: String? = null, // spec 0113:null=不动;非空=改挂该父(两级项目不提供置空)
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
        parentGroupId = parentObjectId,
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
        // spec 0114:层级封顶 3(用户 2026-11-15 拍板;builtin level=NULL 不占位不参与)
        if (level > 3) throw BadRequestException("分组类型层级封顶 3(自底向上;builtin 不占位)")
        val saved =
            try {
                types.save(ItemGroupType(projectObjectId = pid, typeKey = typeKey, name = name, level = level, timed = request.timed))
            } catch (_: DataIntegrityViolationException) {
                throw ConflictException("group 类型层级并发冲突,请重试:$typeKey")
            }
        workflow.seedFor(saved)
        // spec 0117(YPJ-173):S0→有组转换点(首个用户类型必得 level-1)——同事务清全项目标记,票落 backlog(排期归人)
        if (used.isEmpty()) itemRepo.clearOnBoard(pid)
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
        // spec 0114:本层为「中间链」(上下层类型都存在)时删除会断开邻接,409;删顶层/底层放行
        val lvlNow = t.level
        if (lvlNow != null) {
            val ls = types.findByProjectObjectIdOrderByCreatedAt(pid).mapNotNull { it.level }
            if (ls.any { it < lvlNow } && ls.any { it > lvlNow }) {
                throw ConflictException("中间层类型不可删除(上下层类型均在):$typeKey")
            }
        }
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

    /** spec 0115(YPJ-164):workflow 出边全量替换(状态集/statusType 锁死);校验集中 GroupWorkflowService。 */
    @Transactional
    fun updateWorkflow(
        key: String,
        typeKey: String,
        requests: List<GroupWorkflowService.StatusTransitionsRequest>,
    ): List<GroupWorkflowService.GroupStatusDto> {
        leaderGuard.requireLeader(key)
        return workflow.updateWorkflow(typeOf(key, typeKey), requests)
    }

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
        parentId: UUID? = null,
    ): List<GroupDto> {
        val pid = project(key).objectId!!
        val all = groups.findByProjectObjectIdOrderByCreatedAt(pid)
        val byType = if (typeKey == null) all else all.filter { it.type == typeKey }
        val byLevel =
            if (level == null) {
                byType
            } else {
                byType.filter { it.type == types.findByProjectObjectIdAndLevel(pid, level)?.typeKey }
            }
        // spec 0111:按父组过滤(固定两层树;树形组装由前端完成)
        val filtered = if (parentId == null) byLevel else byLevel.filter { it.parentObjectId == parentId }
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
        val pid = project(key).objectId!!
        // spec 0114:多层不变量泛化(0111/0112 的 N=2 → ≤3)——组类型 level=L:
        // 存在 level>L 的类型 ⇒ 该层组必挂父,父组类型须恰为 L+1 且父组无父(顶层恒无父);
        // 无上层类型 ⇒ 该层即顶层,不可带父。builtin(level=NULL)组不参与层级树,不可带父。
        val levels = types.findByProjectObjectIdOrderByCreatedAt(pid).mapNotNull { it.level }
        if (t.level != null) {
            val hasUpper = levels.any { it > t.level!! }
            if (hasUpper && request.parentGroupId.isNullOrBlank()) {
                throw BadRequestException("存在上层类型,新建组须挂父层组(先建父层组)")
            }
            if (!hasUpper && request.parentGroupId != null) {
                throw BadRequestException("该层为顶层(无上层类型),不可挂父组")
            }
        } else if (request.parentGroupId != null) {
            throw BadRequestException("builtin 组不参与层级树,不可挂父组")
        }
        val parent =
            request.parentGroupId?.let { raw ->
                val lvl = t.level!!
                val id =
                    try {
                        UUID.fromString(raw)
                    } catch (_: IllegalArgumentException) {
                        throw BadRequestException("parentGroupId 不合法:" + raw)
                    }
                val pg = groups.findByProjectObjectIdAndObjectId(pid, id) ?: throw NotFoundException("父组不存在")
                if (typeOf(key, pg.type).level != lvl + 1) throw BadRequestException("父组类型层级须为 " + (lvl + 1))
                pg
            }
        val saved =
            groups.save(
                ItemGroup(
                    projectObjectId = project(key).objectId!!,
                    type = t.typeKey,
                    name = name,
                    status = workflow.startStatusName(t), // 初始态 = 类型 workflow 的 START
                    startDate = parseDate(request.startDate, "startDate"),
                    endDate = parseDate(request.endDate, "endDate"),
                    parentObjectId = parent?.objectId,
                ),
            )
        return saved.toDto()
    }

    fun getGroup(
        key: String,
        groupId: UUID,
    ): GroupDto = findGroup(key, groupId).toDto()

    /** PATCH name/日期/父组(spec 0097:status 改走动作端点;0113 增 parentGroupId,null=不动、无置空)。 */
    @Transactional
    fun updateGroup(
        key: String,
        groupId: UUID,
        request: UpdateGroupRequest,
    ): GroupDto {
        leaderGuard.requireLeader(key)
        val g = findGroup(key, groupId)
        val pid = g.projectObjectId
        val name = request.name?.trim()
        if (name != null && name.isEmpty()) throw BadRequestException("name 不能为空")
        // spec 0114:改父泛化——非顶层组(存在上层类型)可改挂;目标须邻层(level+1)组且自身无父;
        // 子树随迁(仅改本组 parent_object_id,子组指向不变);失败 fast-throw 整体不落库
        val targetParent =
            request.parentGroupId?.let { raw ->
                val lvl = typeOfPid(pid, g.type).level ?: throw BadRequestException("builtin 组不参与层级树,不可改挂")
                if (types.findByProjectObjectIdOrderByCreatedAt(pid).mapNotNull { it.level }.none { it > lvl }) {
                    throw BadRequestException("顶层组不可改挂")
                }
                val id =
                    try {
                        UUID.fromString(raw.trim())
                    } catch (_: IllegalArgumentException) {
                        throw BadRequestException("parentGroupId 不合法:$raw")
                    }
                val target = groups.findByProjectObjectIdAndObjectId(pid, id) ?: throw NotFoundException("目标父组不存在")
                if (typeOfPid(pid, target.type).level != lvl + 1) throw BadRequestException("目标父组类型层级须为 " + (lvl + 1))
                target
            }
        val parentChanged = targetParent != null && targetParent.objectId != g.parentObjectId
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
                parentObjectId = targetParent?.objectId ?: g.parentObjectId,
                updatedAt = Instant.now(),
            )
        val saved = groups.save(updated)
        // 改父后新旧父都按 0111 规则重算(同事务)
        if (parentChanged) {
            g.parentObjectId?.let { oldParentId ->
                recomputeParentStatus(pid, oldParentId)
                // spec 0114:改父后旧父若无子,回起点(0111「无子不动」仅约束删除路径,此处为改父语义)
                val oldParent = groups.findByProjectObjectIdAndObjectId(pid, oldParentId)
                if (oldParent != null && groups.findAllByProjectObjectIdAndParentObjectId(pid, oldParentId).isEmpty()) {
                    workflow.statusNameByType(typeOfPid(pid, oldParent.type), StatusType.START)?.let { startName ->
                        if (oldParent.status != startName) {
                            groups.save(oldParent.copy(status = startName, updatedAt = Instant.now()))
                            oldParent.parentObjectId?.let { recomputeParentStatus(pid, it) }
                        }
                    }
                }
            }
            recomputeParentStatus(pid, targetParent!!.objectId!!)
        }
        return saved.toDto()
    }

    /** sprint/release:membership 是历史/在途事实,非空 409;自定义组:membership 展示性组织,级联删(含留痕行)。 */
    @Transactional
    fun deleteGroup(
        key: String,
        groupId: UUID,
    ) {
        leaderGuard.requireLeader(key)
        val g = findGroup(key, groupId)
        // spec 0111:父组含子组不可删(先删子组)
        if (g.objectId != null && groups.findAllByProjectObjectIdAndParentObjectId(g.projectObjectId, g.objectId).isNotEmpty()) {
            throw ConflictException("组含子组,请先删除子组")
        }
        if (membershipIsHistory(key, g.type) && memberships.existsByGroupObjectId(g.objectId!!)) {
            throw ConflictException("组非空,请先移出 items")
        }
        val gid = g.objectId!!
        memberships.findByGroupObjectId(gid).forEach { m ->
            memberHistory.findByGroupObjectId(gid).filter { it.itemObjectId == m.itemObjectId }.forEach(memberHistory::delete)
            memberships.delete(m)
        }
        groups.delete(g)
        propagateParent(g) // spec 0111:删子组后联动重算父组状态
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
        // spec 0114:非叶层组状态由子组逐层联动推导,不开放手动动作
        if ((t.level ?: 0) > 1) throw ConflictException("父层组状态由子组联动,不支持手动动作")
        val target = workflow.applyAction(t, g.status, function)
        val saved = groups.save(g.copy(status = target, updatedAt = Instant.now())).toDto()
        // spec 0110(YPJ-159):完成 + 处置同一事务——迁移已落库后逐行 replaceMembership,失败整体回滚(含状态迁移)
        // spec 0114:完成判定按语义「迁移至 FINAL」而非函数字面量——自定义工作流出边 fn=switch,处置同事务照走
        if (workflow.statusType(t, target) == StatusType.FINAL && request != null && request.dispositions.isNotEmpty()) {
            applyDispositions(g, request.dispositions)
        }
        propagateParent(g.copy(status = target))
        return saved
    }

    /** spec 0111:子组动作/删除后联动父组状态(同事务);0113 改父复用。定位父后交 recomputeParentStatus。 */
    private fun propagateParent(child: ItemGroup) {
        val parentObjectId = child.parentObjectId ?: return
        recomputeParentStatus(child.projectObjectId, parentObjectId)
    }

    /** spec 0111:按子组状态重算父组状态(0113 改父后新旧父共用)。任一子 ACTIVE→父 ACTIVE;全子 FINAL→父 FINAL;有子其余→父 START;无子不动。状态名经父组类型 workflow 按 statusType 解析,不写字面量。 */
    private fun recomputeParentStatus(
        pid: UUID,
        parentObjectId: UUID,
    ) {
        val parent = groups.findByProjectObjectIdAndObjectId(pid, parentObjectId) ?: return
        val children = groups.findAllByProjectObjectIdAndParentObjectId(pid, parentObjectId)
        if (children.isEmpty()) return
        val childStatusTypes = children.map { c -> workflow.statusType(typeOfPid(pid, c.type), c.status) }
        val wanted =
            when {
                childStatusTypes.any { it == StatusType.ACTIVE } -> StatusType.ACTIVE
                childStatusTypes.all { it == StatusType.FINAL } -> StatusType.FINAL
                else -> StatusType.START
            }
        val parentType = typeOfPid(pid, parent.type)
        if (workflow.statusType(parentType, parent.status) == wanted) return
        val targetName = workflow.statusNameByType(parentType, wanted) ?: return
        groups.save(parent.copy(status = targetName, updatedAt = Instant.now()))
        // spec 0114:逐层级联到顶(本层变化才向上;不变则链上无新输入,截断)
        parent.parentObjectId?.let { recomputeParentStatus(pid, it) }
    }

    /** 内部不变式口径(pid 版 typeOf);类型缺失即数据破损,error。 */
    private fun typeOfPid(
        pid: UUID,
        typeKey: String,
    ): ItemGroupType = types.findByProjectObjectIdAndTypeKey(pid, typeKey) ?: error("group 类型不存在:" + typeKey)

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
        // spec 0117:onBoard 收窄至 S0——有组看板=纯 active sprint 成员,标记不参与(active 分支只可达于有组项目)
        return BoardDto(activeDto, itemService.list(key).filter { it.itemId in objectIds })
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
        // spec 0114:item 只挂叶层(level-1)组,非叶层由子组并集表达
        if ((t.level ?: 0) > 1) throw ConflictException("父层组不直接挂 item,item 挂叶层组")
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

    /** spec 0099 起步,0117 收窄:拉入=S0 置标记 / 有组挂 sprint(最早 active → 无则最早 planned **并激活**,零 sprint 409);退回 = 清标记 + 移出全部非 completed sprint 成员行(复用归属移出,历史不可变)。 */
    @Transactional
    fun boardMembership(
        itemObjectId: UUID,
        request: BoardMembershipRequest,
    ): ItemService.ItemDto {
        val item = itemRepo.findByObjectId(itemObjectId) ?: throw NotFoundException("item 不存在")
        val l1 = level1Key(item.projectObjectId)
        if (request.onBoard) {
            if (l1 == null) {
                // S0(无组项目):标记即进板唯一通道(现状)
                itemRepo.save(item.copy(onBoard = true))
            } else {
                // 有组:二元模型,不置标记;挂最早 active,无则挂最早 planned 并激活(构造性消灭空窗)
                val l1Groups = groups.findByProjectObjectIdOrderByCreatedAt(item.projectObjectId).filter { it.type == l1 }
                val target =
                    l1Groups.firstOrNull { it.status == "active" }
                        ?: l1Groups.firstOrNull { it.status == "planned" }?.let { startGroup(it) }
                        ?: throw ConflictException("无 active/planned sprint 可挂,请先创建 sprint")
                val rows = memberships.findByItemObjectIdIn(listOf(item.objectId!!))
                if (rows.none { it.groupObjectId == target.objectId }) {
                    memberships.save(ItemGroupMember(groupObjectId = target.objectId!!, itemObjectId = item.objectId!!))
                    memberHistory.recordAdd(target.objectId!!, item.objectId!!, clock.instant())
                }
            }
        } else {
            itemRepo.save(item.copy(onBoard = false))
            if (l1 != null) replaceMembership(itemObjectId, MembershipRequest(groupId = null, groupType = l1))
        }
        return itemService.get(itemObjectId)
    }

    /** spec 0117:拉入自动开 sprint——planned 经 START 出边迁至 active(复用 workflow 白名单,同事务;无 start 出边的自定义拓扑 409,拉入拒)。 */
    private fun startGroup(g: ItemGroup): ItemGroup {
        val target = workflow.applyAction(typeOfPid(g.projectObjectId, g.type), g.status, "start")
        return groups.save(g.copy(status = target, updatedAt = Instant.now()))
    }
}
