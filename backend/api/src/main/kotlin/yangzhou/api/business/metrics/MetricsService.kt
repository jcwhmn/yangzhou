package yangzhou.api.business.metrics

import org.springframework.stereotype.Service
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.ItemGroupMemberHistory
import yangzhou.persistence.repository.ItemGroupMemberHistoryRepository
import yangzhou.persistence.repository.MetricsGroupRow
import yangzhou.persistence.repository.MetricsHistoryRow
import yangzhou.persistence.repository.MetricsRepository
import yangzhou.persistence.repository.ProjectRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor

// ---------- 纯计算函数(无 Spring,MetricsMathTest 直测) ----------

/** 线性插值百分位;sorted 须升序;p∈(0,100];空表 null。 */
internal fun percentile(
    sorted: List<Double>,
    p: Double,
): Double? {
    if (sorted.isEmpty()) return null
    if (sorted.size == 1) return sorted[0]
    val rank = (p / 100.0) * (sorted.size - 1)
    val lo = floor(rank).toInt()
    val hi = ceil(rank).toInt().coerceAtMost(sorted.size - 1)
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo)
}

internal fun utcDate(t: Instant): LocalDate = t.atZone(ZoneOffset.UTC).toLocalDate()

internal fun endOfDayUtc(d: LocalDate): Instant = d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()

/** 周桶起点(UTC 周一)。 */
internal fun weekStartUtc(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - 1).toLong())

/** 每 item 首次进入 final 的时刻(重开后再完成不重复计);rows 须按 createdAt 升序。 */
internal fun completionTs(
    rows: List<MetricsHistoryRow>,
    finalIds: Set<UUID>,
): Instant? = rows.firstOrNull { it.toStatusObjectId in finalIds }?.createdAt

/** 每 item 首次离开 start 状态的时刻;从未离开返回 null。 */
internal fun cycleStartTs(
    rows: List<MetricsHistoryRow>,
    startIds: Set<UUID>,
): Instant? = rows.firstOrNull { it.fromStatusObjectId != null && it.fromStatusObjectId in startIds }?.createdAt

/** item 在时刻 t 所处状态(最后一条 ≤t 的 toStatusObjectId;t 早于首条 history 返回 null)。 */
internal fun statusAt(
    rows: List<MetricsHistoryRow>,
    t: Instant,
): UUID? = rows.lastOrNull { it.createdAt <= t }?.toStatusObjectId

internal fun daysBetween(
    a: Instant,
    b: Instant,
): Double = Duration.between(a, b).toMillis() / 86_400_000.0

// ---------- 口径(中文,随响应下发,用户可见) ----------

private const val D_BACKLOG = "backlog = 现处 start 状态的 item 数;WIP = 非 start 非 final;done = final。"
private const val D_THROUGHPUT = "窗口内首次进入 final 状态的 item 数(重开后再完成不重复计);bucket=week 按 UTC 周一分桶。"
private const val D_CYCLE = "cycle = 首次离开 start 状态 → 首次进入 final;从未离开 start 的 item 无 cycle(noCycleCount 计数)。"
private const val D_LEAD = "lead = item 创建时刻 → 首次进入 final。"
private const val D_PCT = "对窗口内已完成 item 的天数分布取 P50/P85/P95(线性插值,拒绝平均数)。"
private const val D_CFD = "各状态存量按 UTC 日快照,由状态迁移史重放;item 首条 history 之前不计入(回填缺口)。"
private const val D_AGING = "WIP item 进入当前状态的时刻与已停留天数;queueWidth = 各非 final 状态当前存量。"
private const val D_BURNDOWN = "remaining = 当前成员总数 − 当日及之前已完成数;ideal = start→end 线性;口径注意:成员中途变动不回放(留痕票 YPJ-54 落地前)。"
private const val D_BURNUP = "done = 成员完成累计;scope = 当日在册成员累计(按留痕重放);口径注意:存量离组不可回填,上线时点起精确。"
private const val D_SCOPE_CHANGE =
    "scope-change:sprint 开始在册(Original)与结束在册(Final)成员数,及期间加入/离出次数;net = Final − Original;口径注意:removed 存量不可回填,上线时点起精确。"
private const val D_REOPEN = "reopen:窗口内首次完成的 item 中,此后发生过 final→非 final 迁移(重开)的占比;窗口内无完成则 rate = null。"
private const val D_VELOCITY = "各 sprint(有起止日期)内首次完成时刻落在 [start, end] 的 item 数;count 口径,非生产力 KPI。"

// ---------- 线型 DTO(同文件,吵了再拆) ----------

data class StatusCountDto(
    val statusId: UUID,
    val name: String,
    val isStart: Boolean,
    val isFinal: Boolean,
    val count: Int,
)

data class AssigneeCountDto(
    val memberObjectId: UUID?,
    val displayName: String?,
    val count: Int,
    val byStatus: Map<String, Int> = emptyMap(),
)

data class TypeCountDto(
    val type: String,
    val count: Int,
)

data class WorkloadResponse(
    val definitions: Map<String, String>,
    val backlogSize: Int,
    val wip: Int,
    val done: Int,
    val byStatus: List<StatusCountDto>,
    val byAssignee: List<AssigneeCountDto>,
    val byType: List<TypeCountDto>,
)

data class ThroughputBucketDto(
    val start: String,
    val completed: Int,
)

data class ThroughputResponse(
    val definitions: Map<String, String>,
    val bucket: String,
    val buckets: List<ThroughputBucketDto>,
)

data class FlowItemDto(
    val itemId: UUID,
    val number: Int,
    val title: String,
    val completedAt: String,
    val cycleDays: Double?,
    val leadDays: Double,
)

data class PercentilesDto(
    val cycleP50: Double?,
    val cycleP85: Double?,
    val cycleP95: Double?,
    val leadP50: Double?,
    val leadP85: Double?,
    val leadP95: Double?,
)

data class FlowTimesResponse(
    val definitions: Map<String, String>,
    val items: List<FlowItemDto>,
    val percentiles: PercentilesDto,
    val noCycleCount: Int,
)

data class CfdStatusDto(
    val statusId: UUID,
    val name: String,
    val isFinal: Boolean,
)

data class CfdPointDto(
    val date: String,
    val counts: Map<String, Int>,
)

data class CfdResponse(
    val definitions: Map<String, String>,
    val statuses: List<CfdStatusDto>,
    val series: List<CfdPointDto>,
)

data class AgingItemDto(
    val itemId: UUID,
    val number: Int,
    val title: String,
    val statusId: UUID,
    val statusName: String,
    val enteredAt: String,
    val ageDays: Double,
)

data class AgingResponse(
    val definitions: Map<String, String>,
    val items: List<AgingItemDto>,
    val queueWidth: List<StatusCountDto>,
)

data class BurndownPointDto(
    val date: String,
    val remaining: Int,
)

data class BurndownResponse(
    val definitions: Map<String, String>,
    val groupId: UUID,
    val name: String,
    val startDate: String,
    val endDate: String,
    val total: Int,
    val ideal: List<BurndownPointDto>,
    val actual: List<BurndownPointDto>,
)

data class BurnupDonePointDto(
    val date: String,
    val cumulative: Int,
)

data class ScopePointDto(
    val date: String,
    val total: Int,
)

data class ScopeChangeResponse(
    val definitions: Map<String, String>,
    val groupId: UUID,
    val name: String,
    val startDate: String,
    val endDate: String,
    val original: Int,
    val final: Int,
    val adds: Int,
    val removes: Int,
    val net: Int,
)

data class ReopenRateResponse(
    val definitions: Map<String, String>,
    val from: String,
    val to: String,
    val completed: Int,
    val reopened: Int,
    val rate: Double?,
)

data class BurnupResponse(
    val definitions: Map<String, String>,
    val groupId: UUID,
    val name: String,
    val done: List<BurnupDonePointDto>,
    val scope: List<ScopePointDto>,
)

data class VelocitySprintDto(
    val groupId: UUID,
    val name: String,
    val startDate: String,
    val endDate: String,
    val completed: Int,
)

data class VelocityResponse(
    val definitions: Map<String, String>,
    val sprints: List<VelocitySprintDto>,
)

// ---------- 服务 ----------

@Service
class MetricsService(
    private val repo: MetricsRepository,
    private val projects: ProjectRepository,
    private val memberHistory: ItemGroupMemberHistoryRepository,
    private val clock: Clock,
) {
    fun workload(key: String): WorkloadResponse {
        requireProject(key)
        val statuses = repo.statuses(key)
        val items = repo.items(key)
        val statusByObjectId = statuses.associateBy { it.statusObjectId }
        val members = repo.assignees(key).associateBy { it.memberObjectId }
        val countByStatusId = items.groupingBy { statusByObjectId.getValue(it.statusObjectId).statusObjectId }.eachCount()
        val byStatus =
            statuses.map { s ->
                StatusCountDto(
                    s.statusObjectId,
                    s.name,
                    s.isStart,
                    s.isFinal,
                    countByStatusId[s.statusObjectId] ?: 0,
                )
            }
        // YPJ-46:byAssignee 附 WIP 按 status 堆叠(Flow 页 WIP 条形)
        val byAssignee =
            items
                .groupBy { it.assigneeObjectId }
                .map { (oid, its) ->
                    val wipByStatus =
                        its
                            .filter {
                                !statusByObjectId.getValue(it.statusObjectId).isStart &&
                                    !statusByObjectId.getValue(it.statusObjectId).isFinal
                            }.groupingBy { statusByObjectId.getValue(it.statusObjectId).name }
                            .eachCount()
                    if (oid == null) {
                        AssigneeCountDto(null, null, its.size, wipByStatus)
                    } else {
                        AssigneeCountDto(oid, members[oid]?.displayName, its.size, wipByStatus)
                    }
                }.sortedWith(compareBy(nullsLast<String>()) { it.displayName })
        val byType =
            items
                .groupingBy { it.type }
                .eachCount()
                .map { TypeCountDto(it.key, it.value) }
                .sortedBy { it.type }
        val backlogSize = statuses.filter { it.isStart }.sumOf { countByStatusId[it.statusObjectId] ?: 0 }
        val done = statuses.filter { it.isFinal }.sumOf { countByStatusId[it.statusObjectId] ?: 0 }
        return WorkloadResponse(
            mapOf("workload" to D_BACKLOG),
            backlogSize,
            items.size - backlogSize - done,
            done,
            byStatus,
            byAssignee,
            byType,
        )
    }

    fun throughput(
        key: String,
        fromS: String?,
        toS: String?,
        bucket: String,
        groupObjectId: UUID?,
    ): ThroughputResponse {
        requireProject(key)
        if (bucket != "day" && bucket != "week") throw BadRequestException("bucket 仅支持 day|week")
        val (from, to) = window(fromS, toS)
        val completions = completions(key, memberItemObjectIds(key, groupObjectId)).filter { it.second in from..to }
        val step = if (bucket == "week") 7L else 1L
        val fromD = utcDate(from)
        val toD = utcDate(to)
        val buckets = mutableListOf<ThroughputBucketDto>()
        var d = if (bucket == "week") weekStartUtc(fromD) else fromD
        val lastD = if (bucket == "week") weekStartUtc(toD) else toD
        while (!d.isAfter(lastD)) {
            val next = d.plusDays(step)
            val c = completions.count { utcDate(it.second) >= d && utcDate(it.second) < next }
            buckets.add(ThroughputBucketDto(d.toString(), c))
            d = next
        }
        return ThroughputResponse(mapOf("throughput" to D_THROUGHPUT), bucket, buckets)
    }

    fun flowTimes(
        key: String,
        fromS: String?,
        toS: String?,
    ): FlowTimesResponse {
        requireProject(key)
        val (from, to) = window(fromS, toS)
        val statuses = repo.statuses(key)
        val finalIds = statuses.filter { it.isFinal }.map { it.statusObjectId }.toSet()
        val startIds = statuses.filter { it.isStart }.map { it.statusObjectId }.toSet()
        val byItem = repo.history(key).groupBy { it.itemObjectId }
        val flows =
            repo
                .items(key)
                .mapNotNull { item ->
                    val rows = byItem[item.itemObjectId] ?: return@mapNotNull null
                    val done = completionTs(rows, finalIds) ?: return@mapNotNull null
                    if (done !in from..to) return@mapNotNull null
                    val cycle = cycleStartTs(rows, startIds)?.let { daysBetween(it, done) }
                    FlowItemDto(item.itemObjectId, item.number, item.title, done.toString(), cycle, daysBetween(item.createdAt, done))
                }.sortedBy { it.completedAt }
        val cycles = flows.mapNotNull { it.cycleDays }.sorted()
        val leads = flows.map { it.leadDays }.sorted()
        return FlowTimesResponse(
            definitions = mapOf("cycle" to D_CYCLE, "lead" to D_LEAD, "percentiles" to D_PCT),
            items = flows,
            percentiles =
                PercentilesDto(
                    cycleP50 = percentile(cycles, 50.0),
                    cycleP85 = percentile(cycles, 85.0),
                    cycleP95 = percentile(cycles, 95.0),
                    leadP50 = percentile(leads, 50.0),
                    leadP85 = percentile(leads, 85.0),
                    leadP95 = percentile(leads, 95.0),
                ),
            noCycleCount = flows.size - cycles.size,
        )
    }

    fun cfd(
        key: String,
        fromS: String?,
        toS: String?,
    ): CfdResponse {
        requireProject(key)
        val (from, to) = window(fromS, toS)
        val statuses = repo.statuses(key)
        val statusById = statuses.associateBy { it.statusObjectId }
        val items = repo.items(key)
        val byItem = repo.history(key).groupBy { it.itemObjectId }
        val firstRow = byItem.values.minOfOrNull { rows -> rows.minOf { it.createdAt } } ?: from
        var d = maxOf(utcDate(from), utcDate(firstRow))
        val last = minOf(utcDate(to), utcDate(clock.instant()))
        val series = mutableListOf<CfdPointDto>()
        while (!d.isAfter(last)) {
            val t = endOfDayUtc(d)
            val counts = mutableMapOf<String, Int>()
            items.forEach { item ->
                val sid = statusAt(byItem[item.itemObjectId] ?: emptyList(), t) ?: return@forEach
                val oid = statusById[sid]?.statusObjectId ?: return@forEach
                counts.merge(oid.toString(), 1, Int::plus)
            }
            series.add(CfdPointDto(d.toString(), counts.toSortedMap()))
            d = d.plusDays(1)
        }
        return CfdResponse(
            definitions = mapOf("cfd" to D_CFD),
            statuses = statuses.map { CfdStatusDto(it.statusObjectId, it.name, it.isFinal) },
            series = series,
        )
    }

    fun aging(key: String): AgingResponse {
        requireProject(key)
        val now = clock.instant()
        val statuses = repo.statuses(key)
        val statusByObjectId = statuses.associateBy { it.statusObjectId }
        val items = repo.items(key)
        val byItem = repo.history(key).groupBy { it.itemObjectId }
        val aging =
            items
                .mapNotNull { item ->
                    val s = statusByObjectId[item.statusObjectId] ?: return@mapNotNull null
                    if (s.isStart || s.isFinal) return@mapNotNull null
                    val enteredAt = byItem[item.itemObjectId]?.maxOf { it.createdAt } ?: item.createdAt
                    AgingItemDto(
                        item.itemObjectId,
                        item.number,
                        item.title,
                        s.statusObjectId,
                        s.name,
                        enteredAt.toString(),
                        daysBetween(enteredAt, now),
                    )
                }.sortedByDescending { it.ageDays }
        val countByStatusId = items.groupingBy { statusByObjectId.getValue(it.statusObjectId).statusObjectId }.eachCount()
        val queueWidth =
            statuses
                .filter { !it.isFinal }
                .map { s -> StatusCountDto(s.statusObjectId, s.name, s.isStart, s.isFinal, countByStatusId[s.statusObjectId] ?: 0) }
        return AgingResponse(mapOf("aging" to D_AGING), aging, queueWidth)
    }

    fun burndown(
        key: String,
        groupObjectId: UUID,
    ): BurndownResponse {
        requireProject(key)
        val (g, memberJoinTs, completions) = sprintData(key, groupObjectId)
        val start = g.startDate ?: throw BadRequestException("sprint 须有起止日期")
        val end = g.endDate ?: throw BadRequestException("sprint 须有起止日期")
        val seriesEnd = minOf(end, utcDate(clock.instant()))
        val total = memberJoinTs.size
        val span = ChronoUnit.DAYS.between(start, seriesEnd)

        fun dates(): List<LocalDate> = if (seriesEnd.isBefore(start)) emptyList() else start.datesUntil(seriesEnd.plusDays(1)).toList()
        val ideal =
            dates().map { d ->
                val elapsed = ChronoUnit.DAYS.between(start, d)
                val remaining = if (span <= 0) 0 else (total * (span - elapsed) / span).toInt()
                BurndownPointDto(d.toString(), remaining.coerceIn(0, total))
            }
        val actual = dates().map { d -> BurndownPointDto(d.toString(), total - completions.count { !it.isAfter(endOfDayUtc(d)) }) }
        return BurndownResponse(
            definitions = mapOf("burndown" to D_BURNDOWN),
            groupId = g.groupObjectId,
            name = g.name,
            startDate = start.toString(),
            endDate = end.toString(),
            total = total,
            ideal = ideal,
            actual = actual,
        )
    }

    fun burnup(
        key: String,
        groupObjectId: UUID,
    ): BurnupResponse {
        requireProject(key)
        val (g, _, completions) = sprintData(key, groupObjectId)
        val episodes = memberHistory.findByGroupObjectId(g.groupObjectId) // YPJ-54:scope 线消费留痕重放
        val start = g.startDate ?: throw BadRequestException("sprint 须有起止日期")
        val end = g.endDate ?: throw BadRequestException("sprint 须有起止日期")
        val seriesEnd = minOf(end, utcDate(clock.instant()))

        fun dates(): List<LocalDate> = if (seriesEnd.isBefore(start)) emptyList() else start.datesUntil(seriesEnd.plusDays(1)).toList()
        val done = dates().map { d -> BurnupDonePointDto(d.toString(), completions.count { !it.isAfter(endOfDayUtc(d)) }) }
        val scope = dates().map { d -> ScopePointDto(d.toString(), coveringItemObjectIds(episodes, endOfDayUtc(d)).size) }
        return BurnupResponse(
            definitions = mapOf("burnup" to D_BURNUP),
            groupId = g.groupObjectId,
            name = g.name,
            done = done,
            scope = scope,
        )
    }

    /** M11(Y54):sprint 成员 scope 变化——开始/结束在册数与期间加/离次数(spec 0041);口径同 burnup,要求起止日期。 */
    fun scopeChange(
        key: String,
        groupObjectId: UUID,
    ): ScopeChangeResponse {
        requireProject(key)
        val g = findGroup(key, groupObjectId)
        val start = g.startDate ?: throw BadRequestException("sprint 须有起止日期")
        val end = g.endDate ?: throw BadRequestException("sprint 须有起止日期")
        val episodes = memberHistory.findByGroupObjectId(g.groupObjectId)
        val from = start.atStartOfDay(ZoneOffset.UTC).toInstant()
        val to = endOfDayUtc(end)
        val original = coveringItemObjectIds(episodes, from).size
        val final = coveringItemObjectIds(episodes, to).size
        val adds = episodes.count { it.addedAt >= from && it.addedAt <= to }
        val removes = episodes.count { e -> e.removedAt?.let { it in from..to } == true }
        return ScopeChangeResponse(
            definitions = mapOf("scopeChange" to D_SCOPE_CHANGE),
            groupId = g.groupObjectId,
            name = g.name,
            startDate = start.toString(),
            endDate = end.toString(),
            original = original,
            final = final,
            adds = adds,
            removes = removes,
            net = final - original,
        )
    }

    /** M12(Y54):重开率——窗口内首次完成 item 中,此后发生过 final→非 final 迁移的占比;分母 0 → null。 */
    fun reopenRate(
        key: String,
        fromS: String?,
        toS: String?,
    ): ReopenRateResponse {
        requireProject(key)
        val (from, to) = window(fromS, toS)
        val statuses = repo.statuses(key)
        val finalIds = statuses.filter { it.isFinal }.map { it.statusObjectId }.toSet()
        val byItem = repo.history(key).groupBy { it.itemObjectId }
        var completed = 0
        var reopened = 0
        repo.items(key).forEach { item ->
            val rows = byItem[item.itemObjectId] ?: return@forEach
            val firstFinal = completionTs(rows, finalIds) ?: return@forEach
            if (firstFinal !in from..to) return@forEach
            completed++
            if (rows.any {
                    it.fromStatusObjectId != null &&
                        it.fromStatusObjectId in finalIds &&
                        it.createdAt.isAfter(
                            firstFinal,
                        )
                }
            ) {
                reopened++
            }
        }
        val rate = if (completed == 0) null else reopened.toDouble() / completed
        return ReopenRateResponse(
            definitions = mapOf("reopen" to D_REOPEN),
            from = from.toString(),
            to = to.toString(),
            completed = completed,
            reopened = reopened,
            rate = rate,
        )
    }

    fun velocity(key: String): VelocityResponse {
        requireProject(key)
        val sprints = repo.groups(key, "sprint").filter { it.startDate != null && it.endDate != null }
        val members = repo.groupMembers(key).groupBy { it.groupObjectId }
        val compByItem = completions(key, null).toMap()
        val rows =
            sprints.map { g ->
                val from = g.startDate!!.atStartOfDay(ZoneOffset.UTC).toInstant()
                val to = endOfDayUtc(g.endDate!!)
                VelocitySprintDto(
                    g.groupObjectId,
                    g.name,
                    g.startDate.toString(),
                    g.endDate.toString(),
                    members[g.groupObjectId].orEmpty().count { m -> compByItem[m.itemObjectId]?.let { it in from..to } == true },
                )
            }
        return VelocityResponse(mapOf("velocity" to D_VELOCITY), rows)
    }

    // ---------- 内部 ----------

    /** 沿项目级端点惯例:未知 key → 404(纯 join 查询对未知 key 返回空集,须显式守卫)。 */
    private fun requireProject(key: String) {
        projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")
    }

    private fun window(
        fromS: String?,
        toS: String?,
    ): Pair<Instant, Instant> {
        val to = toS?.let { parseInstant(it) } ?: clock.instant()
        val from = fromS?.let { parseInstant(it) } ?: to.minus(Duration.ofDays(90))
        if (!from.isBefore(to)) throw BadRequestException("from 必须早于 to")
        if (Duration.between(from, to) > Duration.ofDays(366)) throw BadRequestException("窗口上限 365 天")
        return from to to
    }

    private fun parseInstant(s: String): Instant =
        try {
            Instant.parse(s)
        } catch (e: DateTimeParseException) {
            throw BadRequestException("时间须为 ISO-8601 Instant:$s")
        }

    /** groupObjectId 限定时的成员 item 内部 id 集;null = 全项目。 */
    private fun memberItemObjectIds(
        key: String,
        groupObjectId: UUID?,
    ): Set<UUID>? {
        if (groupObjectId == null) return null
        val g = findGroup(key, groupObjectId)
        return repo
            .groupMembers(key)
            .filter { it.groupObjectId == g.groupObjectId }
            .map { it.itemObjectId }
            .toSet()
    }

    /** (item 内部 id, 首次完成时刻);itemObjectIdFilter=null 取全项目。 */
    private fun completions(
        key: String,
        itemObjectIdFilter: Set<UUID>?,
    ): List<Pair<UUID, Instant>> {
        val finalIds =
            repo
                .statuses(key)
                .filter { it.isFinal }
                .map { it.statusObjectId }
                .toSet()
        val items = repo.items(key).filter { itemObjectIdFilter == null || it.itemObjectId in itemObjectIdFilter }
        val byItem = repo.history(key).groupBy { it.itemObjectId }
        return items.mapNotNull { item ->
            completionTs(byItem[item.itemObjectId] ?: emptyList(), finalIds)?.let { item.itemObjectId to it }
        }
    }

    private fun findGroup(
        key: String,
        groupObjectId: UUID,
    ): MetricsGroupRow =
        repo.groups(key, null).find { it.groupObjectId == groupObjectId } ?: throw NotFoundException("sprint 不存在:$groupObjectId")

    /** (group, 各成员加入时刻, 各成员首次完成时刻)。 */
    private fun sprintData(
        key: String,
        groupObjectId: UUID,
    ): Triple<MetricsGroupRow, List<Instant>, List<Instant>> {
        val g = findGroup(key, groupObjectId)
        val members = repo.groupMembers(key).filter { it.groupObjectId == g.groupObjectId }
        val compByItem = completions(key, members.map { it.itemObjectId }.toSet()).toMap()
        return Triple(g, members.map { it.createdAt }, members.mapNotNull { compByItem[it.itemObjectId] })
    }
}

/** YPJ-54:episode 覆盖判定——added_at ≤ t 且(未离组或 removed_at > t);返回 distinct itemObjectId 集。 */
internal fun coveringItemObjectIds(
    episodes: List<ItemGroupMemberHistory>,
    t: Instant,
): Set<UUID> = episodes.filter { e -> e.addedAt <= t && (e.removedAt?.let { it > t } ?: true) }.map { it.itemObjectId }.toSet()
