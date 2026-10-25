package yangzhou.api.business.release

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.ItemGroup
import yangzhou.persistence.Project
import yangzhou.persistence.repository.ItemGroupMemberHistoryRepository
import yangzhou.persistence.repository.ItemGroupMemberRepository
import yangzhou.persistence.repository.ItemGroupRepository
import yangzhou.persistence.repository.ItemGroupTypeRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.MilestoneRepository
import yangzhou.persistence.repository.ProjectRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-89 单测补齐:ReleaseService(类型守卫/状态校验/已发布不可变/排序)。 */
class ReleaseServiceUnitTest {

    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val groups = mockk<ItemGroupRepository>(relaxed = true)
    private val groupMembers = mockk<ItemGroupMemberRepository>(relaxed = true)
    private val milestones = mockk<MilestoneRepository>(relaxed = true)
    private val itemRepo = mockk<ItemRepository>(relaxed = true)
    private val itemService = mockk<yangzhou.api.business.item.ItemService>(relaxed = true)
    private val memberHistory = mockk<ItemGroupMemberHistoryRepository>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC)
    private val groupTypes = mockk<ItemGroupTypeRepository>(relaxed = true)
    private val service = ReleaseService(
        projects, groups, groupMembers, milestones, itemRepo, itemService, memberHistory, clock, groupTypes,
    )

    private val project = Project(id = 1L, workspaceId = 9L, key = "CHE", name = "CHE")

    private fun release(
        id: Long = 1L,
        name: String = "R1",
        status: String = "planned",
        endDate: java.time.LocalDate? = null,
        releasedDate: java.time.LocalDate? = null,
    ) = ItemGroup(
        id = id, projectId = 1L, type = "release", name = name, status = status,
        endDate = endDate, releasedDate = releasedDate,
    )

    @BeforeEach
    fun setup() {
        clearMocks(projects, groups, groupMembers, milestones, itemRepo, itemService, memberHistory, groupTypes)
        every { projects.findByKey("CHE") } returns project
    }

    @Nested
    inner class CreateRelease {

        @Test
        fun missingReleaseTypeRejected() {
            every { groupTypes.findByProjectIdAndTypeKey(1L, "release") } returns null
            assertFailsWith<ConflictException> {
                service.create("CHE", CreateReleaseRequest("R1"))
            }
        }

        @Test
        fun blankNameRejected() {
            every { groupTypes.findByProjectIdAndTypeKey(1L, "release") } returns mockk()
            assertFailsWith<BadRequestException> { service.create("CHE", CreateReleaseRequest("  ")) }
        }

        @Test
        fun badStatusRejected() {
            every { groupTypes.findByProjectIdAndTypeKey(1L, "release") } returns mockk()
            assertFailsWith<BadRequestException> {
                service.create("CHE", CreateReleaseRequest("R1", status = "done"))
            }
        }

        @Test
        fun badDateRejected() {
            every { groupTypes.findByProjectIdAndTypeKey(1L, "release") } returns mockk()
            assertFailsWith<BadRequestException> {
                service.create("CHE", CreateReleaseRequest("R1", releasedDate = "2026/01/01"))
            }
        }
    }

    @Nested
    inner class UpdateRelease {

        private val planned = release()
        private val released = release(id = 2L, name = "R0", status = "released", releasedDate = java.time.LocalDate.of(2026, 10, 1))

        @BeforeEach
        fun stub() {
            every { groups.findByProjectIdAndObjectId(1L, planned.objectId) } returns planned
            every { groups.findByProjectIdAndObjectId(1L, released.objectId) } returns released
        }

        @Test
        fun missingReleaseRejected() {
            every { groups.findByProjectIdAndObjectId(1L, any()) } returns null
            assertFailsWith<NotFoundException> {
                service.update("CHE", UUID.randomUUID(), UpdateReleaseRequest(name = "x"))
            }
        }

        @Test
        fun releasedImmutableWhenTouched() {
            assertFailsWith<ConflictException> {
                service.update("CHE", released.objectId, UpdateReleaseRequest(name = "R0x"))
            }
            assertFailsWith<ConflictException> {
                service.update("CHE", released.objectId, UpdateReleaseRequest(targetDate = "2026-11-01"))
            }
        }

        @Test
        fun badStatusRejected() {
            assertFailsWith<BadRequestException> {
                service.update("CHE", planned.objectId, UpdateReleaseRequest(status = "done"))
            }
        }
    }

    @Nested
    inner class ListReleases {

        @Test
        fun plannedFirstByTargetThenReleasedByDateDesc() {
            val r1 = release(id = 1L, name = "late", endDate = java.time.LocalDate.of(2026, 12, 1))
            val r2 = release(id = 2L, name = "soon", endDate = java.time.LocalDate.of(2026, 11, 1))
            val r3 = release(id = 3L, name = "old-rel", status = "released", releasedDate = java.time.LocalDate.of(2026, 9, 1))
            val r4 = release(id = 4L, name = "new-rel", status = "released", releasedDate = java.time.LocalDate.of(2026, 10, 1))
            every { groups.findByProjectIdOrderByCreatedAt(1L) } returns listOf(r1, r2, r3, r4)
            every { groupMembers.findByGroupIdIn(any()) } returns emptyList()
            every { milestones.findById(any()) } returns java.util.Optional.empty()
            assertEquals(listOf("soon", "late", "new-rel", "old-rel"), service.list("CHE").map { it.name })
        }
    }
}
