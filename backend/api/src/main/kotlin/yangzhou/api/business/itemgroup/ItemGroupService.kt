package yangzhou.api.business.itemgroup

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.business.item.ItemService
import yangzhou.api.business.item.LifecycleTimes
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.api.guard.ProjectGuard
import yangzhou.persistence.GroupStatusHistory
import yangzhou.persistence.ItemGroup
import yangzhou.persistence.ItemGroupMember
import yangzhou.persistence.ItemGroupType
import yangzhou.persistence.StatusType
import yangzhou.persistence.repository.GroupStatusHistoryRepository
import yangzhou.persistence.repository.GroupStatusRepository
import yangzhou.persistence.repository.ItemGroupMemberHistoryRepository
import yangzhou.persistence.repository.ItemGroupMemberRepository
import yangzhou.persistence.repository.ItemGroupRepository
import yangzhou.persistence.repository.ItemGroupTypeRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ItemStatusHistoryRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.StatusRepository
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
    private val projectGuard: ProjectGuard,
    private val projects: ProjectRepository,
    private val types: ItemGroupTypeRepository,
    private val workflow: GroupWorkflowService,
    private val groups: ItemGroupRepository,
    private val memberships: ItemGroupMemberRepository,
    private val itemRepo: ItemRepository,
    private val itemService: ItemService,
    private val memberHistory: ItemGroupMemberHistoryRepository,
    private val groupStatuses: GroupStatusRepository,
    private val groupStatusHistories: GroupStatusHistoryRepository,
    private val itemStatusHistories: ItemStatusHistoryRepository,
    private val statuses: StatusRepository,
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
        val createdAt: String? = null,
        /** YPJ-269(spec 0174):生命周期派生值零物化——仅 getGroup 详情端点算(SRS §3.2);列表/写端点 toDto 恒 null。 */
        val startedAt: String? = null,
        val completedAt: String? = null,
        val firstItemStartedAt: String? = null,
        val lastItemCompletedAt: String? = null,
    )

    /** YPJ-284(spec 0175):组时间线行——kind=status(组迁移)/member(成员进出);统一时间列,DESC 出参。
     *  itemNumber 仅 member 行(item 成员);存量组无 created 史行时由后端合成创建行(to=null)。 */
    data class GroupTimelineDto(
        val objectId: UUID,
        val kind: String,
        val createdAt: String,
        val from: String? = null,
        val to: String? = null,
        val source: String? = null,
        val itemTitle: String? = null,
        val itemNumber: Int? = null,
        val action: String? = null,
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
        createdAt = createdAt?.toString(),
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
        projectGuard.requireEditor(key)
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
        projectGuard.requireEditor(key)
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
        projectGuard.requireEditor(key)
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
        projectGuard.requireEditor(key)
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
        projectGuard.requireEditor(key)
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
        recordTransition(t, saved, fromName = null, toName = saved.status, source = "created") // YPJ-269:初始进入史行
        return saved.toDto()
    }

    /** YPJ-269(spec 0174):详情端点带生命周期派生值(零物化,SRS §3.2);首/末 item = 直挂成员(与成员表同口径,不含子树)× item LifecycleTimes。 */
    fun getGroup(
        key: String,
        groupId: UUID,
    ): GroupDto {
        val g = findGroup(key, groupId)
        val (startIds, finalIds) = lifecycleBoundaryIds(typeOf(key, g.type))
        val hist = groupStatusHistories.findByGroupObjectIdOrderByCreatedAtAscIdAsc(g.objectId!!)
        val memberIds = memberships.findByGroupObjectId(g.objectId!!).mapNotNull { it.itemObjectId }
        val (firstStart, lastEnd) =
            if (memberIds.isEmpty()) {
                null to null
            } else {
                val pStatuses = statuses.findByProjectObjectIdOrderByPosition(g.projectObjectId)
                val pStart = pStatuses.filter { it.statusType == StatusType.START }.mapNotNull { it.objectId }.toSet()
                val pFinal = pStatuses.filter { it.statusType == StatusType.FINAL }.mapNotNull { it.objectId }.toSet()
                val perItem =
                    itemStatusHistories.findByItemObjectIdInOrderByCreatedAtAscIdAsc(memberIds).groupBy { it.itemObjectId }.values
                perItem.mapNotNull { LifecycleTimes.startedAt(it, pStart) }.minOrNull() to
                    perItem.mapNotNull { LifecycleTimes.completedAt(it, pFinal) }.maxOrNull()
            }
        return g.toDto().copy(
            startedAt = GroupLifecycleTimes.startedAt(hist, startIds)?.toString(),
            completedAt = GroupLifecycleTimes.completedAt(hist, finalIds)?.toString(),
            firstItemStartedAt = firstStart?.toString(),
            lastItemCompletedAt = lastEnd?.toString(),
        )
    }

    /** YPJ-284(spec 0175):组时间线(DESC 后进先见)——组迁移 + 成员进出(join=added_at/leave=removed_at 各一行);
     *  态名/物名解析,破损兜底 "-" 不 500;子组成员回退组名。 */
    fun getGroupTimeline(
        key: String,
        groupId: UUID,
    ): List<GroupTimelineDto> {
        val g = findGroup(key, groupId)
        val names = groupStatuses.findByGroupTypeIdOrderByPosition(typeOf(key, g.type).id!!).associateBy { it.objectId }
        val rows = ArrayList<GroupTimelineDto>()
        groupStatusHistories.findByGroupObjectIdOrderByCreatedAtAscIdAsc(g.objectId!!).mapTo(rows) {
            GroupTimelineDto(
                objectId = it.objectId,
                kind = "status",
                createdAt = it.createdAt.toString(),
                from = it.fromGroupStatusObjectId?.let { id -> names[id]?.name },
                to = names[it.toGroupStatusObjectId]?.name ?: "-",
                source = it.source,
            )
        }
        val mh = memberHistory.findByGroupObjectId(g.objectId!!)
        if (mh.isNotEmpty()) {
            val ids = mh.map { it.itemObjectId }.toSet()
            val titles = itemRepo.findAllByObjectIdIn(ids).associateBy { it.objectId }
            val groupNames =
                ids.minus(titles.keys).takeIf { it.isNotEmpty() }?.let { groups.findAllByObjectIdIn(it) }
                    ?.associateBy { it.objectId }
            mh.forEach { m ->
                val it0 = titles[m.itemObjectId]
                val name = it0?.title ?: groupNames?.get(m.itemObjectId)?.name ?: "-"
                rows += GroupTimelineDto(
                    objectId = m.objectId,
                    kind = "member",
                    createdAt = m.addedAt.toString(),
                    itemTitle = name,
                    itemNumber = it0?.number,
                    action = "join",
                )
                m.removedAt?.let { rm ->
                    rows += GroupTimelineDto(
                        objectId = m.objectId,
                        kind = "member",
                        createdAt = rm.toString(),
                        itemTitle = name,
                        itemNumber = it0?.number,
                        action = "leave",
                    )
                }
            }
        }
        // 存量组无 created 史行:从实体 createdAt 合成创建行(不造 from→to,显示仅「创建」)
        if (rows.none { it.source == "created" }) {
            rows += GroupTimelineDto(objectId = g.objectId!!, kind = "status", createdAt = g.createdAt.toString(), source = "created")
        }
        return rows.sortedWith(compareByDescending { it.createdAt })
    }

    /** YPJ-269(spec 0174):类型 workflow 的 START/FINAL 态 objectId 集(组生命周期派生边界)。 */
    private fun lifecycleBoundaryIds(t: ItemGroupType): Pair<Set<UUID>, Set<UUID>> {
        val sts = groupStatuses.findByGroupTypeIdOrderByPosition(t.id!!)
        return sts.filter { it.statusType == StatusType.START }.mapNotNull { it.objectId }.toSet() to
            sts.filter { it.statusType == StatusType.FINAL }.mapNotNull { it.objectId }.toSet()
    }

    /** YPJ-269(spec 0174):组状态史行——每次状态变更一行(SRS §3.1);source=created(建组初始)| action(动作端点)| cascade(父组联动/改父,仅实际变化时)。 */
    private fun recordTransition(
        t: ItemGroupType,
        g: ItemGroup,
        fromName: String?,
        toName: String,
        source: String,
    ) {
        val byName = groupStatuses.findByGroupTypeIdOrderByPosition(t.id!!).associateBy { it.name }
        val to = byName[toName] ?: error("组状态不存在:$toName") // 名字来自同 workflow,缺失即数据破损(typeOfPid 同口径)
        groupStatusHistories.save(
            GroupStatusHistory(
                groupObjectId = g.objectId!!,
                fromGroupStatusObjectId = fromName?.let { byName[it]?.objectId },
                toGroupStatusObjectId = to.objectId,
                source = source,
            ),
        )
    }

    /** PATCH name/日期/父组(spec 0097:status 改走动作端点;0113 增 parentGroupId,null=不动、无置空)。 */
    @Transactional
    fun updateGroup(
        key: String,
        groupId: UUID,
        request: UpdateGroupRequest,
    ): GroupDto {
        projectGuard.requireEditor(key)
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
                            recordTransition(typeOfPid(pid, oldParent.type), oldParent, fromName = oldParent.status, toName = startName, source = "cascade") // YPJ-269:改父回退史行
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
        projectGuard.requireEditor(key)
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
        projectGuard.requireEditor(key)
        val g = findGroup(key, groupId)
        val t = typeOf(key, g.type)
        // spec 0114:非叶层组状态由子组逐层联动推导,不开放手动动作
        if ((t.level ?: 0) > 1) throw ConflictException("父层组状态由子组联动,不支持手动动作")
        val target = workflow.applyAction(t, g.status, function)
        val saved = groups.save(g.copy(status = target, updatedAt = Instant.now()))
        recordTransition(t, saved, fromName = g.status, toName = target, source = "action") // YPJ-269:动作史行
        // spec 0110(YPJ-159):完成 + 处置同一事务——迁移已落库后逐行 replaceMembership,失败整体回滚(含状态迁移)
        // spec 0114:完成判定按语义「迁移至 FINAL」而非函数字面量——自定义工作流出边 fn=switch,处置同事务照走
        if (workflow.statusType(t, target) == StatusType.FINAL && request != null && request.dispositions.isNotEmpty()) {
            applyDispositions(g, request.dispositions)
        }
        propagateParent(g.copy(status = target))
        return saved.toDto()
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
        recordTransition(parentType, parent, fromName = parent.status, toName = targetName, source = "cascade") // YPJ-269:联动史行
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

    /** spec 0141(YPJ-220):甘特范围用——看板集合的 item objectId(与 board() 同口径,含无 active 退化为非 backlog 全量)。 */
    fun boardItemObjectIds(key: String): Set<UUID> = board(key).items.map { it.itemId }.toSet()

    /** spec 0141:group 范围——叶层组直取成员;父层组(level-2)= 叶子子组成员并集;多组并集。 */
    fun groupMemberItemObjectIds(
        key: String,
        groupIds: List<UUID>,
    ): Set<UUID> {
        val pid = project(key).objectId!!
        val all = groups.findByProjectObjectIdOrderByCreatedAt(pid)
        val byId = all.associateBy { it.objectId }
        val out = mutableSetOf<UUID>()
        for (gid in groupIds) {
            val g = byId[gid] ?: throw NotFoundException("group 不存在")
            val childIds = all.filter { it.parentObjectId == g.objectId }.map { it.objectId }
            val leafIds = if (childIds.isEmpty()) listOf(g.objectId) else childIds
            leafIds.forEach { leaf -> out += memberships.findByGroupObjectId(leaf).map { it.itemObjectId } }
        }
        return out
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
                // YPJ-216(用户 2026-11-23 拍板 A):幂等重拉也过守卫——存量成员无日期则补铺,有显式日期原样保留
                if (t.timed) itemService.applySprintPullSchedule(item)
                return itemService.get(itemObjectId)
            }
        }
        (live + stale).forEach { m ->
            memberships.delete(m)
            memberHistory.recordRemove(m.groupObjectId, m.itemObjectId, clock.instant())
        }
        // YPJ-238:移出 level-1 sprint(退板路径之一)→ 删排序行
        if (target == null && typeKey == level1Key(item.projectObjectId)) {
            itemService.removeBoardOrder(item.objectId!!)
        }
        if (target != null && live.none { it.groupObjectId == target.objectId }) {
            memberships.save(ItemGroupMember(groupObjectId = target.objectId!!, itemObjectId = item.objectId!!))
            memberHistory.recordAdd(target.objectId!!, item.objectId!!, clock.instant())
            // YPJ-216(spec 0164):timed sprint 拉入排期守卫(同事务)
            if (t.timed) itemService.applySprintPullSchedule(item)
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
                    // YPJ-216(spec 0164):看板拉入自动挂 sprint,同排期守卫
                    itemService.applySprintPullSchedule(item)
                }
            }
        } else {
            itemRepo.save(item.copy(onBoard = false))
            // YPJ-238:退板删排序行(S0 走这里;有组走 replaceMembership 同一钩子,幂等)
            itemService.removeBoardOrder(item.objectId!!)
            if (l1 != null) replaceMembership(itemObjectId, MembershipRequest(groupId = null, groupType = l1))
        }
        return itemService.get(itemObjectId)
    }

    /** spec 0117:拉入自动开 sprint——planned 经 START 出边迁至 active(复用 workflow 白名单,同事务;无 start 出边的自定义拓扑 409,拉入拒)。 */
    private fun startGroup(g: ItemGroup): ItemGroup {
        val t = typeOfPid(g.projectObjectId, g.type)
        val target = workflow.applyAction(t, g.status, "start")
        val saved = groups.save(g.copy(status = target, updatedAt = Instant.now()))
        recordTransition(t, saved, fromName = g.status, toName = target, source = "action") // YPJ-269:看板拉入自动激活也是真实 start(源仍 action,非推导)
        return saved
    }
}
