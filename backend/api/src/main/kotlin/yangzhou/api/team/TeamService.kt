package yangzhou.api.team

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.member.MemberService
import yangzhou.api.support.ConflictException
import yangzhou.api.support.NotFoundException
import yangzhou.api.workspace.WorkspaceService
import yangzhou.persistence.Team
import yangzhou.persistence.TeamMember
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.TeamMemberRepository
import yangzhou.persistence.repository.TeamRepository
import java.util.UUID

/**
 * Team = workspace 纯分组(池),视图过滤用(Q6):
 * 匹配与候选在全 workspace 成员池上算,不看 Team;Project→Team 归属 defer。
 */
@Service
class TeamService(
    private val teams: TeamRepository,
    private val teamMembers: TeamMemberRepository,
    private val members: MemberRepository,
    private val memberService: MemberService,
    private val workspaceService: WorkspaceService,
) {

    data class TeamMemberDto(val memberId: UUID, val displayName: String)
    data class TeamDto(val teamId: UUID, val name: String, val members: List<TeamMemberDto>)

    fun list(): List<TeamDto> =
        teams.findByWorkspaceId(workspaceService.required().id!!).map { toDto(it) }

    @Transactional
    fun create(name: String): TeamDto {
        val workspaceId = workspaceService.required().id!!
        if (teams.existsByWorkspaceIdAndName(workspaceId, name)) throw ConflictException("分组已存在:$name")
        val team = teams.save(Team(workspaceId = workspaceId, name = name))
        return TeamDto(team.objectId, team.name, emptyList())
    }

    @Transactional
    fun rename(teamId: UUID, name: String): TeamDto {
        val team = teams.findByObjectId(teamId) ?: throw NotFoundException("分组不存在")
        val updated = teams.save(team.copy(name = name))
        return toDto(updated)
    }

    @Transactional
    fun delete(teamId: UUID) {
        val team = teams.findByObjectId(teamId) ?: throw NotFoundException("分组不存在")
        teams.delete(team) // team_member FK 级联
    }

    @Transactional
    fun addMember(teamId: UUID, memberId: UUID): TeamDto {
        val team = teams.findByObjectId(teamId) ?: throw NotFoundException("分组不存在")
        memberService.requireMember(memberId)
        val internalMember = members.findByObjectId(memberId)!!
        val teamId2 = team.id!!
        val memberId2 = internalMember.id!!
        if (!teamMembers.existsByTeamIdAndMemberId(teamId2, memberId2)) {
            teamMembers.save(TeamMember(teamId = teamId2, memberId = memberId2))
        }
        return toDto(team)
    }

    @Transactional
    fun removeMember(teamId: UUID, memberId: UUID): TeamDto {
        val team = teams.findByObjectId(teamId) ?: throw NotFoundException("分组不存在")
        val internalMember = members.findByObjectId(memberId) ?: throw NotFoundException("成员不存在")
        teamMembers.findByTeamId(team.id!!).firstOrNull { it.memberId == internalMember.id }
            ?.let { teamMembers.delete(it) }
        return toDto(team)
    }

    private fun toDto(team: Team): TeamDto {
        val byInternal = members.findAll().associate { it.id to it }
        val list = teamMembers.findByTeamId(team.id!!)
        return TeamDto(
            teamId = team.objectId,
            name = team.name,
            members = list.mapNotNull { tm ->
                byInternal[tm.memberId]?.let { TeamMemberDto(it.objectId, it.displayName) }
            },
        )
    }
}

data class CreateTeamRequest(
    @field:NotBlank val name: String,
)

data class AddTeamMemberRequest(val memberId: UUID)
