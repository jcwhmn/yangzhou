package yangzhou.api.standup

import org.springframework.stereotype.Service
import yangzhou.api.member.MemberService
import yangzhou.api.support.NotFoundException
import yangzhou.persistence.repository.ItemActivityRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.StatusRepository
import yangzhou.api.dependency.DependencyService
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * V9-S4 每日站会:按人三组(昨日完成/今日名下/阻塞中),自然日窗口,范围 = workspace 全项目。
 * doneYesterday = 本人是 assignee 且昨日(自然日)有 status_changed 推进;
 * today = 本人是 assignee 且非终态;blocked = 本人是 assignee 且依赖未清。
 */
@Service
class StandupService(
    private val projects: ProjectRepository,
    private val items: ItemRepository,
    private val statuses: StatusRepository,
    private val members: MemberRepository,
    private val activities: ItemActivityRepository,
    private val dependencyService: DependencyService,
    private val memberService: MemberService,
) {

    fun standup(memberObjectId: UUID?): StandupDto {
        val member = memberObjectId?.let { members.findByObjectId(it) ?: throw NotFoundException("成员不存在") }
            ?: memberService.current()
        val memberId = member.id!!

        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val yesterdayStart = todayStart.minus(Duration.ofDays(1))
        val movedYesterday = activities.findByKindAndCreatedAtBetween("status_changed", yesterdayStart, todayStart)
            .map { it.itemId }.toSet()

        val done = mutableListOf<StandupItemDto>()
        val today = mutableListOf<StandupItemDto>()
        val blocked = mutableListOf<StandupItemDto>()

        projects.findAll().forEach { project ->
            val key = project.key
            val pid = project.id!!
            val blockedIds = dependencyService.blockedItemIds(pid)
            val statusById = statuses.findByProjectIdOrderByPosition(pid).associateBy { it.objectId }
            items.findByProjectIdAndDeletedAtIsNullOrderByNumber(pid).forEach { item ->
                if (item.assigneeObjectId != member.objectId) return@forEach
                val status = statusById[item.statusObjectId] ?: return@forEach
                val dto = StandupItemDto(
                    itemId = item.objectId,
                    number = "$key-${item.number}",
                    projectKey = key,
                    title = item.title,
                    statusName = status.name,
                )
                val iid = item.id!!
                if (iid in movedYesterday) done.add(dto)
                if (!status.isFinal) {
                    today.add(dto)
                    if (iid in blockedIds) blocked.add(dto)
                }
            }
        }
        return StandupDto(member.objectId, member.displayName, done, today, blocked)
    }
}

data class StandupItemDto(
    val itemId: UUID,
    val number: String,
    val projectKey: String,
    val title: String,
    val statusName: String,
)

data class StandupDto(
    val memberId: UUID,
    val displayName: String,
    val doneYesterday: List<StandupItemDto>,
    val today: List<StandupItemDto>,
    val blocked: List<StandupItemDto>,
)
