package yangzhou.persistence.repository

import org.springframework.data.repository.CrudRepository
import yangzhou.persistence.AttributeDefinition
import yangzhou.persistence.Capability
import yangzhou.persistence.Comment
import yangzhou.persistence.ItemActivity
import yangzhou.persistence.ItemGitRef
import yangzhou.persistence.Item
import yangzhou.persistence.Member
import yangzhou.persistence.Project
import yangzhou.persistence.ProjectMember
import yangzhou.persistence.ProjectRepo
import yangzhou.persistence.ProjectWorkflowRule
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
    fun findByWorkspaceIdAndGithubUsername(workspaceId: Long, githubUsername: String): Member?
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

interface ItemActivityRepository : CrudRepository<ItemActivity, Long> {
    fun findByItemIdOrderByCreatedAtDesc(item_id: Long): List<ItemActivity>
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

interface ProjectMemberRepository : CrudRepository<ProjectMember, Long> {
    fun findByProjectId(projectId: Long): List<ProjectMember>
    fun findByProjectIdOrderByIdAsc(projectId: Long): List<ProjectMember>
    fun existsByProjectIdAndMemberId(projectId: Long, memberId: Long): Boolean
    fun findByProjectIdAndMemberId(projectId: Long, memberId: Long): ProjectMember?
}

interface CommentRepository : CrudRepository<Comment, Long> {
    fun findByItemIdOrderByCreatedAtDesc(itemId: Long): List<Comment>
    fun findByObjectId(objectId: UUID): Comment?
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
    fun findByProjectIdAndStatusObjectId(projectId: Long, statusObjectId: UUID): List<Item>
    fun findByProjectIdOrderByNumber(projectId: Long): List<Item>
    fun findByProjectIdAndExternalRef(projectId: Long, externalRef: String): Item?
    fun existsByParentObjectId(parentObjectId: UUID): Boolean
}

interface ProjectRepoRepository : CrudRepository<ProjectRepo, Long> {
    fun findByProjectId(projectId: Long): List<ProjectRepo>
    fun findByProjectIdAndRepo(projectId: Long, repo: String): ProjectRepo?
    fun findByObjectId(objectId: UUID): ProjectRepo?
    fun existsByProjectIdAndRepo(projectId: Long, repo: String): Boolean
}

interface ItemGitRefRepository : CrudRepository<ItemGitRef, Long> {
    fun findByItemId(itemId: Long): List<ItemGitRef>
    fun findByRepoAndKindAndRef(repo: String, kind: String, ref: String): ItemGitRef?
}

interface ProjectWorkflowRuleRepository : CrudRepository<ProjectWorkflowRule, Long> {
    fun findByProjectId(projectId: Long): List<ProjectWorkflowRule>
    fun findByProjectIdAndEventType(projectId: Long, eventType: String): ProjectWorkflowRule?
    fun deleteByProjectId(projectId: Long)
}

interface RequirementRepository : CrudRepository<Requirement, Long> {
    fun findByItemId(itemId: Long): List<Requirement>
    fun findByItemIdIn(itemIds: Collection<Long>): List<Requirement>
    fun deleteByItemId(itemId: Long)
}
