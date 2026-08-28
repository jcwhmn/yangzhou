package yangzhou.api.team

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class TeamController(private val service: TeamService) {

    @GetMapping("/teams")
    fun list(): List<TeamService.TeamDto> = service.list()

    @PostMapping("/teams")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateTeamRequest): TeamService.TeamDto = service.create(request.name.trim())

    @PatchMapping("/teams/{teamId}")
    fun rename(
        @PathVariable teamId: UUID,
        @Valid @RequestBody request: CreateTeamRequest,
    ): TeamService.TeamDto = service.rename(teamId, request.name.trim())

    @DeleteMapping("/teams/{teamId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable teamId: UUID) = service.delete(teamId)

    @PostMapping("/teams/{teamId}/members")
    fun addMember(
        @PathVariable teamId: UUID,
        @Valid @RequestBody request: AddTeamMemberRequest,
    ): TeamService.TeamDto = service.addMember(teamId, request.memberId)

    @DeleteMapping("/teams/{teamId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeMember(@PathVariable teamId: UUID, @PathVariable memberId: UUID) = service.removeMember(teamId, memberId)
}
