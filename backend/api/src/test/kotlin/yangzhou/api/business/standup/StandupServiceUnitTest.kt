package yangzhou.api.business.standup

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import yangzhou.api.business.member.MemberService
import yangzhou.api.business.dependency.DependencyService
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Item
import yangzhou.persistence.ItemActivity
import yangzhou.persistence.Member
import yangzhou.persistence.Project
import yangzhou.persistence.Status
import yangzhou.persistence.repository.ItemActivityRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.StatusRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-89 单测补齐:StandupService(三组归类:昨日完成/今日名下/阻塞)。 */
class StandupServiceUnitTest {

    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val items = mockk<ItemRepository>(relaxed = true)
    private val statuses = mockk<StatusRepository>(relaxed = true)
    private val members = mockk<MemberRepository>(relaxed = true)
    private val activities = mockk<ItemActivityRepository>(relaxed = true)
    private val dependencyService = mockk<DependencyService>(relaxed = true)
    private val memberService = mockk<MemberService>(relaxed = true)
    private val service = StandupService(projects, items, statuses, members, activities, dependencyService, memberService)

    private val me = Member(id = 5L, workspaceId = 9L, username = null, passwordHash = "h", displayName = "me")
    private val project = Project(id = 1L, workspaceId = 9L, key = "CHE", name = "CHE")
    private val open = Status(id = 1L, projectId = 1L, name = "In Progress", position = 1)
    private val done = Status(id = 2L, projectId = 1L, name = "Done", isFinal = true, position = 2)

    private fun item(id: Long, number: Int, status: Status) = Item(
        id = id, projectId = 1L, number = number, title = "t$number",
        assigneeObjectId = me.objectId, statusObjectId = status.objectId,
    )

    @BeforeEach
    fun setup() {
        clearMocks(projects, items, statuses, members, activities, dependencyService, memberService)
        every { memberService.current() } returns me
        every { projects.findAll() } returns listOf(project)
        every { statuses.findByProjectIdOrderByPosition(1L) } returns listOf(open, done)
        every { dependencyService.blockedItemIds(1L) } returns emptySet()
        // 窗口桩:任一活动都算「昨日推进」
        every { activities.findByKindAndCreatedAtBetween(any(), any<Instant>(), any<Instant>()) } returns listOf(
            ItemActivity(id = 1L, itemId = 1L, kind = "status_changed", actorMemberId = 5L),
            ItemActivity(id = 2L, itemId = 2L, kind = "status_changed", actorMemberId = 5L),
        )
    }

    @Test
    fun threeBucketsByAssignee() {
        // item1:昨日推进且未终态 → done + today;item2:终态 → done only;item3:未推进未终态 → today
        every { items.findByProjectIdAndDeletedAtIsNullOrderByNumber(1L) } returns listOf(
            item(1L, 1, open), item(2L, 2, done), item(3L, 3, open),
        )
        val dto = service.standup(null) // null = 我
        assertEquals(listOf("CHE-1", "CHE-2"), dto.doneYesterday.map { it.number })
        assertEquals(listOf("CHE-1", "CHE-3"), dto.today.map { it.number })
        assertEquals(emptyList(), dto.blocked)
    }

    @Test
    fun blockedIntersectsWithToday() {
        every { items.findByProjectIdAndDeletedAtIsNullOrderByNumber(1L) } returns listOf(item(1L, 1, open))
        every { dependencyService.blockedItemIds(1L) } returns setOf(1L)
        assertEquals(listOf("CHE-1"), service.standup(null).blocked.map { it.number })
    }

    @Test
    fun otherMembersItemsExcluded() {
        val other = item(1L, 1, open).copy(assigneeObjectId = UUID.randomUUID())
        every { items.findByProjectIdAndDeletedAtIsNullOrderByNumber(1L) } returns listOf(other)
        val dto = service.standup(null)
        assertEquals(emptyList(), dto.today)
    }

    @Test
    fun unknownMemberRejected() {
        every { members.findByObjectId(any()) } returns null
        assertFailsWith<NotFoundException> { service.standup(UUID.randomUUID()) }
    }
}
