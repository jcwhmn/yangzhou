package yangzhou.api.dashboard

import yangzhou.api.milestone.MilestoneService
import yangzhou.api.sprint.SprintService
import yangzhou.api.standup.StandupDto
import yangzhou.api.standup.StandupService
import yangzhou.persistence.repository.ProjectRepository
import org.springframework.stereotype.Service
import java.util.UUID

/** 跨项目在途组:只含有途(active sprint / in_progress milestone)的项目。 */
data class LiveGroupDto(
    val projectKey: String,
    val sprints: List<LiveSprintDto>,
    val milestones: List<LiveMilestoneDto>,
)

data class LiveSprintDto(val sprintId: UUID, val name: String, val endDate: String?)

data class LiveMilestoneDto(val milestoneId: UUID, val name: String, val targetDate: String?)

data class DashboardDto(val standup: StandupDto, val live: List<LiveGroupDto>)

@Service
class DashboardService(
    private val projects: ProjectRepository,
    private val standupService: StandupService,
    private val sprintService: SprintService,
    private val milestoneService: MilestoneService,
) {
    fun dashboard(): DashboardDto {
        val live = projects.findAll().filter { it.archivedAt == null }
            .map { p ->
                LiveGroupDto(
                    projectKey = p.key,
                    sprints = sprintService.list(p.key).filter { it.status == "active" }
                        .map { LiveSprintDto(it.sprintId, it.name, it.endDate) },
                    milestones = milestoneService.list(p.key).filter { it.status == "in_progress" }
                        .map { LiveMilestoneDto(it.milestoneId, it.name, it.targetDate) },
                )
            }
            .filter { it.sprints.isNotEmpty() || it.milestones.isNotEmpty() }
        return DashboardDto(standup = standupService.standup(null), live = live)
    }
}
