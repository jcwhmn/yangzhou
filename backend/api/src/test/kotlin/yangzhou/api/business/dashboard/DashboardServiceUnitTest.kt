package yangzhou.api.business.dashboard

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import yangzhou.api.business.milestone.MilestoneService
import yangzhou.api.business.sprint.SprintService
import yangzhou.api.business.standup.StandupDto
import yangzhou.api.business.standup.StandupService
import yangzhou.persistence.Project
import yangzhou.persistence.repository.ProjectRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** YPJ-89 单测补齐:DashboardService(只含有途项目:active sprint 或 in_progress milestone)。 */
class DashboardServiceUnitTest {
    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val standupService = mockk<StandupService>(relaxed = true)
    private val sprintService = mockk<SprintService>(relaxed = true)
    private val milestoneService = mockk<MilestoneService>(relaxed = true)
    private val service = DashboardService(projects, standupService, sprintService, milestoneService)

    private val live = Project(id = 1L, objectId = UUID(0, 1), workspaceObjectId = UUID(0, 9), key = "CHE", name = "CHE")
    private val archived =
        Project(
            id = 2L,
            objectId = UUID(0, 2),
            workspaceObjectId = UUID(0, 9),
            key = "OLD",
            name = "OLD",
            archivedAt = java.time.Instant.now(),
        )

    @BeforeEach
    fun setup() {
        clearMocks(projects, standupService, sprintService, milestoneService)
        every { projects.findAll() } returns listOf(live, archived)
        every { standupService.standup(null) } returns StandupDto(UUID.randomUUID(), "me", emptyList(), emptyList(), emptyList())
    }

    @Test
    fun activeSprintMakesProjectLive() {
        every { sprintService.list("CHE") } returns
            listOf(
                SprintService.SprintDto(UUID.randomUUID(), "V24", "active", null, null),
                SprintService.SprintDto(UUID.randomUUID(), "V23", "completed", null, null),
            )
        every { milestoneService.list("CHE") } returns emptyList()
        every { sprintService.list("OLD") } returns emptyList()
        every { milestoneService.list("OLD") } returns emptyList()
        val dto = service.dashboard()
        assertEquals(listOf("CHE"), dto.live.map { it.projectKey })
        assertEquals(listOf("V24"), dto.live[0].sprints.map { it.name }) // 非 active 被滤掉
    }

    @Test
    fun inProgressMilestoneAloneMakesProjectLive() {
        every { sprintService.list(any()) } returns emptyList()
        every { milestoneService.list("CHE") } returns
            listOf(
                MilestoneService.MilestoneDto(UUID.randomUUID(), "M1", "in_progress", "2026-11-01"),
                MilestoneService.MilestoneDto(UUID.randomUUID(), "M0", "completed", null),
            )
        every { milestoneService.list("OLD") } returns emptyList()
        val dto = service.dashboard()
        assertEquals(listOf("CHE"), dto.live.map { it.projectKey })
        assertEquals(listOf("M1"), dto.live[0].milestones.map { it.name })
    }

    @Test
    fun idleProjectsDropped() {
        every { sprintService.list(any()) } returns emptyList()
        every { milestoneService.list(any()) } returns emptyList()
        assertTrue(service.dashboard().live.isEmpty())
    }
}
