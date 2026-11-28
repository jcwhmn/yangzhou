package yangzhou.api.business.release

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.api.guard.ProjectGuard
import yangzhou.persistence.Item
import yangzhou.persistence.Member
import yangzhou.persistence.Project
import yangzhou.persistence.Release
import yangzhou.persistence.ReleaseItem
import yangzhou.persistence.ReleaseItemHistory
import yangzhou.persistence.Status
import yangzhou.persistence.StatusType
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.ReleaseItemHistoryRepository
import yangzhou.persistence.repository.ReleaseItemRepository
import yangzhou.persistence.repository.ReleaseRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** YPJ-191(spec 0132):ReleaseService 单元测试——校验/状态机(发布记 releasedDate)/终态冻结/删除守卫/排序。 */
class ReleaseServiceUnitTest {
    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val releases = mockk<ReleaseRepository>(relaxed = true)
    private val releaseItems = mockk<ReleaseItemRepository>(relaxed = true)
    private val releaseItemHistories = mockk<ReleaseItemHistoryRepository>(relaxed = true)
    private val members = mockk<MemberRepository>(relaxed = true)
    private val items = mockk<ItemRepository>(relaxed = true)
    private val statuses = mockk<yangzhou.persistence.repository.StatusRepository>(relaxed = true)
    private val projectGuard = mockk<ProjectGuard>(relaxed = true)
    private val memberService = mockk<yangzhou.api.business.member.MemberService>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC)
    private val service =
        ReleaseService(projects, releases, releaseItems, releaseItemHistories, members, items, statuses, projectGuard, memberService, clock)

    private val project = Project(id = 1L, objectId = UUID(0, 1), workspaceObjectId = UUID(0, 9), key = "CHE", name = "CHE")
    private val r = Release(id = 1L, objectId = UUID(0, 2), projectObjectId = UUID(0, 1), name = "1.0", stage = "rc")

    @Suppress("unused")
    private val today = LocalDate.parse("2026-11-20")

    @BeforeEach
    fun setup() {
        clearMocks(projects, releases, projectGuard)
        every { projects.findByKey("CHE") } returns project
        every { releases.findByProjectObjectIdAndObjectId(UUID(0, 1), r.objectId) } returns r
        every { memberService.current() } returns Member(workspaceObjectId = UUID(0, 9), username = "me", passwordHash = null, displayName = "我")
    }

    private fun saveReturns() {
        every { releases.save(any()) } answers { firstArg<Release>() }
        every { releaseItemHistories.save(any()) } answers { firstArg<ReleaseItemHistory>() }
    }

    @Test
    fun `create - blank name rejected`() {
        assertFailsWith<BadRequestException> { service.create("CHE", CreateReleaseRequest("  ")) }
    }

    @Test
    fun `create - bad stage rejected`() {
        assertFailsWith<BadRequestException> { service.create("CHE", CreateReleaseRequest("1.0", stage = "dev")) }
    }

    @Test
    fun `create - bad targetDate rejected`() {
        assertFailsWith<BadRequestException> { service.create("CHE", CreateReleaseRequest("1.0", targetDate = "2026/01/01")) }
    }

    @Test
    fun `create - trims and normalizes empties`() {
        saveReturns()
        val dto = service.create("CHE", CreateReleaseRequest(" 1.0 RC2 ", version = " ", stage = " rc "))
        assertEquals("1.0 RC2", dto.name)
        assertNull(dto.version)
        assertEquals("rc", dto.stage)
        assertEquals("planned", dto.status)
    }

    // ---------- YPJ-202(spec 0135):US-R5 事后补建 ----------

    @Test
    fun `create - released backfill with explicit date`() {
        saveReturns()
        val dto = service.create("CHE", CreateReleaseRequest("v0.5 补录", status = "released", releasedDate = "2026-06-01"))
        assertEquals("released", dto.status)
        assertEquals("2026-06-01", dto.releasedDate)
    }

    @Test
    fun `create - released backfill defaults to today`() {
        saveReturns()
        val dto = service.create("CHE", CreateReleaseRequest("v0.5 补录", status = "released"))
        assertEquals("released", dto.status)
        assertEquals("2026-11-20", dto.releasedDate)
    }

    @Test
    fun `create - released backfill future date rejected`() {
        assertFailsWith<BadRequestException> {
            service.create("CHE", CreateReleaseRequest("v0.5", status = "released", releasedDate = "2026-11-21"))
        }
    }

    @Test
    fun `create - bad status rejected`() {
        assertFailsWith<BadRequestException> { service.create("CHE", CreateReleaseRequest("1.0", status = "bogus")) }
    }

    @Test
    fun `publish - releasedDate defaults to today`() {
        saveReturns()
        val dto = service.update("CHE", r.objectId, UpdateReleaseRequest(status = "released"))
        assertEquals("released", dto.status)
        assertEquals("2026-11-20", dto.releasedDate)
    }

    @Test
    fun `publish - explicit releasedDate wins`() {
        saveReturns()
        val dto = service.update("CHE", r.objectId, UpdateReleaseRequest(status = "released", releasedDate = "2026-11-18"))
        assertEquals("2026-11-18", dto.releasedDate)
    }

    @Test
    fun `publish - bad releasedDate rejected`() {
        assertFailsWith<BadRequestException> {
            service.update("CHE", r.objectId, UpdateReleaseRequest(status = "released", releasedDate = "11-18"))
        }
    }

    @Test
    fun `terminal freeze - any edit on released rejected`() {
        every { releases.findByProjectObjectIdAndObjectId(UUID(0, 1), r.objectId) } returns r.copy(status = "released", releasedDate = today)
        assertFailsWith<ConflictException> { service.update("CHE", r.objectId, UpdateReleaseRequest(name = "x")) }
        assertFailsWith<ConflictException> { service.delete("CHE", r.objectId) }
    }

    @Test
    fun `illegal transition - no such status value`() {
        assertFailsWith<BadRequestException> { service.update("CHE", r.objectId, UpdateReleaseRequest(status = "in_progress")) }
    }

    @Test
    fun `delete - planned ok released rejected`() {
        saveReturns()
        service.delete("CHE", r.objectId)
        io.mockk.verify { releases.delete(any()) }
        every { releases.findByProjectObjectIdAndObjectId(UUID(0, 1), r.objectId) } returns r.copy(status = "released", releasedDate = today)
        assertFailsWith<ConflictException> { service.delete("CHE", r.objectId) }
    }

    @Test
    fun `cross project - 404`() {
        every { projects.findByKey("OTHER") } returns null
        assertFailsWith<NotFoundException> { service.list("OTHER") }
    }

    @Test
    fun `list - planned first by targetDate, released last by releasedDate desc`() {
        val p1 = Release(id = 1, objectId = UUID(0, 3), projectObjectId = UUID(0, 1), name = "远目标", targetDate = LocalDate.parse("2026-12-31"))
        val p2 = Release(id = 2, objectId = UUID(0, 4), projectObjectId = UUID(0, 1), name = "近目标", targetDate = LocalDate.parse("2026-11-21"))
        val p3 = Release(id = 3, objectId = UUID(0, 5), projectObjectId = UUID(0, 1), name = "无目标")
        val done1 = Release(id = 4, objectId = UUID(0, 6), projectObjectId = UUID(0, 1), name = "发布A", status = "released", releasedDate = LocalDate.parse("2026-11-01"))
        val done2 = Release(id = 5, objectId = UUID(0, 7), projectObjectId = UUID(0, 1), name = "发布B", status = "released", releasedDate = LocalDate.parse("2026-11-10"))
        every { releases.findByProjectObjectIdOrderByCreatedAt(UUID(0, 1)) } returns listOf(p1, done1, p2, done2, p3)
        val names = service.list("CHE").map { it.name }
        assertEquals(listOf("近目标", "远目标", "无目标", "发布B", "发布A"), names)
    }

    // ---------- YPJ-203(spec 0136):US-R7 逾期徽标 + US-R8 完成率(口径 1b) ----------

    @Test
    fun `list - overdue 与完成率 - 软删不入分母 - 零挂接`() {
        val done = Status(projectObjectId = UUID(0, 1), name = "done", statusType = StatusType.FINAL)
        val active = Status(projectObjectId = UUID(0, 1), name = "doing", statusType = StatusType.ACTIVE)
        fun item(status: Status, deleted: Boolean = false) = Item(projectObjectId = UUID(0, 1), number = 1, title = "t", statusObjectId = status.objectId, deletedAt = if (deleted) Instant.now() else null)
        val iDone = item(done)
        val iDeletedDone = item(done, deleted = true)
        val iActive = item(active)
        val p1 = Release(objectId = UUID(0, 41), projectObjectId = UUID(0, 1), name = "p1", targetDate = LocalDate.parse("2026-11-19"))
        val p2 = Release(objectId = UUID(0, 42), projectObjectId = UUID(0, 1), name = "p2") // 无 targetDate 不逾期;零挂接
        every { releases.findByProjectObjectIdOrderByCreatedAt(UUID(0, 1)) } returns listOf(p1, p2)
        every { releaseItems.findByReleaseObjectIdIn(any()) } returns
            listOf(
                ReleaseItem(releaseObjectId = p1.objectId, itemObjectId = iDone.objectId),
                ReleaseItem(releaseObjectId = p1.objectId, itemObjectId = iDeletedDone.objectId),
                ReleaseItem(releaseObjectId = p1.objectId, itemObjectId = iActive.objectId),
            )
        every { items.findAllByObjectIdIn(any()) } returns listOf(iDone, iDeletedDone, iActive)
        every { statuses.findAllByObjectIdIn(any()) } returns listOf(done, active)

        val dtos = service.list("CHE")
        val overdueRow = dtos.first { it.name == "p1" }
        assertTrue(overdueRow.overdue) // 昨天 < 今天(Clock 钉在 2026-11-20)
        assertEquals(1, overdueRow.doneCount) // FINAL 入分子;软删的不入分母
        assertEquals(2, overdueRow.totalCount)
        val emptyRow = dtos.first { it.name == "p2" }
        assertTrue(!emptyRow.overdue)
        assertEquals(0, emptyRow.doneCount)
        assertEquals(0, emptyRow.totalCount)
    }

    // ---------- US-R3 挂接(spec 0133) ----------

    private val item = Item(id = 9L, number = 1, objectId = UUID(0, 11), projectObjectId = UUID(0, 1), title = "it", statusObjectId = UUID(0, 99))
    private val relA = Release(id = 11L, objectId = UUID(0, 21), projectObjectId = UUID(0, 1), name = "A")
    private val relB = Release(id = 12L, objectId = UUID(0, 22), projectObjectId = UUID(0, 1), name = "B")

    @Test
    fun `membership - attach ok and idempotent`() {
        every { items.findByObjectId(item.objectId!!) } returns item
        every { releases.findByObjectId(relA.objectId) } returns relA
        every { releaseItems.findByItemObjectId(item.objectId!!) } returns emptyList()
        every { releaseItems.save(any()) } answers { firstArg<ReleaseItem>() }
        every { releaseItemHistories.save(any()) } answers { firstArg<ReleaseItemHistory>() }
        val dto = service.setMembership(item.objectId!!, SetReleaseMembershipRequest(relA.objectId))
        assertEquals(relA.objectId, dto.releaseId)
        assertEquals("A", dto.releaseName)
        // 幂等:已有同 release 行
        every { releaseItems.findByItemObjectId(item.objectId!!) } returns listOf(ReleaseItem(id = 1, objectId = UUID(0, 31), releaseObjectId = relA.objectId, itemObjectId = item.objectId!!))
        every { releases.findAllByObjectIdIn(any()) } returns listOf(relA)
        assertEquals(dto.releaseId, service.setMembership(item.objectId!!, SetReleaseMembershipRequest(relA.objectId)).releaseId)
    }

    @Test
    fun `membership - attach to released rejected`() {
        every { items.findByObjectId(item.objectId!!) } returns item
        every { releaseItems.findByItemObjectId(item.objectId!!) } returns emptyList()
        every { releases.findByObjectId(relA.objectId) } returns relA.copy(status = "released", releasedDate = today)
        assertFailsWith<ConflictException> { service.setMembership(item.objectId!!, SetReleaseMembershipRequest(relA.objectId)) }
    }

    @Test
    fun `membership - two planned releases conflict`() {
        every { items.findByObjectId(item.objectId!!) } returns item
        every { releases.findByObjectId(relB.objectId) } returns relB
        every { releaseItems.findByItemObjectId(item.objectId!!) } returns listOf(ReleaseItem(id = 1, objectId = UUID(0, 31), releaseObjectId = relA.objectId, itemObjectId = item.objectId!!))
        every { releases.findAllByObjectIdIn(any()) } returns listOf(relA)
        val ex = assertFailsWith<ConflictException> { service.setMembership(item.objectId!!, SetReleaseMembershipRequest(relB.objectId)) }
        assertTrue("A" in ex.message!!, ex.message)
    }

    @Test
    fun `membership - detach planned ok, released history row untouched`() {
        val rowOnA = ReleaseItem(id = 1, objectId = UUID(0, 31), releaseObjectId = relA.objectId, itemObjectId = item.objectId!!)
        every { items.findByObjectId(item.objectId!!) } returns item
        every { releases.findByObjectId(relA.objectId) } returns relA
        every { releaseItems.findByItemObjectId(item.objectId!!) } returns listOf(rowOnA)
        every { releases.findAllByObjectIdIn(any()) } returns listOf(relA)
        every { releaseItemHistories.save(any()) } answers { firstArg<ReleaseItemHistory>() }
        val dto = service.setMembership(item.objectId!!, SetReleaseMembershipRequest(null))
        assertNull(dto.releaseId)
        io.mockk.verify { releaseItems.delete(rowOnA) }
        // released 历史行不报错也不被删(挂接真相只看 planned)
        val rowOnDone = ReleaseItem(id = 2, objectId = UUID(0, 32), releaseObjectId = relA.objectId, itemObjectId = item.objectId!!)
        every { releaseItems.findByItemObjectId(item.objectId!!) } returns listOf(rowOnDone)
        every { releases.findAllByObjectIdIn(any()) } returns listOf(relA.copy(status = "released", releasedDate = today))
        assertNull(service.setMembership(item.objectId!!, SetReleaseMembershipRequest(null)).releaseId)
        io.mockk.verify(exactly = 0) { releaseItems.delete(rowOnDone) }
    }

    @Test
    fun `membership - cross project release 404, deleted item rejected`() {
        every { items.findByObjectId(item.objectId!!) } returns item
        every { releaseItems.findByItemObjectId(item.objectId!!) } returns emptyList()
        every { releases.findByObjectId(relA.objectId) } returns relA.copy(projectObjectId = UUID(0, 99))
        assertFailsWith<NotFoundException> { service.setMembership(item.objectId!!, SetReleaseMembershipRequest(relA.objectId)) }
        every { items.findByObjectId(item.objectId!!) } returns item.copy(deletedAt = Instant.now(clock))
        assertFailsWith<BadRequestException> { service.setMembership(item.objectId!!, SetReleaseMembershipRequest(null)) }
    }

    @Test
    fun `delete guard - members present rejected`() {
        every { releases.findByProjectObjectIdAndObjectId(UUID(0, 1), r.objectId) } returns r
        every { releaseItems.findByReleaseObjectId(r.objectId) } returns listOf(ReleaseItem(id = 1, objectId = UUID(0, 31), releaseObjectId = r.objectId, itemObjectId = UUID(0, 11)))
        assertFailsWith<ConflictException> { service.delete("CHE", r.objectId) }
    }
}
