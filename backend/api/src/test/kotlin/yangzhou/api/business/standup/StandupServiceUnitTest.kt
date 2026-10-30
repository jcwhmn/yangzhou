package yangzhou.api.business.standup

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import yangzhou.api.business.dependency.DependencyService
import yangzhou.api.business.member.MemberService
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
import java.util.UUID
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

    private val me =
        Member(id = 5L, objectId = UUID(0, 5), workspaceObjectId = UUID(0, 9), username = null, passwordHash = "h", displayName = "me")
    private val project = Project(id = 1L, objectId = UUID(0, 1), workspaceObjectId = UUID(0, 9), key = "CHE", name = "CHE")
    private val open = Status(id = 1L, objectId = UUID(0, 1), projectObjectId = UUID(0, 1), name = "In Progress", position = 1)
    private val done = Status(id = 2L, objectId = UUID(0, 2), projectObjectId = UUID(0, 1), name = "Done", isFinal = true, position = 2)

    private fun item(
        id: Long,
        number: Int,
        status: Status,
    ) = Item(
        id = id,
        objectId = UUID(0, id),
        projectObjectId = UUID(0, 1),
        number = number,
        title = "t$number",
        assigneeObjectId = me.objectId,
        statusObjectId = status.objectId,
    )

    @BeforeEach
    fun setup() {
        clearMocks(projects, items, statuses, members, activities, dependencyService, memberService)
        every { memberService.current() } returns me
        every { projects.findAll() } returns listOf(project)
        every { statuses.findByProjectObjectIdOrderByPosition(UUID(0, 1)) } returns listOf(open, done)
        every { dependencyService.blockedItemObjectIds(UUID(0, 1)) } returns emptySet()
        // 窗口桩:任一活动都算「昨日推进」
        every { activities.findByKindAndCreatedAtBetween(any(), any<Instant>(), any<Instant>()) } returns
            listOf(
                ItemActivity(
                    id = 1L,
                    objectId = UUID(0, 1),
                    itemObjectId = UUID(0, 1),
                    kind = "status_changed",
                    actorMemberObjectId = UUID(0, 5),
                ),
                ItemActivity(
                    id = 2L,
                    objectId = UUID(0, 2),
                    itemObjectId = UUID(0, 2),
                    kind = "status_changed",
                    actorMemberObjectId = UUID(0, 5),
                ),
            )
    }

    @Test
    fun threeBucketsByAssignee() {
        // item1:昨日推进且未终态 → done + today;item2:终态 → done only;item3:未推进未终态 → today
        every { items.findByProjectObjectIdAndDeletedAtIsNullOrderByNumber(UUID(0, 1)) } returns
            listOf(
                item(1L, 1, open),
                item(2L, 2, done),
                item(3L, 3, open),
            )
        val dto = service.standup(null) // null = 我
        assertEquals(listOf("CHE-1", "CHE-2"), dto.doneYesterday.map { it.number })
        assertEquals(listOf("CHE-1", "CHE-3"), dto.today.map { it.number })
        assertEquals(emptyList(), dto.blocked)
    }

    @Test
    fun blockedIntersectsWithToday() {
        every { items.findByProjectObjectIdAndDeletedAtIsNullOrderByNumber(UUID(0, 1)) } returns listOf(item(1L, 1, open))
        every { dependencyService.blockedItemObjectIds(UUID(0, 1)) } returns setOf(UUID(0, 1))
        assertEquals(listOf("CHE-1"), service.standup(null).blocked.map { it.number })
    }

    @Test
    fun otherMembersItemsExcluded() {
        val other = item(1L, 1, open).copy(assigneeObjectId = UUID.randomUUID())
        every { items.findByProjectObjectIdAndDeletedAtIsNullOrderByNumber(UUID(0, 1)) } returns listOf(other)
        val dto = service.standup(null)
        assertEquals(emptyList(), dto.today)
    }

    @Test
    fun unknownMemberRejected() {
        every { members.findByObjectId(any()) } returns null
        assertFailsWith<NotFoundException> { service.standup(UUID.randomUUID()) }
    }
}
