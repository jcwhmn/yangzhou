package yangzhou.api.time

import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import yangzhou.api.github.GithubCommitService
import yangzhou.api.member.MemberService
import yangzhou.api.projectmember.ProjectMemberService
import yangzhou.api.support.BadRequestException
import yangzhou.api.support.ConflictException
import yangzhou.api.support.NotFoundException
import yangzhou.persistence.TimeEntry
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.TimeEntryRepository
import java.time.Duration
import java.time.Instant
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

    data class ItemTimeLog(val entries: List<TimeEntryDto>, val totalMinutes: Long)

    data class MemberSummary(
        val memberId: UUID,
        val displayName: String,
        val totalMinutes: Long,
        val entryCount: Int,
    )

    /** minutes 空 = 开计时(自动停旧);minutes 非空 = 补录(不得再带 startedAt/endedAt)。 */
    @Transactional
    fun create(itemId: UUID, minutes: Int?, startedAtText: String?, endedAtText: String?, note: String?): TimeEntryDto {
        // 补录(minutes)与起止(startedAt/endedAt)互斥(V8-S3 验收)
        if (minutes != null && (startedAtText != null || endedAtText != null)) {
            throw BadRequestException("补录只填 minutes 与备注;计时模式由服务端生成起止")
        }
        if (minutes == null && startedAtText == null && endedAtText != null) {
            throw BadRequestException("起止需成对:startedAt 与 endedAt 都要给")
        }
        val item = items.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val me = memberService.current()
        projectMembers.assertAssignable(item.projectId, me.id!!)

        val entry = when {
            minutes == null && startedAtText != null -> { // V16-3 起止补录(工时建议落账走这里);首末 commit ≈ 开工/收工
                val endText = endedAtText ?: throw BadRequestException("起止需成对:startedAt 与 endedAt 都要给")
                val start = parseInstant(startedAtText) ?: throw BadRequestException("startedAt 应为 ISO-8601(如 2026-10-07T14:00:00Z)")
                val end = parseInstant(endText) ?: throw BadRequestException("endedAt 应为 ISO-8601")
                if (!end.isAfter(start)) throw BadRequestException("endedAt 必须晚于 startedAt")
                TimeEntry(itemId = item.id!!, memberId = me.id!!, startedAt = start, endedAt = end, note = note)
            }
            minutes == null -> { // 计时:停掉我所有进行中的,再开新的
                timeEntries.findByMemberIdAndEndedAtIsNull(me.id!!)
                    .forEach { timeEntries.save(it.copy(endedAt = Instant.now())) }
                TimeEntry(itemId = item.id!!, memberId = me.id!!, startedAt = Instant.now(), note = note)
            }
            minutes <= 0 -> throw BadRequestException("minutes 必须为正数")
            else -> TimeEntry(
                itemId = item.id!!,
                memberId = me.id!!,
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
        val meId = memberService.current().id!!
        val entry = timeEntries.findByObjectId(entryId) ?: throw NotFoundException("工时记录不存在")
        if (entry.memberId != meId) throw NotFoundException("工时记录不存在")
        if (entry.endedAt != null) throw ConflictException("计时已结束")
        val saved = timeEntries.save(entry.copy(endedAt = Instant.now()))
        val name = members.findById(entry.memberId).orElse(null)?.displayName ?: "?"
        return toDto(saved, name)
    }

    fun listForItem(itemId: UUID): ItemTimeLog {
        val item = items.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val rows = timeEntries.findByItemIdOrderByStartedAtDesc(item.id!!)
        val memberMap = members.findAllById(rows.map { it.memberId }.toSet()).associateBy { it.id }
        val entries = rows.map { toDto(it, memberMap[it.memberId]?.displayName ?: "?") }
        return ItemTimeLog(entries = entries, totalMinutes = entries.sumOf { it.minutes })
    }

    fun summary(projectKey: String): List<MemberSummary> {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        val projectItems = items.findByProjectIdAndDeletedAtIsNullOrderByNumber(project.id!!)
        val rows = timeEntries.findByItemIdIn(projectItems.mapNotNull { it.id })
        val memberMap = members.findAllById(rows.map { it.memberId }.toSet()).associateBy { it.id }
        return rows.groupBy { it.memberId }
            .map { (memberId, list) ->
                MemberSummary(
                    memberId = memberMap[memberId]?.objectId ?: UUID(0, 0),
                    displayName = memberMap[memberId]?.displayName ?: "?",
                    totalMinutes = list.sumOf { minutesOf(it) },
                    entryCount = list.size,
                )
            }
            .sortedByDescending { it.totalMinutes }
    }

    data class SuggestedEntry(
        val repo: String,
        val ref: String,
        val commitCount: Int,
        val startedAt: String,
        val endedAt: String,
        val note: String,
    )

    /** V16-3 工时建议:分支 commit 时间轴 ≈ 工作记录(首 commit ≈ 开工,末 commit ≈ 收工);只出草稿,不落账。 */
    fun suggest(itemId: UUID): List<SuggestedEntry> = buildSuggestions(githubCommits.commitsForItem(itemId))

    private fun parseInstant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()

    companion object {
        /** 纯映射:≥2 个带时间戳的 commit 才成段(单 commit 无从定义收工);无日期 commit 忽略。 */
        fun buildSuggestions(refs: List<GithubCommitService.RefCommitsDto>): List<SuggestedEntry> =
            refs.mapNotNull { ref ->
                val dates = ref.commits
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
    }

    // ---------- 内部 ----------

    private fun toDto(e: TimeEntry, memberName: String) = TimeEntryDto(
        timeEntryId = e.objectId,
        itemId = items.findById(e.itemId).orElse(null)?.objectId ?: UUID(0, 0),
        member = memberName,
        startedAt = e.startedAt.toString(),
        endedAt = e.endedAt?.toString(),
        minutes = minutesOf(e),
        note = e.note,
    )

    private fun minutesOf(e: TimeEntry): Long =
        e.minutes?.toLong() ?: Duration.between(e.startedAt, e.endedAt ?: Instant.now()).toMinutes()
}

data class CreateTimeEntryRequest(
    @field:NotNull val minutes: Int?,
    val startedAt: String?,
    val endedAt: String?,
    val note: String?,
)

@RestController
@RequestMapping("/api")
class TimeEntryController(private val service: TimeEntryService) {

    @PostMapping("/items/{itemId}/time-entries")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable itemId: UUID,
        @RequestBody request: CreateTimeEntryRequest,
    ): TimeEntryService.TimeEntryDto =
        service.create(itemId, request.minutes, request.startedAt, request.endedAt, request.note?.takeIf { it.isNotBlank() })

    @PostMapping("/time-entries/{entryId}/stop")
    fun stop(@PathVariable entryId: UUID): TimeEntryService.TimeEntryDto = service.stop(entryId)

    @GetMapping("/items/{itemId}/time-entries")
    fun listForItem(@PathVariable itemId: UUID): TimeEntryService.ItemTimeLog = service.listForItem(itemId)

    @GetMapping("/projects/{key}/time-summary")
    fun summary(@PathVariable key: String): List<TimeEntryService.MemberSummary> = service.summary(key)

    @GetMapping("/items/{itemId}/time-entry-suggestions")
    fun suggestions(@PathVariable itemId: UUID): List<TimeEntryService.SuggestedEntry> = service.suggest(itemId)
}
