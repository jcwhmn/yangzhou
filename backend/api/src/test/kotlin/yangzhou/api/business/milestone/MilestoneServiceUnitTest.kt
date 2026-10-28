package yangzhou.api.business.milestone

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Milestone
import yangzhou.persistence.Project
import yangzhou.persistence.repository.MilestoneRepository
import yangzhou.persistence.repository.ProjectRepository
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-89 单测补齐:MilestoneService(状态机:单 in_progress/终态不可变)。 */
class MilestoneServiceUnitTest {

    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val milestones = mockk<MilestoneRepository>(relaxed = true)
    private val service = MilestoneService(projects, milestones)

    private val project = Project(id = 1L, objectId = UUID(0, 1), workspaceObjectId = UUID(0, 9), key = "CHE", name = "CHE")
    private val m = Milestone(id = 1L, objectId = UUID(0, 1), projectObjectId = UUID(0, 1), name = "M1")

    @BeforeEach
    fun setup() {
        clearMocks(projects, milestones)
        every { projects.findByKey("CHE") } returns project
        every { milestones.findByProjectObjectIdAndObjectId(UUID(0, 1), m.objectId) } returns m
    }

    @Nested
    inner class CreateMilestone {

        @Test
        fun blankNameRejected() {
            assertFailsWith<BadRequestException> { service.create("CHE", CreateMilestoneRequest("  ")) }
        }

        @Test
        fun badDateRejected() {
            assertFailsWith<BadRequestException> {
                service.create("CHE", CreateMilestoneRequest("M2", targetDate = "2026/01/01"))
            }
        }
    }

    @Nested
    inner class UpdateMilestone {

        @Test
        fun invalidStatusRejected() {
            assertFailsWith<BadRequestException> {
                service.update("CHE", m.objectId, UpdateMilestoneRequest(status = "done"))
            }
        }

        @Test
        fun illegalTransitionRejected() {
            // planned → completed 跳两步
            assertFailsWith<ConflictException> {
                service.update("CHE", m.objectId, UpdateMilestoneRequest(status = "completed"))
            }
        }

        @Test
        fun terminalMilestoneImmutable() {
            val doneM = m.copy(status = "completed")
            every { milestones.findByProjectObjectIdAndObjectId(UUID(0, 1), doneM.objectId) } returns doneM
            assertFailsWith<ConflictException> {
                service.update("CHE", doneM.objectId, UpdateMilestoneRequest(name = "M1x"))
            }
            assertFailsWith<ConflictException> {
                service.update("CHE", doneM.objectId, UpdateMilestoneRequest(targetDate = "2026-11-01"))
            }
        }

        @Test
        fun secondInProgressRejected() {
            val other = Milestone(id = 2L, objectId = UUID(0, 2), projectObjectId = UUID(0, 1), name = "M0", status = "in_progress")
            every { milestones.findByProjectObjectIdAndStatus(UUID(0, 1), "in_progress") } returns listOf(other)
            assertFailsWith<ConflictException> {
                service.update("CHE", m.objectId, UpdateMilestoneRequest(status = "in_progress"))
            }
        }

        @Test
        fun legalTransitionSaved() {
            every { milestones.findByProjectObjectIdAndStatus(UUID(0, 1), "in_progress") } returns emptyList()
            every { milestones.save(any()) } answers { firstArg<Milestone>() }
            val dto = service.update("CHE", m.objectId, UpdateMilestoneRequest(status = "in_progress"))
            assertEquals("in_progress", dto.status)
            verify { milestones.save(withArg<Milestone> { assertEquals("in_progress", it.status) }) }
        }
    }

    @Nested
    inner class FindMilestone {

        @Test
        fun missingRejected() {
            every { milestones.findByProjectObjectIdAndObjectId(UUID(0, 1), any()) } returns null
            assertFailsWith<NotFoundException> { service.update("CHE", UUID.randomUUID(), UpdateMilestoneRequest(name = "x")) }
        }
    }
}
