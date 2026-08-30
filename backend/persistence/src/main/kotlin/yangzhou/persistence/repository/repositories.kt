package yangzhou.persistence.repository

import org.springframework.data.repository.CrudRepository
import yangzhou.persistence.AttributeDefinition
import yangzhou.persistence.Capability
import yangzhou.persistence.Item
import yangzhou.persistence.Member
import yangzhou.persistence.Project
import yangzhou.persistence.Requirement
import yangzhou.persistence.Status
import yangzhou.persistence.Team
import yangzhou.persistence.TeamMember
import yangzhou.persistence.StatusTransition
import yangzhou.persistence.Workspace
import java.util.UUID

interface WorkspaceRepository : CrudRepository<Workspace, Long>

interface MemberRepository : CrudRepository<Member, Long> {
    fun findByUsername(username: String): Member?
    fun findByObjectId(objectId: UUID): Member?
    fun findByWorkspaceId(workspaceId: Long): List<Member>
}

interface TeamRepository : CrudRepository<Team, Long> {
    fun findByWorkspaceId(workspaceId: Long): List<Team>
    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): Team?
    fun findByObjectId(objectId: UUID): Team?
    fun existsByWorkspaceIdAndName(workspaceId: Long, name: String): Boolean
}

interface TeamMemberRepository : CrudRepository<TeamMember, Long> {
    fun findByTeamId(teamId: Long): List<TeamMember>
    fun existsByTeamIdAndMemberId(teamId: Long, memberId: Long): Boolean
}

interface AttributeDefinitionRepository : CrudRepository<AttributeDefinition, Long> {
    fun findByWorkspaceId(workspaceId: Long): List<AttributeDefinition>
    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): AttributeDefinition?
    fun findByWorkspaceIdAndObjectId(workspaceId: Long, objectId: UUID): AttributeDefinition?
    fun existsByWorkspaceIdAndName(workspaceId: Long, name: String): Boolean
}

interface CapabilityRepository : CrudRepository<Capability, Long> {
    fun findByMemberId(memberId: Long): List<Capability>
    fun findByMemberIdAndAttributeDefinitionId(memberId: Long, attributeDefinitionId: Long): Capability?
    fun deleteByMemberId(memberId: Long)
}

interface ProjectRepository : CrudRepository<Project, Long> {
    fun findByKey(key: String): Project?
    fun existsByKey(key: String): Boolean
}

interface StatusRepository : CrudRepository<Status, Long> {
    fun findByProjectIdOrderByPosition(projectId: Long): List<Status>
    fun findByProjectIdAndObjectId(projectId: Long, objectId: UUID): Status?
    fun findByObjectId(objectId: UUID): Status?
    fun deleteByObjectId(objectId: UUID)
}

interface StatusTransitionRepository : CrudRepository<StatusTransition, Long> {
    fun findByProjectId(projectId: Long): List<StatusTransition>
    fun deleteByProjectId(projectId: Long)
}

interface ItemRepository : CrudRepository<Item, Long> {
    fun findByObjectId(objectId: UUID): Item?
    fun findByProjectIdOrderByNumber(projectId: Long): List<Item>
    fun findByProjectIdAndExternalRef(projectId: Long, externalRef: String): Item?
}

interface RequirementRepository : CrudRepository<Requirement, Long> {
    fun findByItemId(itemId: Long): List<Requirement>
    fun findByItemIdIn(itemIds: Collection<Long>): List<Requirement>
    fun deleteByItemId(itemId: Long)
}
