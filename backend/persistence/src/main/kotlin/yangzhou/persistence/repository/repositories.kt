package yangzhou.persistence.repository

import org.springframework.data.repository.CrudRepository
import yangzhou.persistence.AttributeDefinitionEntity
import yangzhou.persistence.CapabilityEntity
import yangzhou.persistence.ItemEntity
import yangzhou.persistence.MemberEntity
import yangzhou.persistence.ProjectEntity
import yangzhou.persistence.RequirementEntity
import yangzhou.persistence.StatusEntity
import yangzhou.persistence.WorkspaceEntity
import java.util.UUID

interface WorkspaceRepository : CrudRepository<WorkspaceEntity, Long>

interface MemberRepository : CrudRepository<MemberEntity, Long> {
    fun findByUsername(username: String): MemberEntity?
}

interface AttributeDefinitionRepository : CrudRepository<AttributeDefinitionEntity, Long> {
    fun findByWorkspaceId(workspaceId: Long): List<AttributeDefinitionEntity>
    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): AttributeDefinitionEntity?
    fun findByWorkspaceIdAndObjectId(workspaceId: Long, objectId: UUID): AttributeDefinitionEntity?
    fun existsByWorkspaceIdAndName(workspaceId: Long, name: String): Boolean
}

interface CapabilityRepository : CrudRepository<CapabilityEntity, Long> {
    fun findByMemberId(memberId: Long): List<CapabilityEntity>
    fun findByMemberIdAndAttributeDefinitionId(memberId: Long, attributeDefinitionId: Long): CapabilityEntity?
}

interface ProjectRepository : CrudRepository<ProjectEntity, Long> {
    fun findByKey(key: String): ProjectEntity?
    fun existsByKey(key: String): Boolean
}

interface StatusRepository : CrudRepository<StatusEntity, Long> {
    fun findByProjectIdOrderByPosition(projectId: Long): List<StatusEntity>
    fun findByProjectIdAndObjectId(projectId: Long, objectId: UUID): StatusEntity?
}

interface ItemRepository : CrudRepository<ItemEntity, Long> {
    fun findByObjectId(objectId: UUID): ItemEntity?
    fun findByProjectIdOrderByNumber(projectId: Long): List<ItemEntity>
}

interface RequirementRepository : CrudRepository<RequirementEntity, Long> {
    fun findByItemId(itemId: Long): List<RequirementEntity>
    fun findByItemIdIn(itemIds: Collection<Long>): List<RequirementEntity>
    fun deleteByItemId(itemId: Long)
}
