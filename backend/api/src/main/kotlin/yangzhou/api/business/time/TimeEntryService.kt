package yangzhou.api.business.time

import jakarta.validation.constraints.NotNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import yangzhou.api.business.github.GithubCommitService
import yangzhou.api.business.member.MemberService
import yangzhou.api.business.projectmember.ProjectMemberService
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.TimeEntry
import yangzhou.persistence.repository.ItemCommitSnapshotRepository
import yangzhou.persistence.repository.ItemGroupMemberRepository
import yangzhou.persistence.repository.ItemGroupRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ItemTypeRepository
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.TimeEntryRepository
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.TreeMap
import java.util.UUID

/**
 * V8-S3 工时:计时器(全局唯一,开新自动停旧)+ 手动补录。
 * 任意项目成员可记(池语义同指派);进行中条目不计 minutes,读时折算到 now。
 */
@Service
class TimeEntryService(
    private val timeEntries: TimeEntryRepository,
    private val items: ItemRepository,
    private val projects: yangzhou.persistence.repository.ProjectRepository,
    private val members: MemberRepository,
    private val memberService: MemberService,
    private val projectMembers: ProjectMemberService,
    private val githubCommits: GithubCommitService,
    private val commitSnapshots: ItemCommitSnapshotRepository,
    private val objectMapper: ObjectMapper,
    private val groups: ItemGroupRepository,
    private val groupMembers: ItemGroupMemberRepository,
    private val itemTypes: ItemTypeRepository,
) {
    data class TimeEntryDto(
        val timeEntryId: UUID,
        val itemId: UUID,
        val member: String,
        val startedAt: String,
        val endedAt: String?,
        val minutes: Long,
        val note: String?,
    )

    data class ItemTimeLog(
        val entries: List<TimeEntryDto>,
        val totalMinutes: Long,
    )

    data class MemberSummary(
        val memberId: UUID,
        val displayName: String,
        val totalMinutes: Long,
        val entryCount: Int,
    )

    /** minutes 空 = 开计时(自动停旧);minutes 非空 = 补录(不得再带 startedAt/endedAt)。 */
    @Transactional
    fun create(
        itemId: UUID,
        minutes: Int?,
        startedAtText: String?,
        endedAtText: String?,
        note: String?,
    ): TimeEntryDto {
        // 补录(minutes)与起止(startedAt/endedAt)互斥(V8-S3 验收)
        if (minutes != null && (startedAtText != null || endedAtText != null)) {
            throw BadRequestException("补录只填 minutes 与备注;计时模式由服务端生成起止")
        }
        if (minutes == null && startedAtText == null && endedAtText != null) {
            throw BadRequestException("起止需成对:startedAt 与 endedAt 都要给")
        }
        val item = items.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val me = memberService.current()
        projectMembers.assertAssignable(item.projectObjectId, me.objectId!!)

        val entry =
            when {
                minutes == null && startedAtText != null -> { // V16-3 起止补录(工时建议落账走这里);首末 commit ≈ 开工/收工
                    val endText = endedAtText ?: throw BadRequestException("起止需成对:startedAt 与 endedAt 都要给")
                    val start = parseInstant(startedAtText) ?: throw BadRequestException("startedAt 应为 ISO-8601(如 2026-10-07T14:00:00Z)")
                    val end = parseInstant(endText) ?: throw BadRequestException("endedAt 应为 ISO-8601")
                    if (!end.isAfter(start)) throw BadRequestException("endedAt 必须晚于 startedAt")
                    TimeEntry(itemObjectId = item.objectId!!, memberObjectId = me.objectId!!, startedAt = start, endedAt = end, note = note)
                }
                minutes == null -> { // 计时:停掉我所有进行中的,再开新的
                    timeEntries
                        .findByMemberObjectIdAndEndedAtIsNull(me.objectId!!)
                        .forEach { timeEntries.save(it.copy(endedAt = Instant.now())) }
                    TimeEntry(itemObjectId = item.objectId!!, memberObjectId = me.objectId!!, startedAt = Instant.now(), note = note)
                }
                minutes <= 0 -> throw BadRequestException("minutes 必须为正数")
                else ->
                    TimeEntry(
                        itemObjectId = item.objectId!!,
                        memberObjectId = me.objectId!!,
                        startedAt = Instant.now().minusSeconds(minutes * 60L),
                        endedAt = Instant.now(),
                        minutes = minutes,
                        note = note,
                    )
            }
        return toDto(timeEntries.save(entry), me.displayName)
    }

    /** 停止自己的计时;非本人/不存在 404,已结束 409。 */
    @Transactional
    fun stop(entryId: UUID): TimeEntryDto {
        val meId = memberService.current().objectId!!
        val entry = timeEntries.findByObjectId(entryId) ?: throw NotFoundException("工时记录不存在")
        if (entry.memberObjectId != meId) throw NotFoundException("工时记录不存在")
        if (entry.endedAt != null) throw ConflictException("计时已结束")
        val saved = timeEntries.save(entry.copy(endedAt = Instant.now()))
        val name = members.findByObjectId(entry.memberObjectId)?.displayName ?: "?"
        return toDto(saved, name)
    }

    fun listForItem(itemId: UUID): ItemTimeLog {
        val item = items.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val rows = timeEntries.findByItemObjectIdOrderByStartedAtDesc(item.objectId!!)
        val memberMap = members.findAllByObjectIdIn(rows.map { it.memberObjectId }.toSet()).associateBy { it.objectId }
        val entries = rows.map { toDto(it, memberMap[it.memberObjectId]?.displayName ?: "?") }
        return ItemTimeLog(entries = entries, totalMinutes = entries.sumOf { it.minutes })
    }

    fun summary(projectKey: String): List<MemberSummary> {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        val projectItems = items.findByProjectObjectIdAndDeletedAtIsNullOrderByNumber(project.objectId!!)
        val rows = timeEntries.findByItemObjectIdIn(projectItems.mapNotNull { it.objectId })
        val memberMap = members.findAllByObjectIdIn(rows.map { it.memberObjectId }.toSet()).associateBy { it.objectId }
        return rows
            .groupBy { it.memberObjectId }
            .map { (memberId, list) ->
                MemberSummary(
                    memberId = memberMap[memberId]?.objectId ?: UUID(0, 0),
                    displayName = memberMap[memberId]?.displayName ?: "?",
                    totalMinutes = list.sumOf { minutesOf(it) },
                    entryCount = list.size,
                )
            }.sortedByDescending { it.totalMinutes }
    }

    /** YPJ-179(spec 0124)项目工时报表:byMember/byType/byDay 一次返回。from/to 为 ISO-8601 闭区间(锚=startedAt);groupId 限组成员 item。 */
    fun report(
        key: String,
        fromS: String?,
        toS: String?,
        groupObjectId: UUID?,
    ): TimeReportDto {
        val from = fromS?.let { parseInstant(it) ?: throw BadRequestException("from 应为 ISO-8601(如 2026-11-14T00:00:00Z)") }
        val to = toS?.let { parseInstant(it) ?: throw BadRequestException("to 应为 ISO-8601") }
        if (from != null && to != null && from.isAfter(to)) throw BadRequestException("from 必须早于 to")
        val project = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")
        val projectItems = items.findByProjectObjectIdAndDeletedAtIsNullOrderByNumber(project.objectId!!)
        val rows =
            groupObjectId?.let { gid ->
                val g = groups.findByProjectObjectIdAndObjectId(project.objectId, gid) ?: throw NotFoundException("组不存在:$gid")
                val memberItemIds = groupMembers.findByGroupObjectId(g.objectId).map { it.itemObjectId }.toSet()
                projectItems.filter { it.objectId in memberItemIds }
            } ?: projectItems
        val entries =
            timeEntries
                .findByItemObjectIdIn(rows.mapNotNull { it.objectId })
                .filter { (from == null || !it.startedAt.isBefore(from)) && (to == null || !it.startedAt.isAfter(to)) }
        val memberMap = members.findAllByObjectIdIn(entries.map { it.memberObjectId }.toSet()).associateBy { it.objectId }
        val byMember =
            entries
                .groupBy { it.memberObjectId }
                .map { (memberId, list) ->
                    MemberSummary(
                        memberId = memberId,
                        displayName = memberMap[memberId]?.displayName ?: "?",
                        totalMinutes = list.sumOf { minutesOf(it) },
                        entryCount = list.size,
                    )
                }.sortedByDescending { it.totalMinutes }
        val typeNames = itemTypes.findByProjectObjectIdOrderByCreatedAt(project.objectId).associateBy({ it.typeKey }, { it.name })
        return TimeReportDto(
            byMember = byMember,
            byType = typeBuckets(entries, rows.associate { it.objectId!! to it.type }, typeNames, Instant.now()),
            byDay = dayBuckets(entries, Instant.now()),
        )
    }

    data class SuggestedEntry(
        val repo: String,
        val ref: String,
        val commitCount: Int,
        val startedAt: String,
        val endedAt: String,
        val note: String,
    )

    /** 快照行解码后的整 ref 视图(编排层解 JSON,companion 纯函数消费)。 */
    data class SnapshotRef(
        val repo: String,
        val ref: String,
        val capturedAt: Instant,
        val capturedBy: String,
        val commits: List<GithubCommitService.CommitDto>,
    )

    /** V16-3 工时建议:分支 commit 时间轴 ≈ 工作记录(首 commit ≈ 开工,末 commit ≈ 收工);只出草稿,不落账。 */
    fun suggest(itemId: UUID): List<SuggestedEntry> {
        val live = githubCommits.commitsForItem(itemId) // 副作用:读时缓存快照(YPJ-184)
        val segments = buildSuggestions(live)
        val doneRefs = segments.map { it.repo to it.ref }.toSet()
        val snapshots =
            commitSnapshots.findByItemObjectId(itemId).mapNotNull { row ->
                runCatching {
                    SnapshotRef(
                        repo = row.repo,
                        ref = row.ref,
                        capturedAt = row.capturedAt,
                        capturedBy = row.capturedBy,
                        commits = objectMapper.readTree(row.commitsJson).map { n ->
                            GithubCommitService.CommitDto(
                                sha = n.get("sha").asText(),
                                message = n.get("message").asText(),
                                author = n.get("author")?.takeIf { !it.isNull }?.asText(),
                                date = n.get("date")?.takeIf { !it.isNull }?.asText(),
                                url = n.get("url")?.takeIf { !it.isNull }?.asText(),
                            )
                        },
                    )
                }.getOrNull() // 快照行损坏不炸建议
            }
        return segments + buildSuggestionsFromSnapshots(doneRefs, snapshots)
    }

    private fun parseInstant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()

    companion object {
        /** minutes 落库优先;缺省按起止折算,进行中折到 now(截断到分钟)。 */
        fun minutesOf(
            e: TimeEntry,
            now: Instant,
        ): Long = e.minutes?.toLong() ?: Duration.between(e.startedAt, e.endedAt ?: now).toMinutes()

        /** 纯映射:按 UTC startedAt 所在天聚合;首末日之间补零桶(图表连线连续)。 */
        fun dayBuckets(
            rows: List<TimeEntry>,
            now: Instant,
        ): List<DayMinutesDto> {
            val totals = TreeMap<LocalDate, Long>()
            for (e in rows) {
                val day = e.startedAt.atZone(ZoneOffset.UTC).toLocalDate()
                totals[day] = (totals[day] ?: 0L) + minutesOf(e, now)
            }
            if (totals.isEmpty()) return emptyList()
            val out = mutableListOf<DayMinutesDto>()
            var d = totals.firstKey()
            while (!d.isAfter(totals.lastKey())) {
                out += DayMinutesDto(d.toString(), totals[d] ?: 0L)
                d = d.plusDays(1)
            }
            return out
        }

        /** 纯映射:按 item.typeKey 聚合;定义名缺失回退 key(YPJ-177 防御);item 类型映射缺失跳过(删档 item 不计)。 */
        fun typeBuckets(
            rows: List<TimeEntry>,
            itemTypeByObjectId: Map<UUID, String>,
            typeNames: Map<String, String>,
            now: Instant,
        ): List<TypeMinutesDto> = rows
            .mapNotNull { e -> itemTypeByObjectId[e.itemObjectId]?.let { it to e } }
            .groupBy { it.first }
            .map { (typeKey, pairs) ->
                TypeMinutesDto(typeKey, typeNames[typeKey] ?: typeKey, pairs.sumOf { minutesOf(it.second, now) }, pairs.size)
            }.sortedBy { it.typeKey }

        /** 纯映射:≥2 个带时间戳的 commit 才成段(单 commit 无从定义收工);无日期 commit 忽略。 */
        fun buildSuggestions(refs: List<GithubCommitService.RefCommitsDto>): List<SuggestedEntry> = refs.mapNotNull { ref ->
            val dates =
                ref.commits
                    .mapNotNull { c -> c.date?.let { d -> runCatching { Instant.parse(d) }.getOrNull() } }
                    .sorted()
            if (dates.size < 2) return@mapNotNull null
            SuggestedEntry(
                repo = ref.repo,
                ref = ref.ref,
                commitCount = ref.commits.size,
                startedAt = dates.first().toString(),
                endedAt = dates.last().toString(),
                note = "分支 ${ref.ref} · ${ref.commits.size} commits",
            )
        }

        /**
         * YPJ-184(spec 0125)快照回退段:live 不足的 ref 查快照补段。
         * per ref——live 已出段的跳过;快照 ≥2 dated 同现有口径;恰 1 dated 且 capturedBy=finalize
         * (FINAL 锚,终止时间 = Done 时刻)→ 单提交段;0 dated / 无 FINAL 锚 → 不出。
         */
        fun buildSuggestionsFromSnapshots(
            doneRefs: Set<Pair<String, String>>,
            snapshots: List<SnapshotRef>,
        ): List<SuggestedEntry> = snapshots.mapNotNull { s ->
            if (s.repo to s.ref in doneRefs) return@mapNotNull null
            val dates =
                s.commits
                    .mapNotNull { c -> c.date?.let { d -> runCatching { Instant.parse(d) }.getOrNull() } }
                    .sorted()
            when {
                dates.size >= 2 ->
                    SuggestedEntry(
                        repo = s.repo,
                        ref = s.ref,
                        commitCount = s.commits.size,
                        startedAt = dates.first().toString(),
                        endedAt = dates.last().toString(),
                        note = "分支 ${s.ref} · ${s.commits.size} commits",
                    )
                dates.size == 1 && s.capturedBy == "finalize" ->
                    SuggestedEntry(
                        repo = s.repo,
                        ref = s.ref,
                        commitCount = 1,
                        startedAt = dates.first().toString(),
                        endedAt = s.capturedAt.toString(),
                        note = "分支 ${s.ref} · 单提交(commit→完成时刻)",
                    )
                else -> null
            }
        }
    }

    // ---------- 内部 ----------

    private fun toDto(
        e: TimeEntry,
        memberName: String,
    ) = TimeEntryDto(
        timeEntryId = e.objectId,
        itemId = items.findByObjectId(e.itemObjectId)?.objectId ?: UUID(0, 0),
        member = memberName,
        startedAt = e.startedAt.toString(),
        endedAt = e.endedAt?.toString(),
        minutes = minutesOf(e),
        note = e.note,
    )

    private fun minutesOf(e: TimeEntry): Long = minutesOf(e, Instant.now())
}

data class CreateTimeEntryRequest(
    @field:NotNull val minutes: Int?,
    val startedAt: String?,
    val endedAt: String?,
    val note: String?,
)

data class TypeMinutesDto(
    val typeKey: String,
    val typeName: String,
    val minutes: Long,
    val entryCount: Int,
)

data class DayMinutesDto(
    val date: String,
    val minutes: Long,
)

data class TimeReportDto(
    val byMember: List<TimeEntryService.MemberSummary>,
    val byType: List<TypeMinutesDto>,
    val byDay: List<DayMinutesDto>,
)
