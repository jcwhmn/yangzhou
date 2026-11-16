package yangzhou.api.business.release

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.api.guard.LeaderGuard
import yangzhou.persistence.Project
import yangzhou.persistence.Release
import yangzhou.persistence.repository.ProjectRepository
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

/** YPJ-191(spec 0132):ReleaseService 单元测试——校验/状态机(发布记 releasedDate)/终态冻结/删除守卫/排序。 */
class ReleaseServiceUnitTest {
    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val releases = mockk<ReleaseRepository>(relaxed = true)
    private val leaderGuard = mockk<LeaderGuard>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC)
    private val service = ReleaseService(projects, releases, leaderGuard, clock)

    private val project = Project(id = 1L, objectId = UUID(0, 1), workspaceObjectId = UUID(0, 9), key = "CHE", name = "CHE")
    private val r = Release(id = 1L, objectId = UUID(0, 2), projectObjectId = UUID(0, 1), name = "1.0", stage = "rc")

    @Suppress("unused")
    private val today = LocalDate.parse("2026-11-20")

    @BeforeEach
    fun setup() {
        clearMocks(projects, releases, leaderGuard)
        every { projects.findByKey("CHE") } returns project
        every { releases.findByProjectObjectIdAndObjectId(UUID(0, 1), r.objectId) } returns r
    }

    private fun saveReturns() {
        every { releases.save(any()) } answers { firstArg<Release>() }
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
}
