package yangzhou.api.business.item

import jakarta.validation.constraints.NotBlank
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.business.workday.WorkdayCalculator
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Item
import yangzhou.persistence.ItemActivity
import yangzhou.persistence.ItemGroupMember
import yangzhou.persistence.ItemStatusHistory
import yangzhou.persistence.Requirement
import yangzhou.persistence.StatusType
import yangzhou.persistence.repository.AttributeDefinitionRepository
import yangzhou.persistence.repository.ItemGitRefRepository
import yangzhou.persistence.repository.ItemGroupMemberHistoryRepository
import yangzhou.persistence.repository.ItemGroupMemberRepository
import yangzhou.persistence.repository.ItemGroupRepository
import yangzhou.persistence.repository.ItemGroupTypeRepository
import yangzhou.persistence.repository.ItemNumberRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ItemTreeRepository
import yangzhou.persistence.repository.ItemTypeRepository
import yangzhou.persistence.repository.MilestoneItemRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.RequirementRepository
import yangzhou.persistence.repository.StatusRepository
import yangzhou.persistence.repository.TimeEntryRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/** Item:项目内递增编号(key-N)、type 属性、parentObjectId 同质树、自由迁移(ADR-0002)。 */
@Service
class ItemService(
    private val projects: ProjectRepository,
    private val itemRepo: ItemRepository,
    private val statuses: StatusRepository,
    private val requirementRepo: RequirementRepository,
    private val itemAttributes: yangzhou.persistence.repository.ItemAttributeRepository,
    private val definitions: AttributeDefinitionRepository,
    private val numbering: ItemNumberRepository,
    private val itemTree: ItemTreeRepository,
    private val itemTypes: ItemTypeRepository,
    private val workflow: yangzhou.api.business.workflow.WorkflowService,
    private val members: yangzhou.persistence.repository.MemberRepository,
    private val memberService: yangzhou.api.business.member.MemberService,
    private val projectMembers: yangzhou.api.business.projectmember.ProjectMemberService,
    private val activityRepo: yangzhou.persistence.repository.ItemActivityRepository,
    private val historyRepo: yangzhou.persistence.repository.ItemStatusHistoryRepository,
    private val gitRefs: ItemGitRefRepository,
    private val feasibilityService: yangzhou.api.business.feasibility.FeasibilityService,
    private val dependencyService: yangzhou.api.business.dependency.DependencyService,
    private val dependencies: yangzhou.persistence.repository.ItemDependencyRepository,
    private val groups: ItemGroupRepository,
    private val groupTypes: ItemGroupTypeRepository,
    private val memberships: ItemGroupMemberRepository,
    private val notificationService: yangzhou.api.business.notification.NotificationService,
    private val memberHistory: yangzhou.persistence.repository.ItemGroupMemberHistoryRepository,
    private val milestoneItems: MilestoneItemRepository,
    private val itemFinalize: ItemFinalizeService,
    private val timeEntries: yangzhou.persistence.repository.TimeEntryRepository,
    private val clock: Clock,
) {
    data class RequirementDto(
        val attribute: String,
        val minLevel: Int?,
    )

    data class ItemAttributeDto(
        val attribute: String,
        val value: String,
    )

    data class GitRefDto(
        val kind: String,
        val repo: String,
        val ref: String,
        val url: String?,
        val state: String?,
    )

    data class ItemDto(
        val itemId: UUID,
        val number: String,
        val title: String,
        val description: String?,
        val type: String,
        val status: String,
        val assignee: String?,
        val parentItemId: UUID?,
        val parentNumber: String? = null,
        val externalRef: String?,
        val requirements: List<RequirementDto>,
        val attributes: List<ItemAttributeDto> = emptyList(),
        val gitRefs: List<GitRefDto> = emptyList(),
        val assigneeColor: String? = null,
        val dueSoon: Boolean = false,
        val startDate: String? = null,
        val dueDate: String? = null,
        val overdue: Boolean = false,
        val feasSignal: String? = null,
        val blocked: Boolean = false,
        val priority: String? = null,
        val estimateValue: Double? = null, // YPJ-186(spec 0128):预估快照
        val estimateUnit: String? = null,
        val createdAt: String? = null,
        val sprintId: UUID? = null,
        val sprintName: String? = null,
        val onBoard: Boolean = false,
        val autoAssignedSprintName: String? = null, // YPJ-120 仅 PATCH 触发自动挂载时非空
        val warnings: List<String> = emptyList(), // YPJ-243(spec 0150):互算警告,仅 PATCH 响应携带
        /** 票 2(US-M7):gate 多归属回显,仅详情回填(列表批量代价高)。 */
        val milestoneIds: List<UUID> = emptyList(),
        /** YPJ-182(spec 0130):时间合计(分钟),仅列表填充;口径同 minutesOf。 */
        val totalMinutes: Long = 0,
    )

    @Transactional
    fun create(
        projectKey: String,
        title: String,
        description: String?,
        type: String,
        parentItemId: UUID?,
        statusItemId: UUID?,
        requirements: List<RequirementDto>,
        externalRef: String? = null,
        sprintId: UUID? = null,
        estimateValue: String? = null,
        estimateUnit: String? = null,
    ): ItemDto {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        // YPJ-186(spec 0128):预估解析先于取号锁(fail fast);required=立票必须非空
        val estimate = EstimateInput.parse(estimateValue, estimateUnit)
        if (project.estimateRequired && (estimate == null || estimate.first == null)) {
            throw BadRequestException("本项目立票必须填预估")
        }
        // YPJ-177(spec 0123):type 校验改查项目级定义(白名单退役,CK 已放开)
        if (itemTypes.findByProjectObjectIdAndTypeKey(project.objectId!!, type) == null) {
            throw BadRequestException("type 非法:$type")
        }
        val projectObjectId = project.objectId!!

        // YPJ-128:nextNumber 的 UPDATE project 即本事务第一把锁——锁序统一 project→item(spec 0096)
        val number = numbering.nextNumber(projectObjectId) // 原子取号,行锁防并发重号

        val status =
            when {
                statusItemId != null ->
                    statuses.findByProjectObjectIdAndObjectId(projectObjectId, statusItemId)
                        ?: throw BadRequestException("状态不属于该项目")
                else ->
                    statuses
                        .findByProjectObjectIdOrderByPosition(projectObjectId)
                        .let { list -> list.filter { it.statusType == StatusType.START }.minByOrNull { it.position } ?: list.firstOrNull() }
                        ?: throw ConflictException("项目无状态")
            }

        parentItemId?.let {
            val parent = itemRepo.findByObjectId(it) ?: throw BadRequestException("父 item 不存在")
            if (parent.projectObjectId != project.objectId) throw BadRequestException("父 item 不属于该项目")
        }

        val item =
            itemRepo.save(
                Item(
                    projectObjectId = projectObjectId,
                    number = number,
                    title = title,
                    description = description,
                    type = type,
                    parentObjectId = parentItemId,
                    externalRef = externalRef,
                    statusObjectId = status.objectId,
                    estimateValue = estimate?.first,
                    estimateUnit = estimate?.second,
                    createdByObjectId = memberService.current().objectId!!,
                ),
            )
        val itemId = item.objectId!!
        resolveRequirements(project.workspaceObjectId, requirements).forEach { (defId, minLevel) ->
            requirementRepo.save(Requirement(itemObjectId = itemId, attributeDefinitionObjectId = defId, minLevel = minLevel))
        }
        logActivity(itemId, "created", null, title, memberService.current().objectId!!)
        historyRepo.save(
            ItemStatusHistory(
                itemObjectId = itemId,
                toStatusObjectId = status.objectId!!,
                source = "created",
                actorMemberObjectId = memberService.current().objectId!!,
            ),
        )
        feasibilityService.recomputeProjectSignal(projectObjectId) // V9-S1
        if (sprintId != null) { // YPJ-56:创建时初始归属,校验同 assignItem(sprint 归属该项目;completed 拒);同事务,负例下 item 不落库
            val sprint =
                groups.findByProjectObjectIdAndObjectId(projectObjectId, sprintId)
                    ?.takeIf { it.type == level1Key(projectObjectId) } // spec 0100 切片B:只认 level-1 组
                    ?: throw NotFoundException("sprint 不存在")
            if (sprint.status == "completed") throw ConflictException("sprint 已完成,不能指派")
            memberships.save(ItemGroupMember(groupObjectId = sprint.objectId!!, itemObjectId = itemId))
            memberHistory.recordAdd(sprint.objectId!!, itemId, clock.instant()) // YPJ-54 留痕(同事务,负例不落库语义不变)
            // YPJ-216(spec 0164):timed sprint 拉入排期守卫(同事务,负例不落库)
            if (groupTypes.findByProjectObjectIdAndTypeKey(projectObjectId, sprint.type)?.timed == true) {
                applySprintPullSchedule(item, null, null)
            }
        }
        return get(item.objectId)
    }

    fun list(
        projectKey: String,
        backlogOnly: Boolean = false,
    ): List<ItemDto> {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        val projectItems = itemRepo.findByProjectObjectIdAndDeletedAtIsNullOrderByNumber(project.objectId!!)
        val ctx = buildCtx(project, projectItems)
        val visible =
            if (backlogOnly) {
                projectItems.filter {
                    it.statusObjectId !in ctx.finalIds && it.objectId !in ctx.currentSprint && !it.onBoard
                }
            } else {
                projectItems
            }
        // YPJ-84 决策 2:父前缀标识(同项目全量行内可解析,直接查表)
        val numberById = projectItems.associate { it.objectId to "${project.key}-${it.number}" }
        val attrsByItem = itemAttributes.findByItemObjectIdIn(visible.mapNotNull { it.objectId }).groupBy { it.itemObjectId }
        // YPJ-182(spec 0130):时间合计批量取,内存分组(口径同 TimeEntryService.minutesOf:minutes 优先,否则起止折算,进行中折算到 now)
        val minutesByItem = timeEntries
            .findByItemObjectIdIn(visible.mapNotNull { it.objectId })
            .groupBy { it.itemObjectId }
            .mapValues { (_, rows) -> rows.sumOf { e -> e.minutes?.toLong() ?: java.time.Duration.between(e.startedAt, e.endedAt ?: clock.instant()).toMinutes() } }
        val attrNames =
            definitions.findAllByObjectIdIn(attrsByItem.values.flatten().map { it.attributeDefinitionObjectId }).associate {
                it.objectId!! to
                    it.name
            }
        return visible.map {
            ctx.toDto(it).copy(
                parentNumber = it.parentObjectId?.let(numberById::get),
                totalMinutes = minutesByItem[it.objectId] ?: 0L,
                attributes =
                attrsByItem[it.objectId].orEmpty().map { a ->
                    ItemAttributeDto(attrNames[a.attributeDefinitionObjectId] ?: "?", a.value)
                },
            )
        }
    }

    fun get(itemId: UUID): ItemDto {
        val item = itemRepo.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val project = projectOf(item.projectObjectId)
        val ctx = buildCtx(project, listOf(item))
        // 已软删 item 不在 list()(回收站预览语义):toDto 内加 [已删除] 前缀
        val parentNumber = item.parentObjectId?.let { itemRepo.findByObjectId(it) }?.let { "${project.key}-${it.number}" }
        return ctx.toDto(item).copy(
            parentNumber = parentNumber,
            gitRefs =
            gitRefs.findByItemObjectId(item.objectId!!).map {
                GitRefDto(it.kind, it.repo, it.ref, it.url, it.state)
            },
            attributes = toAttrDtos(itemAttributes.findByItemObjectId(item.objectId!!)),
            milestoneIds = milestoneItems.findByItemObjectId(item.objectId!!).map { it.milestoneObjectId },
        )
    }

    @Transactional
    fun update(
        itemId: UUID,
        title: String?,
        description: String?,
        type: String?,
        statusItemId: UUID?,
        parentItemId: UUID?,
        startDateText: String? = null,
        dueDateText: String? = null,
        priorityText: String? = null,
        estimateValueText: String? = null,
        estimateUnitText: String? = null,
    ): ItemDto {
        val item = itemRepo.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val project = projectOf(item.projectObjectId)
        var current = item

        val projectObjectId = project.objectId ?: error("no id")
        val actorId = memberService.current().objectId!!
        var autoSprintName: String? = null // YPJ-120 自动挂载的 sprint 名;仅本次 PATCH 触发时非空
        if (statusItemId != null) {
            val status =
                statuses.findByProjectObjectIdAndObjectId(projectObjectId, statusItemId)
                    ?: throw BadRequestException("状态不属于该项目")
            val oldStatus =
                statuses
                    .findByProjectObjectIdOrderByPosition(projectObjectId)
                    .firstOrNull { it.objectId == current.statusObjectId }
            if (oldStatus != null) {
                workflow.assertTransitionAllowed(projectObjectId, oldStatus.objectId!!, status.objectId!!)
            }
            // 开工须有主:离开起点状态前必须已指派(V4-S3)
            if (current.assigneeObjectId == null && oldStatus?.statusType == StatusType.START && status.statusType != StatusType.START) {
                throw ConflictException("开工前请先指派负责人")
            }
            // WIP 限制:目标列已有 item 数 >= 上限 → 409(V5-W1)
            val limit = status.wipLimit
            if (limit != null && limit > 0) {
                val wipCount = itemRepo.findByProjectObjectIdAndStatusObjectId(projectObjectId, status.objectId).size
                if (wipCount >= limit) throw ConflictException("该列 WIP 已满:$wipCount/$limit")
            }
            current = itemRepo.save(current.copy(statusObjectId = status.objectId))
            logActivity(current.objectId!!, "status_changed", oldStatus?.name, status.name, actorId)
            if (status.objectId != oldStatus?.objectId) {
                historyRepo.save(
                    ItemStatusHistory(
                        itemObjectId = current.objectId!!,
                        fromStatusObjectId = oldStatus?.objectId,
                        toStatusObjectId = status.objectId!!,
                        source = "manual",
                        actorMemberObjectId = actorId,
                    ),
                )
            }
            notificationService.notifyStatusChange(current, actorId, oldStatus?.name, status.name)
            // YPJ-242(spec 0162):FINAL 冻结/rollup 排除靠迁移后重算生效;批量后亚秒级
            feasibilityService.recomputeProjectSignal(projectObjectId)
            // YPJ-120(spec 0089):开始动作自动入当前 sprint——离开 start 态、目标为进行态、且无 sprint 时,
            // 挂到最早创建的 active sprint(与 board activeSprints[0] 同序);无 active/已有 sprint 静默跳过。
            if (oldStatus?.statusType == StatusType.START && status.statusType == StatusType.ACTIVE) {
                val rows = memberships.findByItemObjectIdIn(listOf(current.objectId!!))
                val rowGroups = if (rows.isEmpty()) {
                    emptyMap()
                } else {
                    groups.findAllByObjectIdIn(rows.map { it.groupObjectId }.toSet()).associateBy { it.objectId!! }
                }
                // spec 0097 泄漏 4 收口:只认 sprint 归属为 live(release/自定义组不再抑制自动挂载)
                val hasLive = rows.any { rowGroups[it.groupObjectId]?.let { g -> g.status != "completed" && g.type == level1Key(projectObjectId) } == true }
                if (!hasLive) {
                    val first = groups.findByProjectObjectIdOrderByCreatedAt(projectObjectId)
                        .firstOrNull { it.type == level1Key(projectObjectId) && it.status == "active" } // spec 0100 切片B:自动挂载只挂 level-1
                    if (first != null) { // 同 create 初始归属模式(YPJ-56):save + 留痕,同事务
                        memberships.save(ItemGroupMember(groupObjectId = first.objectId!!, itemObjectId = current.objectId!!))
                        memberHistory.recordAdd(first.objectId!!, current.objectId!!, clock.instant())
                        autoSprintName = first.name
                    }
                }
            }
            // YPJ-184(spec 0125):首次进 FINAL 触发统一后置动作(快照等;best-effort 不阻塞);
            // 重开再完成会再次快照(upsert 幂等,以最后一次 Done 为准)
            if (status.statusType == StatusType.FINAL && oldStatus?.statusType != StatusType.FINAL) {
                itemFinalize.onFinalized(current.objectId!!)
            }
        }

        if (parentItemId != null) {
            val parent = itemRepo.findByObjectId(parentItemId) ?: throw BadRequestException("父 item 不存在")
            if (parent.projectObjectId != project.objectId) throw BadRequestException("父 item 不属于该项目")
            ensureNoCycle(current, parent)
            current = itemRepo.save(current.copy(parentObjectId = parent.objectId))
        }

        if (type != null && type != current.type) {
            // YPJ-177(spec 0123):同值跳过不变;异值查项目级定义
            if (itemTypes.findByProjectObjectIdAndTypeKey(current.projectObjectId, type) == null) {
                throw BadRequestException("type 非法:$type")
            }
            current = itemRepo.save(current.copy(type = type))
        }
        if (title != null && title != current.title) {
            logActivity(current.objectId!!, "title_changed", current.title, title, actorId)
            current = itemRepo.save(current.copy(title = title))
        }
        if (description != null && description != current.description) {
            logActivity(current.objectId!!, "description_changed", current.description, description, actorId)
            current = itemRepo.save(current.copy(description = description))
        }
        // V10-S2 优先级:null = 不变;空串 = 清除;P0-P3 = 设置
        if (priorityText != null) {
            val p = priorityText.trim().ifEmpty { null }
            if (p != null && p !in setOf("P0", "P1", "P2", "P3")) {
                throw BadRequestException("priority 只能是 P0–P3:\$p")
            }
            if (p != current.priority) {
                current = itemRepo.save(current.copy(priority = p))
            }
        }

        // YPJ-186(spec 0128):预估三态合并(required 项目禁显式清除;同值跳过不记日志)
        val withEstimate = applyEstimate(current, estimateValueText, estimateUnitText, project.estimateRequired)
        val estimateChanged = withEstimate !== current
        if (estimateChanged) {
            val old = estimateText(current.estimateValue, current.estimateUnit)
            current = itemRepo.save(withEstimate)
            logActivity(current.objectId!!, "estimate_changed", old, estimateText(current.estimateValue, current.estimateUnit), actorId)
        }

        // V8-S1 日期:参数 null = 不变;空串 = 清除;ISO 文本 = 设置。start<=due 由 DB CHECK + 此处双保险
        val warnings = mutableListOf<String>() // YPJ-243(spec 0150):互算警告,仅本次 PATCH 响应携带
        if (startDateText != null || dueDateText != null || estimateChanged) {
            // YPJ-208(spec 0138 切片 b):date-only → 项目工作日界默认时刻;datetime 原样
            val (ws, we) = workdayBounds(project)

            // YPJ-226(spec 0163):工作制 = 跳周末;墙钟制 = 周六日照算(原行为)
            fun parse(
                text: String?,
                field: String,
                defaultTime: LocalTime,
            ): LocalDateTime? = text?.trim()?.ifEmpty { null }?.let { raw ->
                runCatching { LocalDateTime.parse(raw) }
                    .recoverCatching { LocalDate.parse(raw).atTime(defaultTime) }
                    .getOrElse { throw BadRequestException("$field 格式应为 yyyy-MM-dd 或 yyyy-MM-dd'T'HH:mm:$it") }
            }
            val newStart = parse(startDateText, "startDate", ws)
            val dueCleared = dueDateText != null && dueDateText.trim().isEmpty()
            val dueExplicit = dueDateText != null && !dueCleared
            // date-only due 默认时刻 = 工作窗终点;全天窗取当日 23:59:59(秒精度日末)——
            // LocalTime.MAX 是纳秒精度,Postgres timestamp(6) 微秒入库四舍五入进位成次日 00:00(2026-11-21 实锤);
            // 截止=当日日末,避免"当天到期即逾期"伪问题
            val dueDefaultTime = if (we == LocalTime.MIDNIGHT) LocalTime.of(23, 59, 59) else we
            val newDueExplicit = parse(dueDateText, "dueDate", dueDefaultTime)
            var due = when {
                dueDateText == null -> current.dueDate
                dueCleared -> null
                else -> newDueExplicit
            }
            val start = newStart ?: current.startDate
            val estHours = estimateHours(current.estimateValue, current.estimateUnit, ws, we)
            val calc = WorkdayCalculator(ws, we, skipWeekends = project.workMode == "work")
            // YPJ-243(spec 0150)双向互算·后写者为准(真值表见 spec §2.1;推翻 0146 两处,用户 2026-11-20 拍板)
            when {
                // estimate 后写(显式改预估且变化;due 未显式携带)→ due 按预估重算;无 start 只警告
                estimateChanged && estHours != null && dueDateText == null -> {
                    if (start != null) {
                        val recalc = calc.dueFrom(start, estHours)
                        if (recalc != due) {
                            due = recalc
                            warnings += W_DUE_FROM_ESTIMATE
                        }
                    } else {
                        warnings += W_NO_START_ESTIMATE
                    }
                }
                // due 显式清除且预估有值 → 铺算回填(H2,推翻 0146「清除不回填」)
                dueCleared && estHours != null && start != null -> {
                    due = calc.dueFrom(start, estHours)
                    warnings += W_DUE_CLEAR_BACKFILL
                }
                // due 后写(显式改 due 且变化;start 未同发——start+due 同发=排期窗口,双存不动 estimate;预估本次未变)
                // → estimate 按起止反推(H3 已拍板:无预估填/有预估改)
                dueExplicit && newStart == null && due != current.dueDate && !estimateChanged -> {
                    if (start != null) {
                        val h = calc.hoursBetween(start, due!!)
                        if (h > 0) {
                            var value = h
                            if (value > 999.0) {
                                value = 999.0
                                warnings += W_EST_CLAMP
                            }
                            if (current.estimateValue != value || current.estimateUnit != "hour") {
                                val old = estimateText(current.estimateValue, current.estimateUnit)
                                current = itemRepo.save(current.copy(estimateValue = value, estimateUnit = "hour"))
                                logActivity(current.objectId!!, "estimate_changed", old, estimateText(value, "hour"), actorId)
                                warnings += String.format(W_EST_FROM_DATES, estimateText(value, "hour"))
                            }
                        }
                    }
                    // start == null 已被上方 §12.5 400 拦截(YPJ-245),W_NO_START_DUE 死代码删除
                }
                // 双显式(H1):due+estimate 同请求都变 → 双存不互算;冲突仅警告(后写者为准由前端发送顺序保证)
                estimateChanged && dueExplicit && start != null && estHours != null -> {
                    val h = calc.hoursBetween(start, due!!)
                    if (kotlin.math.abs(h - estHours) > 0.01) warnings += W_CONFLICT
                }
                // 0146 既有:start 显式、due 未携带、预估未变 → due 按存量预估铺算(果随因)
                newStart != null && dueDateText == null && estHours != null && !estimateChanged -> {
                    val recalc = calc.dueFrom(newStart, estHours)
                    if (recalc != due) due = recalc
                }
            }
            // SRS v4 §12.5(YPJ-245):due-only 废除——**结果态** due 有而 start 无 → 400
            // 结果态 start:未携带 = 保持存量(探针实锤:旧码 copy(startDate=newStart) 会把存量 start 抹成 null,
            // due-only PATCH 破坏数据,2026-11-21 修复);显式空串 = 清除;显式日期 = 新值
            val resultStart = if (startDateText == null) current.startDate else newStart
            if (due != null && resultStart == null) throw BadRequestException("须先填开始日期")
            if (start != null && due != null && start > due) throw BadRequestException("开始日期不能晚于截止日期")
            if (resultStart != current.startDate || due != current.dueDate) {
                val old = "${current.startDate ?: "—"} ~ ${current.dueDate ?: "—"}"
                current = itemRepo.save(current.copy(startDate = resultStart, dueDate = due))
                logActivity(current.objectId!!, "dates_changed", old, "${resultStart ?: "—"} ~ ${due ?: "—"}", actorId)
            }
        }
        return get(current.objectId).copy(autoAssignedSprintName = autoSprintName, warnings = warnings) // YPJ-120
    }

    /** 需求整表替换(同 transitions 语义);供 item 详情编辑用。 */
    @Transactional
    fun replaceRequirements(
        itemId: UUID,
        requirements: List<RequirementDto>,
    ): ItemDto {
        val item = itemRepo.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val project = projectOf(item.projectObjectId)
        projects.lockByObjectId(item.projectObjectId) // YPJ-128 统一锁序:第一把锁必须是 project 行(spec 0096)
        val rowId = item.objectId!!
        requirementRepo.deleteByItemObjectId(rowId)
        resolveRequirements(project.workspaceObjectId, requirements).forEach { (defId, minLevel) ->
            requirementRepo.save(Requirement(itemObjectId = rowId, attributeDefinitionObjectId = defId, minLevel = minLevel))
        }
        val summary = requirements.joinToString(";") { r -> r.attribute + (r.minLevel?.let { ">=" + it } ?: "") }
        logActivity(rowId, "requirement_changed", null, summary.ifEmpty { "(清空)" }, actorId())
        // YPJ-26:不单独先锁本 item 行——与并发重算的编号序扫描成环(40P01);信号由下方按编号序的扫全量覆盖
        feasibilityService.recomputeProjectSignal(project.objectId!!) // V9-S1
        return get(item.objectId)
    }

    /** item 自定义属性整表替换(spec 0082);仅 custom 词表;描述性,永不进匹配引擎。 */
    @Transactional
    fun replaceAttributes(
        itemId: UUID,
        values: List<ItemAttributeDto>,
    ): ItemDto {
        val item = itemRepo.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val project = projectOf(item.projectObjectId)
        val rowId = item.objectId!!
        val resolved = resolveAttributes(project.workspaceObjectId, values)
        itemAttributes.deleteByItemObjectId(rowId)
        resolved.forEach { (defId, value) ->
            itemAttributes.save(
                yangzhou.persistence.ItemAttribute(itemObjectId = rowId, attributeDefinitionObjectId = defId, value = value),
            )
        }
        val summary = values.joinToString(";") { it.attribute + "=" + it.value }
        logActivity(rowId, "attribute_changed", null, summary.ifEmpty { "(清空)" }, actorId())
        return get(item.objectId)
    }

    private fun toAttrDtos(rows: List<yangzhou.persistence.ItemAttribute>): List<ItemAttributeDto> {
        if (rows.isEmpty()) return emptyList()
        val names = definitions.findAllByObjectIdIn(rows.map { it.attributeDefinitionObjectId }).associate { it.objectId!! to it.name }
        return rows.map { ItemAttributeDto(names[it.attributeDefinitionObjectId] ?: "?", it.value) }
    }

    private fun resolveAttributes(
        workspaceObjectId: UUID,
        values: List<ItemAttributeDto>,
    ): List<Pair<UUID, String>> {
        val seen = HashSet<String>()
        return values.map { v ->
            val name = v.attribute.trim()
            if (!seen.add(name)) throw BadRequestException("item 属性重复:$name")
            val value = v.value.trim()
            if (value.isEmpty()) throw BadRequestException("属性值不能为空:$name")
            if (value.length > 500) throw BadRequestException("属性值超长(≤500):$name")
            val def =
                definitions.findByWorkspaceObjectIdAndName(workspaceObjectId, name)
                    ?: throw NotFoundException("词表中没有属性:$name(先建 custom 类属性)")
            if (def.kind != "custom") throw BadRequestException("仅 custom 类词表可挂 item 属性:${def.name}")
            def.objectId!! to value
        }
    }

    /** 指派/取消(assigneeItemObjectId=null 取消);成员不存在 404;池外指派 409(0047:空池=无人可指派)。 */
    @Transactional
    fun assign(
        itemId: UUID,
        assigneeItemObjectId: UUID?,
    ): ItemDto {
        val item = itemRepo.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val actorId = memberService.current().objectId!!
        val oldName = item.assigneeObjectId?.let { oid -> members.findByObjectId(oid)?.displayName }
        if (assigneeItemObjectId != null) {
            val member = members.findByObjectId(assigneeItemObjectId) ?: throw NotFoundException("成员不存在")
            projectMembers.assertAssignable(item.projectObjectId, member.objectId!!)
            itemRepo.save(item.copy(assigneeObjectId = assigneeItemObjectId))
            logActivity(item.objectId!!, "assigned", oldName, member.displayName, actorId)
            notificationService.notifyAssigned(item, actorId, member.objectId!!, member.displayName)
        } else {
            itemRepo.save(item.copy(assigneeObjectId = null))
            logActivity(item.objectId!!, "unassigned", oldName, null, actorId)
        }
        return get(item.objectId)
    }

    /** YPJ-216(spec 0164):sprint 拉入排期守卫——三条拉入路径(create 直挂 / replaceMembership / boardMembership)共用。
     *  真值表:无预估 409;start 决策序 = 显式 > 存量 > 前置可推(最晚前置 due+1 顺延工作时刻)> 今天;前置全未排 409;
     *  due = WorkdayCalculator 铺算(显式/存量 due 覆盖,不反推 estimate)。item 已有 start 视为已显式,保留不重排。 */
    fun applySprintPullSchedule(
        item: Item,
        explicitStart: LocalDateTime? = null,
        explicitDue: LocalDateTime? = null,
    ): Item {
        if (item.estimateValue == null || item.estimateUnit == null) {
            throw ConflictException("拉入 sprint 须先填预估时长(estimateValue + estimateUnit)")
        }
        val project = projects.findByObjectId(item.projectObjectId) ?: return item
        val (ws, we) = workdayBounds(project)
        val workMode = project.workMode == "work"
        val calc = WorkdayCalculator(ws, we, skipWeekends = workMode)
        val estHours = estimateHours(item.estimateValue, item.estimateUnit, ws, we) ?: return item
        val start: LocalDateTime =
            explicitStart
                ?: item.startDate
                ?: run {
                    val preds =
                        dependencies
                            .findByItemObjectId(item.objectId!!)
                            .mapNotNull { itemRepo.findByObjectId(it.dependsOnItemObjectId) }
                            .filter { it.deletedAt == null }
                    if (preds.isEmpty()) {
                        todayStart(ws, we, workMode)
                    } else {
                        val scheduled = preds.mapNotNull { it.dueDate }
                        if (scheduled.isEmpty()) {
                            throw ConflictException("前置依赖(${preds.size} 条)均未排期,无法推导开始日期;请先排前置或显式给开始日期")
                        }
                        nextWorkMoment(scheduled.max()!!.plusMinutes(1), ws, we, workMode)
                    }
                }
        val due = explicitDue ?: item.dueDate ?: calc.dueFrom(start, estHours)
        if (start > due) throw BadRequestException("开始日期不能晚于截止日期")
        if (start != item.startDate || due != item.dueDate) {
            val old = "${item.startDate ?: "—"} ~ ${item.dueDate ?: "—"}"
            val saved = itemRepo.save(item.copy(startDate = start, dueDate = due))
            logActivity(saved.objectId!!, "dates_changed", old, "$start ~ $due", memberService.current().objectId!!)
            return saved
        }
        return item
    }

    /** 今天的起点:wall=现在(避免日中拉入 due 落在过去立刻超期);work=钳到当前时刻的下一工作时刻(周末→周一窗起)。 */
    private fun todayStart(ws: LocalTime, we: LocalTime, workMode: Boolean): LocalDateTime {
        val now = LocalDateTime.ofInstant(clock.instant(), java.time.ZoneId.systemDefault())
        return nextWorkMoment(now, ws, we, workMode)
    }

    /** YPJ-216:下一工作时刻(仅工作制生效;wall 原样返回)。周末→下周一窗起;当日窗前→窗起;窗后→次工作日窗起。 */
    private fun nextWorkMoment(t: LocalDateTime, ws: LocalTime, we: LocalTime, workMode: Boolean): LocalDateTime {
        if (!workMode) return t
        val fullDay = ws == LocalTime.MIDNIGHT && we == LocalTime.MIDNIGHT
        var day = t.toLocalDate()
        while (day.dayOfWeek.value >= 6) day = day.plusDays(1)
        var cand = if (t.toLocalDate() == day) maxOf(t, day.atTime(ws)) else day.atTime(ws)
        if (!fullDay && cand.toLocalTime() >= we) {
            day = day.plusDays(1)
            while (day.dayOfWeek.value >= 6) day = day.plusDays(1)
            cand = day.atTime(ws)
        }
        return cand
    }

    /** 删除 item(V9-Q9 软删):置 deleted_at,连子树;全列表类查询自动过滤;回收站可恢复/物理删。 */
    @Transactional
    fun delete(itemId: UUID) {
        val item = itemRepo.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        projects.lockByObjectId(item.projectObjectId) // YPJ-128 统一锁序:第一把锁必须是 project 行(spec 0096)
        softDeleteSubtree(item.id!!, clock.instant()) // 触碰即迁移:原裸 Instant.now() 历史债(spec 0049)
        feasibilityService.recomputeProjectSignal(item.projectObjectId) // V9-S1
    }

    /** 子树软删/恢复(SQL 见 ItemTreeRepository)。 */
    private fun subtreeUpdate(
        rootId: Long,
        at: Instant?,
    ) {
        itemTree.updateSubtreeDeletedAt(rootId, at)
    }

    private fun softDeleteSubtree(
        rootId: Long,
        at: Instant,
    ) = subtreeUpdate(rootId, at)

    private fun restoreSubtree(rootId: Long) = subtreeUpdate(rootId, null)

    /** 物理删(回收站彻底删除):需求无级联先手动清(子树全量),item 子树随后真删。 */
    @Transactional
    fun purgeSubtree(rootId: Long) {
        itemTree.purgeRequirementsInSubtree(rootId)
        itemTree.purgeItemsInSubtree(rootId)
    }

    /** 回收站列表(已删 item 的子树根,删除时间倒序;子条目随父恢复,不单列)。 */
    fun recycleBin(): List<RecycleItemDto> {
        val rows = itemRepo.findByDeletedAtIsNotNullOrderByDeletedAtDesc()
        val rowObjectIds = rows.map { it.objectId }.toSet()
        // 只留子树根:父本身未删的才是根
        val parentObjectIds = rows.mapNotNull { it.parentObjectId }.toSet()
        val parentById =
            if (parentObjectIds.isEmpty()) {
                emptyMap()
            } else {
                itemRepo
                    .findByObjectIdIn(
                        parentObjectIds,
                    ).associateBy { it.objectId }
            }
        val roots =
            rows.filter { r ->
                val parent = r.parentObjectId?.let { parentById[it] }
                parent == null || parent.deletedAt == null
            }
        val projectMap = projects.findAll().associateBy { it.objectId }
        return roots.map { item ->
            val project = projectMap[item.projectObjectId]
            RecycleItemDto(
                itemId = item.objectId,
                number = "${project?.key ?: "?"}-${item.number}",
                title = item.title,
                projectName = project?.name ?: "?",
                deletedAt = item.deletedAt?.toString(),
            )
        }
    }

    data class RecycleItemDto(
        val itemId: UUID,
        val number: String,
        val title: String,
        val projectName: String,
        val deletedAt: String?,
    )

    @Transactional
    fun restore(itemId: UUID) {
        val item = itemRepo.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        if (item.deletedAt == null) throw ConflictException("该 item 不在回收站")
        restoreSubtree(item.id!!)
    }

    @Transactional
    fun purge(itemId: UUID) {
        val item = itemRepo.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        if (item.deletedAt == null) throw ConflictException("该 item 不在回收站")
        projects.lockByObjectId(item.projectObjectId) // YPJ-128 统一锁序:第一把锁必须是 project 行(spec 0096)
        purgeSubtree(item.id!!)
        feasibilityService.recomputeProjectSignal(item.projectObjectId)
    }

    private fun actorId(): UUID = memberService.current().objectId!!

    private fun logActivity(
        itemId: UUID,
        kind: String,
        oldValue: String?,
        newValue: String?,
        actorId: UUID,
    ) {
        activityRepo.save(
            ItemActivity(itemObjectId = itemId, kind = kind, oldValue = oldValue, newValue = newValue, actorMemberObjectId = actorId),
        )
    }

    /** wire 契约 = HEAD 命名(actorMemberId;值已是 objectId)。web Activity 类型按此消费。 */
    data class ActivityDto(
        val objectId: UUID,
        val kind: String,
        val oldValue: String?,
        val newValue: String?,
        val actorMemberId: UUID?,
        val createdAt: java.time.Instant,
    )

    /** 项目 level-1 类型 key(spec 0100 切片B 唯一口径);无 level-1 → null(项目无 sprint 语义)。 */
    private fun level1Key(projectObjectId: UUID): String? = groupTypes.findByProjectObjectIdAndLevel(projectObjectId, 1)?.typeKey

    fun activity(itemId: UUID): List<ActivityDto> {
        val item = itemRepo.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        return activityRepo
            .findByItemObjectIdOrderByCreatedAtDesc(item.objectId!!)
            .map { ActivityDto(it.objectId, it.kind, it.oldValue, it.newValue, it.actorMemberObjectId, it.createdAt) }
    }

    // ---------- 内部 ----------

    /** 防环:沿新父链上行,遇到自身即拒绝(领域规则 2)。 */
    private fun ensureNoCycle(
        item: Item,
        newParent: Item,
    ) {
        var cursor: UUID? = newParent.objectId
        var hops = 0
        while (cursor != null) {
            if (cursor == item.objectId) throw ConflictException("不能把 item 挂到自己的后代下")
            cursor = itemTree.findParentObjectId(cursor)
            if (++hops > 10_000) throw ConflictException("item 树异常,拒绝操作") // ponytail: 深度上限兜底
        }
    }

    /** D3(spec 0052):按 id 直取项目行,替代 findAll().firstOrNull 的整表扫描。 */
    private fun projectOf(projectObjectId: UUID): yangzhou.persistence.Project = projects.findByObjectId(projectObjectId) ?: throw NotFoundException("项目不存在")

    /** D3(spec 0052):单项目 item 组装上下文,list()/get() 共享。 */
    private fun buildCtx(
        project: yangzhou.persistence.Project,
        projectItems: List<Item>,
    ): ItemCtx {
        val projectObjectId = project.objectId!!
        val statusById = statuses.findByProjectObjectIdOrderByPosition(projectObjectId).associateBy { it.objectId }
        val blockedIds = dependencyService.blockedItemObjectIds(projectObjectId)
        val attrNames = definitions.findByWorkspaceObjectId(project.workspaceObjectId).associate { it.objectId!! to it.name }
        val memberMap = members.findByWorkspaceObjectId(project.workspaceObjectId).associateBy { it.objectId }
        val itemObjectIds = projectItems.mapNotNull { it.objectId }
        val reqs = requirementRepo.findByItemObjectIdIn(itemObjectIds)
        val ms = memberships.findByItemObjectIdIn(itemObjectIds)
        val groupById =
            if (ms.isEmpty()) {
                emptyMap()
            } else {
                groups
                    .findAllByObjectIdIn(
                        ms
                            .map {
                                it.groupObjectId
                            }.toSet(),
                    ).associateBy { it.objectId!! }
            }
        val currentSprint =
            ms
                .mapNotNull { m ->
                    groupById[m.groupObjectId]?.takeIf { it.status != "completed" && it.type == level1Key(projectObjectId) }?.let { m.itemObjectId to it }
                }.toMap()
        // YPJ-174(spec 0118):显示用 sprint 归属——活行优先，否则最近历史行（YPJ-62：completed 成员不消失）；currentSprint 保留给 backlog 谓词
        val latestSprint =
            ms
                .mapNotNull { m ->
                    groupById[m.groupObjectId]?.takeIf { it.type == level1Key(projectObjectId) }?.let { m.itemObjectId to it }
                }.groupBy({ it.first }) { it.second }
                .mapValues { (_, gs) -> gs.firstOrNull { it.status != "completed" } ?: gs.maxBy { it.createdAt } }
        return ItemCtx(project, statusById, blockedIds, attrNames, memberMap, reqs, currentSprint, latestSprint)
    }

    private inner class ItemCtx(
        val project: yangzhou.persistence.Project,
        val statusById: Map<UUID, yangzhou.persistence.Status>,
        val blockedIds: Set<UUID>,
        val attrNames: Map<UUID, String>,
        val memberMap: Map<UUID, yangzhou.persistence.Member>,
        val reqs: List<Requirement>,
        val currentSprint: Map<UUID, yangzhou.persistence.ItemGroup>,
        val latestSprint: Map<UUID, yangzhou.persistence.ItemGroup>,
    ) {
        val finalIds: Set<UUID> = statusById.filterValues { it.statusType == StatusType.FINAL }.keys
        private val statusNames = statusById.mapValues { it.value.name }
        private val memberNames = memberMap.mapValues { it.value.displayName }

        fun toDto(item: Item): ItemDto = ItemDto(
            itemId = item.objectId,
            number = "${project.key}-${item.number}",
            title = item.title,
            description = item.description,
            type = item.type,
            status = statusNames[item.statusObjectId]?.let { if (item.deletedAt != null) "[已删除] $it" else it } ?: "?",
            startDate = item.startDate?.toString(),
            dueDate = item.dueDate?.toString(),
            overdue = isOverdue(item.dueDate, LocalDateTime.now(clock), statusById[item.statusObjectId]?.statusType == StatusType.FINAL),
            feasSignal = item.feasSignal,
            priority = item.priority,
            estimateValue = item.estimateValue,
            estimateUnit = item.estimateUnit,
            blocked = item.objectId in blockedIds,
            assignee = item.assigneeObjectId?.let { memberNames[it] },
            assigneeColor = item.assigneeObjectId?.let { memberMap[it]?.color },
            dueSoon = isDueSoon(item.dueDate, LocalDateTime.now(clock)),
            createdAt = item.createdAt.toString(),
            sprintId = latestSprint[item.objectId]?.objectId,
            sprintName = latestSprint[item.objectId]?.name,
            onBoard = item.onBoard,
            externalRef = item.externalRef,
            parentItemId = item.parentObjectId,
            requirements =
            reqs.filter { it.itemObjectId == item.objectId }.map {
                RequirementDto(attrNames[it.attributeDefinitionObjectId] ?: "?", it.minLevel)
            },
        )
    }

    private fun resolveRequirements(
        workspaceObjectId: UUID,
        requirements: List<RequirementDto>,
    ): List<Pair<UUID, Int?>> {
        val defs = definitions.findByWorkspaceObjectId(workspaceObjectId)
        return requirements
            .groupBy { it.attribute }
            .map { (name, list) ->
                if (list.size > 1) throw BadRequestException("需求属性重复:$name 只能出现一次")
                val def =
                    defs.firstOrNull { it.name == name }
                        ?: throw BadRequestException("词表中没有属性:$name(先建属性)")
                def.objectId!! to list.first().minLevel
            }
    }
}

data class RequirementInput(
    val attribute: String,
    val minLevel: Int? = null,
)

data class CreateItemRequest(
    @field:NotBlank val title: String,
    val description: String? = null,
    val type: String = "task",
    val parentItemId: UUID? = null,
    val statusItemId: UUID? = null,
    val externalRef: String? = null,
    val requirements: List<RequirementInput> = emptyList(),
    val sprintId: UUID? = null,
    val estimateValue: String? = null, // YPJ-186(spec 0128):预估三态,与 priority/日期同式
    val estimateUnit: String? = null,
)

data class AssigneeRequest(
    val assigneeItemId: UUID? = null,
)

data class ReplaceRequirementsRequest(
    val requirements: List<RequirementInput> = emptyList(),
)

data class ReplaceItemAttributesRequest(
    val values: List<AttributeValueInput> = emptyList(),
)

data class AttributeValueInput(
    val attribute: String,
    val value: String,
)

data class UpdateItemRequest(
    val title: String? = null,
    val description: String? = null,
    val type: String? = null,
    val statusItemId: UUID? = null,
    val parentItemId: UUID? = null,
    val startDate: String? = null,
    val dueDate: String? = null,
    val priority: String? = null,
    val estimateValue: String? = null, // YPJ-186(spec 0128):预估三态
    val estimateUnit: String? = null,
)

/** dueSoon/overdue 纯函数(时钟纪律:today 由注入 Clock 提供,可测;spec 0051)。 */
/** YPJ-208(spec 0138 切片 b):解析项目工作日历(非法存值兑退缺省;SRS v4 §3.2 缺省全天窗,信任边界在 settings 写入)。 */
internal fun workdayBounds(project: yangzhou.persistence.Project): Pair<LocalTime, LocalTime> {
    val ws = runCatching { LocalTime.parse(project.workdayStart) }.getOrElse { DEFAULT_WORKDAY_START }
    // "24:00"=全天窗终点(SRS v4 §3.2,2026-11-21 拍板);LocalTime.parse 不认 24:00,显式拦截
    val we = if (project.workdayEnd.trim() == "24:00") {
        LocalTime.MIDNIGHT
    } else {
        runCatching { LocalTime.parse(project.workdayEnd) }.getOrElse { DEFAULT_WORKDAY_END }
    }
    return ws to we
}

/** date-only 输入的默认工作日界(日历缺失/非法时兜底;SRS v4:00:00/24:00 全天窗)。 */
internal val DEFAULT_WORKDAY_START: LocalTime = LocalTime.MIDNIGHT
internal val DEFAULT_WORKDAY_END: LocalTime = LocalTime.MIDNIGHT

/** 每日窗分钟数(单点;两端 00:00 = 全天 1440,与 WorkdayCalculator.isFullDay 同口径)。 */
internal fun workdayWindowMinutes(ws: LocalTime, we: LocalTime): Long = if (ws == LocalTime.MIDNIGHT && we == LocalTime.MIDNIGHT) {
    1440L
} else {
    java.time.Duration.between(ws, we).toMinutes()
}

/** YPJ-235(spec 0146):estimate → WorkdayCalculator 小时;unit 缺失/未知按 hour兜底(信任边界在 EstimateInput)。 */
// YPJ-243(spec 0150):互算警告文案集中(中文,前端 toast 原文展示)
private const val W_DUE_FROM_ESTIMATE = "截止时间已按预估重算"
private const val W_NO_START_ESTIMATE = "无开始日期,截止时间未铺算"
private const val W_DUE_CLEAR_BACKFILL = "截止时间已按预估回填(清除被推算覆盖)"
private const val W_EST_FROM_DATES = "预估已按起止重算:%s"
private const val W_EST_CLAMP = "预估超出上限 999,已钳制"
private const val W_CONFLICT = "起止时长与预估不一致,已按双值保存"

internal fun estimateHours(
    estimateValue: Double?,
    estimateUnit: String?,
    workdayStart: LocalTime,
    workdayEnd: LocalTime,
): Double? {
    val v = estimateValue ?: return null
    val winMin = workdayWindowMinutes(workdayStart, workdayEnd).toDouble()
    return when (estimateUnit ?: "hour") {
        "hour" -> v
        "day" -> v * winMin / 60.0
        // SRS v4 §3.3(2026-11-21 拍板):week = 7 天(无 weekend 休息日语义;旧 5×窗废止)
        "week" -> v * 7 * winMin / 60.0
        else -> v
    }
}

internal fun isOverdue(
    due: LocalDate?,
    today: LocalDate,
    statusFinal: Boolean,
): Boolean = due != null && due < today && !statusFinal

/** YPJ-208(spec 0138):item 起止升到时刻级,overdue 改时刻口径;LocalDate 重载供 milestone targetDate(保持 DATE)同用。 */
internal fun isOverdue(
    due: LocalDateTime?,
    now: LocalDateTime,
    statusFinal: Boolean,
): Boolean = due != null && now > due && !statusFinal

internal fun isDueSoon(
    due: LocalDate?,
    today: LocalDate,
): Boolean = due != null && due >= today && due < today.plusDays(3)

/** 时刻口径:窗口 [now, now+3d) 右开;与日期版同语义的 minute 级平移。 */
internal fun isDueSoon(
    due: LocalDateTime?,
    now: LocalDateTime,
): Boolean = due != null && due >= now && due < now.plusDays(3)

/** YPJ-186(spec 0128):预估显示文本(activity log 用;数值去尾零+单位 key)。 */
internal fun estimateText(
    value: Double?,
    unit: String?,
): String = when {
    value == null || unit == null -> "(空)"
    value == value.toLong().toDouble() -> "${value.toLong()} $unit"
    else -> "$value $unit"
}

/** YPJ-186(spec 0128):预估三态合并(与 priority/日期同式)——两 null=不改;空对=清除;值对=设置。required 项目显式清除 → 400。 */
internal fun applyEstimate(
    current: Item,
    valueText: String?,
    unitText: String?,
    required: Boolean,
): Item {
    val parsed = EstimateInput.parse(valueText, unitText) ?: return current
    val (value, unit) = parsed
    // 同值跳过在前:required 项目里「已空再清」=无变化,不拦(spec 0128:required 只禁「从有到无」)
    if (value == current.estimateValue && unit == current.estimateUnit) return current
    if (value == null && required) throw BadRequestException("本项目立票必须填预估,不能清除")
    return current.copy(estimateValue = value, estimateUnit = unit)
}

/** YPJ-186(spec 0128):预估输入——value+unit 成对,unit 封闭枚举(复用 180 的 EstimateScales.UNITS)。 */
object EstimateInput {
    /** 三态:null=不改/未提供;Pair(null,null)=清除;Pair(v,u)=设置(校验 value∈(0,999]、unit∈枚举)。单给其一 → 400。 */
    fun parse(
        valueText: String?,
        unitText: String?,
    ): Pair<Double?, String?>? {
        if (valueText == null && unitText == null) return null
        val v = valueText?.trim().orEmpty()
        val u = unitText?.trim().orEmpty()
        if (v.isEmpty() && u.isEmpty()) return null to null
        if (v.isEmpty() || u.isEmpty()) throw BadRequestException("estimateValue 与 estimateUnit 须成对提供")
        val value = v.toDoubleOrNull() ?: throw BadRequestException("estimateValue 须为数字:$v")
        if (value <= 0.0 || value > 999.0) throw BadRequestException("estimateValue 须 (0,999]:$v")
        if (u !in yangzhou.api.business.project.EstimateScales.UNITS) {
            throw BadRequestException("estimateUnit 须为 hour/day/week:$u")
        }
        return value to u
    }
}
