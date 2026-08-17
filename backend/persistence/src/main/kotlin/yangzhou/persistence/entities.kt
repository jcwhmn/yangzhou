package yangzhou.persistence

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("workspace")
data class WorkspaceEntity(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val name: String = "default",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

@Table("member")
data class MemberEntity(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val workspaceId: Long,
    val username: String,
    val passwordHash: String,
    val displayName: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

@Table("attribute_definition")
data class AttributeDefinitionEntity(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val workspaceId: Long,
    val name: String,
    val kind: String = "skill",
    val leveled: Boolean = false,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

@Table("capability")
data class CapabilityEntity(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val memberId: Long,
    val attributeDefinitionId: Long,
    val level: Int? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

@Table("project")
data class ProjectEntity(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val workspaceId: Long,
    val key: String,
    val name: String,
    val lastItemNumber: Int = 0,
    val archivedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

@Table("status")
data class StatusEntity(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val projectId: Long,
    val name: String,
    val isFinal: Boolean = false,
    val position: Int = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

@Table("item")
data class ItemEntity(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val projectId: Long,
    val number: Int,
    val title: String,
    val description: String? = null,
    val type: String = "task",
    val parentObjectId: UUID? = null,
    val statusObjectId: UUID,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

@Table("requirement")
data class RequirementEntity(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val itemId: Long,
    val attributeDefinitionId: Long,
    val minLevel: Int? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
