package yangzhou.api.business.project

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import yangzhou.api.business.github.VcsEvent
import yangzhou.api.business.workspace.WorkspaceService
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.api.guard.LeaderGuard
import yangzhou.persistence.Project
import yangzhou.persistence.ProjectWorkflowRule
import yangzhou.persistence.Status
import yangzhou.persistence.StatusType
import yangzhou.persistence.repository.ProjectDeletionRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.ProjectWorkflowRuleRepository
import yangzhou.persistence.repository.StatusRepository
import java.time.LocalDateTime
import java.util.UUID

/** YPJ-233(spec 0148)+ YPJ-244/SRS v4 修订(2026-11-21 拍板):排序锚 = 渲染锚——
 *  叶行锚 = startDate ?? actualStart(SRS §8.2;仅 actual 的行按可见实际起点排,不垫尾);
 *  父行 = 子孙**叶节点**并集最早端:逐叶 startDate ?? dueDate(含中间层之下的叶),actualStart 并入(SRS §7.2 左端);
 *  各级父行自有日期与自有状态史作废,不参与(SRS §4.3)。
 *  未排期 = 叶行无 start 且无 actual(SRS §5.4/§8.2);未排靠后,number 兜底。纯函数直测(GanttRowOrderTest)。 */
internal fun ganttWalkOrder(
    siblings: List<yangzhou.persistence.Item>,
    childrenOf: (UUID) -> List<yangzhou.persistence.Item>,
    actualStartOf: (yangzhou.persistence.Item) -> LocalDateTime?,
): List<yangzhou.persistence.Item> {
    val memo = HashMap<UUID, LocalDateTime?>()
    fun subtreeMinOf(item: yangzhou.persistence.Item): LocalDateTime? {
        val id = item.objectId!!
        if (memo.containsKey(id)) return memo[id]
        val kids = childrenOf(id)
        val own = if (kids.isEmpty()) {
            listOfNotNull(item.startDate ?: item.dueDate, actualStartOf(item))
        } else {
            emptyList() // 父行自有日期与状态史作废(SRS §4.3):包络只由叶节点贡献
        }
        val a = (own + kids.mapNotNull { c -> subtreeMinOf(c) }).minOrNull()
        memo[id] = a
        return a
    }
    siblings.forEach { subtreeMinOf(it) }
    fun isUnscheduled(item: yangzhou.persistence.Item) = if (childrenOf(item.objectId!!).isEmpty()) {
        item.startDate == null && actualStartOf(item) == null
    } else {
        memo[item.objectId!!] == null
    }
    fun anchorOf(item: yangzhou.persistence.Item) = if (childrenOf(item.objectId!!).isEmpty()) {
        item.startDate ?: actualStartOf(item)
    } else {
        memo[item.objectId!!]
    }
    return siblings.sortedWith(
        compareBy(
            { isUnscheduled(it) }, // 未排期靠后
            { anchorOf(it) ?: LocalDateTime.MAX }, // 锚升序
            { it.number }, // 稳定兜底:同锚 / 未排期组内按创建序
        ),
    )
}

/** Project = 编号(key-N)+ workflow + 视图容器(ADR-0001)。 */
@Service
class ProjectService(
    private val leaderGuard: LeaderGuard,
    private val projects: ProjectRepository,
    private val statuses: StatusRepository,
    private val workflowRules: ProjectWorkflowRuleRepository,
    private val items: yangzhou.persistence.repository.ItemRepository,
    private val workspaceService: WorkspaceService,
    private val deletions: ProjectDeletionRepository,
    private val members: yangzhou.api.business.member.MemberService,
    private val projectMembers: yangzhou.persistence.repository.ProjectMemberRepository,
    private val groupTypes: yangzhou.persistence.repository.ItemGroupTypeRepository,
    private val itemTypes: yangzhou.persistence.repository.ItemTypeRepository,
    private val groupWorkflow: yangzhou.api.business.itemgroup.GroupWorkflowService,
    // spec 0141(YPJ-220):范围过滤——board/group 复用 ItemGroupService 语义,milestone/release 直取挂接表
    private val itemGroupService: yangzhou.api.business.itemgroup.ItemGroupService,
    private val milestones: yangzhou.persistence.repository.MilestoneRepository,
    private val milestoneItems: yangzhou.persistence.repository.MilestoneItemRepository,
    private val releases: yangzhou.persistence.repository.ReleaseRepository,
    private val releaseItems: yangzhou.persistence.repository.ReleaseItemRepository,
    private val dependencyService: yangzhou.api.business.dependency.DependencyService,
    // YPJ-222(spec 0147):实际起止推导 + 清单完成率 + 负责人名
    private val historyRepo: yangzhou.persistence.repository.ItemStatusHistoryRepository,
    private val checklists: yangzhou.persistence.repository.ChecklistItemRepository,
    private val memberRepo: yangzhou.persistence.repository.MemberRepository,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(ProjectService::class.java)

    data class StatusDto(val statusId: UUID, val name: String, val icon: String?, val statusType: StatusType, val position: Int)
    data class ProjectDto(
        val projectId: UUID,
        val key: String,
        val name: String,
        val archived: Boolean,
        val feasSignal: String?,
        val releaseEnabled: Boolean,
        val milestoneEnabled: Boolean,
        /** YPJ-180:工作量刻度(null 存储=内置默认)。 */
        val estimateScales: List<EstimateScaleDto>,
        val estimateRequired: Boolean,
        val estimateScalesIsDefault: Boolean,
        /** YPJ-208(spec 0138 切片 b):工作日历(HH:mm)。 */
        val workdayStart: String,
        val workdayEnd: String,
        val statuses: List<StatusDto>,
    )

    @Transactional
    fun create(key: String, name: String): ProjectDto {
        if (projects.existsByKey(key)) throw ConflictException("项目 key 已存在:$key")
        val workspaceObjectId = workspaceService.required().objectId!!
        val project = projects.save(Project(workspaceObjectId = workspaceObjectId, key = key, name = name))
        // 默认 workflow:To Do / In Progress / In Review / QA / Done(final)——模板系统的最小预置(V7 5 列)
        val saved = defaultStatuses(project.objectId!!).map { statuses.save(it) }
        seedDefaultRules(project.objectId!!, saved)
        // 0047:创建者自动入池(池=合法指派范围,保证创建者至少能给自己指派)
        // 0047+R1(spec 0081):创建者自动入池,且为项目 leader
        projectMembers.save(
            yangzhou.persistence.ProjectMember(
                projectObjectId = project.objectId!!, memberObjectId = members.current().objectId!!, role = yangzhou.persistence.ProjectMember.ROLE_LEADER,
            ),
        )
        // YPJ-165(spec 0131):不再种子 release 组类型(旧组实现已拆,Release 将以独立实体重建,PRD v1.2);
        // 组结构由用户「加 level」自配
        // YPJ-177(spec 0123):item type 四值全种——初始化占位(修订:builtin 概念移除,可增删改)
        listOf("task" to "Task", "bug" to "Bug", "story" to "Story", "goal" to "Goal").forEach { (k, n) ->
            itemTypes.save(yangzhou.persistence.ItemType(projectObjectId = project.objectId!!, typeKey = k, name = n))
        }
        return get(key)
    }

    fun list(): List<ProjectDto> = projects.findAll().map { it.key }.mapNotNull { key ->
        runCatching { get(key) }
            .onFailure { log.warn("项目组装失败,列表中跳过 key={}", key, it) }
            .getOrNull()
    }

    fun get(key: String): ProjectDto {
        val project = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")
        val statusDtos = statuses.findByProjectObjectIdOrderByPosition(project.objectId!!).map {
            StatusDto(it.objectId, it.name, it.icon, it.statusType, it.position)
        }
        val scales = EstimateScales.parse(mapper, project.estimateScales)
        return ProjectDto(
            project.objectId,
            project.key,
            project.name,
            project.archivedAt != null,
            project.feasSignal,
            project.releaseEnabled,
            project.milestoneEnabled,
            scales,
            project.estimateRequired,
            project.estimateScales == null,
            project.workdayStart,
            project.workdayEnd,
            statusDtos,
        )
    }

    /** YPJ-151:项目设置——release/milestone 启停(null=不改);仅存取,门控在前端。 */
    fun updateSettings(
        key: String,
        request: UpdateProjectSettingsRequest,
    ): ProjectDto {
        val project = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")
        // YPJ-208 切片 b + SRS v4 §3.2:工作日历校验(格式 + start<end;全天窗 00:00–24:00 合法;null=不改)
        val ws = request.workdayStart
        val we = request.workdayEnd
        if (ws != null) runCatching { java.time.LocalTime.parse(ws) }.getOrElse { throw BadRequestException("workdayStart 格式应为 HH:mm:$ws") }
        if (we != null && we.trim() != "24:00") runCatching { java.time.LocalTime.parse(we) }.getOrElse { throw BadRequestException("workdayEnd 格式应为 HH:mm 或 24:00:$we") }
        val newWs = ws ?: project.workdayStart
        val newWe = we ?: project.workdayEnd
        // 分钟数比较:“24:00”=1440(当日终点);两端 00:00 = 全天窗合法,其余仍须 start<end
        fun endMinutes(s: String): Long = if (s.trim() == "24:00") 1440L else java.time.LocalTime.parse(s).toSecondOfDay() / 60L
        val fullDay = newWs == "00:00" && newWe == "24:00"
        if (!fullDay && java.time.LocalTime.parse(newWs).toSecondOfDay() / 60L >= endMinutes(newWe)) {
            throw BadRequestException("workdayStart 必须早于 workdayEnd($newWs >= $newWe)")
        }
        val saved =
            projects.save(
                project.copy(
                    releaseEnabled = request.releaseEnabled ?: project.releaseEnabled,
                    milestoneEnabled = request.milestoneEnabled ?: project.milestoneEnabled,
                    estimateScales = if (request.estimateScales != null) {
                        EstimateScales.validate(request.estimateScales)
                        EstimateScales.serialize(mapper, request.estimateScales)
                    } else {
                        project.estimateScales
                    },
                    estimateRequired = request.estimateRequired ?: project.estimateRequired,
                    workdayStart = request.workdayStart ?: project.workdayStart,
                    workdayEnd = request.workdayEnd ?: project.workdayEnd,
                ),
            )
        return get(saved.key)
    }

    /** YPJ-60:删除预览——将随项目物理删除的数据量(只读,不动库)。 */
    fun deletionPreview(key: String): DeletionPreviewDto {
        val project = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")
        val pid = project.objectId ?: error("no id")
        val r = deletions.deletionPreview(pid)
        return DeletionPreviewDto(
            key = project.key,
            name = project.name,
            itemCount = r.itemCount,
            groupCount = r.groupCount,
            milestoneCount = r.milestoneCount,
            viewCount = r.viewCount,
            statusCount = r.statusCount,
            memberCount = r.memberCount,
        )
    }

    data class DeletionPreviewDto(
        val key: String,
        val name: String,
        val itemCount: Long,
        val groupCount: Long,
        val milestoneCount: Long,
        val viewCount: Long,
        val statusCount: Long,
        val memberCount: Long,
    )

    /** YPJ-60:物理删除项目及全部从属数据(含回收站软删 item);FK 依赖序见 spec 0046。 */
    @Transactional
    fun delete(key: String) {
        leaderGuard.requireLeader(key)
        val project = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")
        val pid = project.objectId ?: error("no id")
        deletions.purgeRequirements(pid)
        deletions.purgeItemGroupMembers(pid)
        // 子表(评论/工时/动态/状态历史/依赖/清单/git_ref/通知/群成员历史)随 item 级联;软删 item 一并物理删
        deletions.purgeItems(pid)
        // milestone 在 sprint/release 同表(item_group)之后清(YPJ-165:milestone_id 列已随旧 release 实现拆除)
        deletions.purgeItemGroups(pid)
        deletions.purgeItemGroupTypes(pid)
        deletions.purgeItemTypes(pid) // YPJ-177:item_type 随项目清(不清则 FK 违例 409)
        deletions.purgeMilestones(pid)
        deletions.purgeStatuses(pid) // transition/workflow_rule 级联
        deletions.purgeProjectViews(pid)
        deletions.clearLastProjectKey(key)
        deletions.deleteProject(pid) // repo/rule/favorite/project_member 级联
    }

    /** V8-S2 甘特数据:全量 item 按树序(父先子后,number 次序);日期可空;overdue 同看板语义。
     * spec 0141(YPJ-220):范围过滤,缺省 board(⚠ 契约变更,原缺省=全部);树形 = 命中 ∪ 后代 ∪ 祖先链(不截断)。 */
    fun gantt(
        key: String,
        scope: String = "board",
        scopeIds: String? = null,
    ): GanttDto {
        val project = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")
        val projectId = project.objectId ?: error("no id")
        val all = items.findByProjectObjectIdAndDeletedAtIsNullOrderByNumber(projectId)
        val matched = resolveScope(key, projectId, scope, scopeIds, all)
        val blocked = dependencyService.blockedItemObjectIds(projectId) // YPJ-204(spec 0137):甘特行 blocked 标记
        val anchors = dependencyService.predecessorAnchors(projectId) // YPJ-243(spec 0150):依赖锚点(前端点定位,YPJ-244)
        val byId = all.associateBy { it.objectId }
        val statusById = statuses.findByProjectObjectIdOrderByPosition(projectId).associateBy { it.objectId }
        val now = LocalDateTime.now() // YPJ-208:时刻口径(spec 0138;甘特无 Clock seam,页面级展示容忍)

        // YPJ-222(spec 0147):实际起止 = status_history 首迁非 START / 首迁 FINAL;清单完成率;负责人名(均批量)
        val startIds = statusById.values.filter { it.statusType == StatusType.START }.mapNotNull { it.objectId }.toSet()
        val finalIds = statusById.values.filter { it.statusType == StatusType.FINAL }.mapNotNull { it.objectId }.toSet()
        val histories = historyRepo.forProject(projectId).groupBy { it.itemObjectId }
        val checklistCounts = checklists.countByProject(projectId).associateBy { it.itemId }
        val assigneeIds = all.mapNotNull { it.assigneeObjectId }.toSet()
        val assigneeNames =
            if (assigneeIds.isEmpty()) {
                emptyMap()
            } else {
                memberRepo.findAllByObjectIdIn(assigneeIds).associateBy({ it.objectId }, { it.displayName })
            }

        // YPJ-218(spec 0139)B1:孤儿兑底——父已删/不存在的 item 归入根组,不消失
        val effectiveParent = { item: yangzhou.persistence.Item ->
            item.parentObjectId?.takeIf { byId.containsKey(it) }
        }
        val byParent = all.groupBy { effectiveParent(it) }

        // spec 0141:keep = 命中 ∪ 全部后代 ∪ 祖先链(祖先不被过滤截断)
        val keep = mutableSetOf<UUID>()
        val stack = ArrayDeque(all.filter { it.objectId in matched }.map { it.objectId })
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!keep.add(id)) continue
            byParent[id]?.forEach { stack.addLast(it.objectId) }
        }
        all.filter { it.objectId in keep }.forEach { item ->
            var cursor = effectiveParent(item)
            while (cursor != null && cursor !in keep) {
                keep.add(cursor)
                cursor = effectiveParent(byId[cursor] ?: break)
            }
        }

        fun depthOf(item: yangzhou.persistence.Item): Int {
            var depth = 0
            var cursor = effectiveParent(item)
            while (cursor != null) {
                depth++
                cursor = effectiveParent(byId[cursor] ?: return depth)
            }
            return depth
        }

        fun walk(parent: UUID?): List<GanttRow> = ganttWalkOrder(byParent[parent].orEmpty(), { id -> byParent[id].orEmpty() }) { item ->
                // 状态史 createdAt 为 Instant,排序锚取本地墙钟(与前端渲染同口径)
                histories[item.objectId]?.firstOrNull { h -> h.toStatusObjectId !in startIds }?.createdAt
                    ?.let { LocalDateTime.ofInstant(it, java.time.ZoneId.systemDefault()) }
            }.flatMap { item ->
            val status = statusById[item.statusObjectId]
            val hist = histories[item.objectId!!].orEmpty()
            val cc = checklistCounts[item.objectId!!]
            val row = GanttRow(
                itemId = item.objectId,
                parentItemId = item.parentObjectId,
                number = "${project.key}-${item.number}",
                title = item.title,
                startDate = item.startDate?.toString(),
                dueDate = item.dueDate?.toString(),
                statusName = status?.name ?: "?",
                statusType = status?.statusType?.name, // YPJ-218(spec 0139):FINAL 置灰判定
                overdue = item.dueDate?.let { now > it } == true && status?.statusType != StatusType.FINAL,
                blocked = item.objectId in blocked,
                estimateValue = item.estimateValue,
                estimateUnit = item.estimateUnit,
                depth = depthOf(item),
                actualStart = hist.firstOrNull { it.toStatusObjectId !in startIds }?.createdAt?.toString(),
                actualEnd = hist.firstOrNull { it.toStatusObjectId in finalIds }?.createdAt?.toString(),
                assigneeName = item.assigneeObjectId?.let { assigneeNames[it] },
                anchorDate = anchors[item.objectId]?.toString(),
                checklistDone = cc?.done?.toInt(),
                checklistTotal = cc?.total?.toInt(),
            )
            listOf(row) + walk(item.objectId)
        }

        return GanttDto(rows = walk(null).filter { it.itemId in keep })
    }

    /** spec 0141:范围 → 命中 item objectId 集;board 复用看板口径,group 多选并集,milestone/release 取挂接表。 */
    private fun resolveScope(
        key: String,
        projectId: UUID,
        scope: String,
        scopeIds: String?,
        all: List<yangzhou.persistence.Item>,
    ): Set<UUID> {
        val ids = scopeIds
            ?.split(',')
            ?.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
            .orEmpty()
        val uuids = ids.map { id ->
            runCatching { UUID.fromString(id) }.getOrElse { throw BadRequestException("scopeIds 非法 UUID:$id") }
        }
        return when (scope) {
            "all" -> all.map { it.objectId }.toSet()
            "board" -> itemGroupService.boardItemObjectIds(key)
            "group" -> {
                if (uuids.isEmpty()) throw BadRequestException("scope=group 需 scopeIds")
                itemGroupService.groupMemberItemObjectIds(key, uuids)
            }
            "milestone" -> {
                if (uuids.size != 1) throw BadRequestException("scope=milestone 需恰一个 scopeIds")
                val m = milestones.findByProjectObjectIdAndObjectId(projectId, uuids[0])
                    ?: throw NotFoundException("milestone 不存在")
                milestoneItems.findByMilestoneObjectId(m.objectId).map { it.itemObjectId }.toSet()
            }
            "release" -> {
                if (uuids.size != 1) throw BadRequestException("scope=release 需恰一个 scopeIds")
                val r = releases.findByProjectObjectIdAndObjectId(projectId, uuids[0])
                    ?: throw NotFoundException("release 不存在")
                releaseItems.findByReleaseObjectId(r.objectId).map { it.itemObjectId }.toSet()
            }
            else -> throw BadRequestException("scope 只能是 board/group/milestone/release/all")
        }
    }

    data class GanttRow(
        val itemId: UUID,
        val parentItemId: UUID?,
        val number: String,
        val title: String,
        val startDate: String?,
        val dueDate: String?,
        val statusName: String,
        val statusType: String?,
        val overdue: Boolean,
        val blocked: Boolean,
        val estimateValue: Double?,
        val estimateUnit: String?,
        val depth: Int,
        // YPJ-222(spec 0147):实际条/偏移量/hover 卡/进度遮罩;Instant 串(UTC),前端 parseLocal 兼容
        val actualStart: String? = null,
        val actualEnd: String? = null,
        val assigneeName: String? = null,
        val anchorDate: String? = null, // YPJ-243(spec 0150):依赖锚点=多前置最晚 due(无 due 退 start);null=无可用前置时间
        val checklistDone: Int? = null,
        val checklistTotal: Int? = null,
    )

    data class GanttDto(val rows: List<GanttRow>)

    /** YPJ-40 方案 A:默认规则按语义推导(branch→首个非 start、pr_open→次个非 start(无则不种=人管)、pr_merged→首个 final、unmerged 恒不种);标准 V7 五列下与旧按名映射结果一致。 */
    private fun seedDefaultRules(projectId: UUID, saved: List<Status>) {
        defaultRuleSeeds(saved).forEach { (event, status) ->
            workflowRules.save(ProjectWorkflowRule(projectObjectId = projectId, eventType = event.wire, statusObjectId = status.objectId))
        }
    }

    private fun defaultStatuses(projectId: UUID) = listOf( // V7:5 列(评审环节天然有位置);存量项目不动
        Status(projectObjectId = projectId, name = "To Do", statusType = StatusType.START, position = 0),
        Status(projectObjectId = projectId, name = "In Progress", statusType = StatusType.ACTIVE, position = 1), // YPJ-120→spec 0097:进行态 = ACTIVE 类
        Status(projectObjectId = projectId, name = "In Review", position = 2),
        Status(projectObjectId = projectId, name = "QA", position = 3),
        Status(projectObjectId = projectId, name = "Done", statusType = StatusType.FINAL, position = 4),
    )

    companion object {
        /** YPJ-40 方案 A 的推导核心(纯函数,单测直测):branch→首个非 start、pr_open→次个非 start(无则不种=人管)、pr_merged→首个 final;缺失即不种,非标 workflow 开箱有定义。 */
        fun defaultRuleSeeds(statuses: List<Status>): List<Pair<VcsEvent, Status>> = buildList {
            val nonStart = statuses.filter { it.statusType != StatusType.START }
            nonStart.firstOrNull()?.let { add(VcsEvent.BRANCH_CREATED to it) }
            nonStart.getOrNull(1)?.let { add(VcsEvent.PR_OPENED to it) }
            statuses.firstOrNull { it.statusType == StatusType.FINAL }?.let { add(VcsEvent.PR_MERGED to it) }
        }
    }
}

data class CreateProjectRequest(
    @field:NotBlank
    @field:Pattern(regexp = "^[A-Z][A-Z0-9]{1,9}$", message = "key 为 2–10 位大写字母/数字,如 CHE")
    val key: String,
    @field:NotBlank val name: String,
)

data class UpdateProjectSettingsRequest(
    val releaseEnabled: Boolean? = null,
    val milestoneEnabled: Boolean? = null,
    /** YPJ-180:null=不改;传数组即整体替换(恢复默认=传默认五档)。 */
    val estimateScales: List<EstimateScaleDto>? = null,
    val estimateRequired: Boolean? = null,
    /** YPJ-208(spec 0138 切片 b):工作日历(HH:mm;null=不改)。 */
    val workdayStart: String? = null,
    val workdayEnd: String? = null,
)
