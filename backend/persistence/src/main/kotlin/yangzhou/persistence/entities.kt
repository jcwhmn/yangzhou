package yangzhou.persistence

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("workspace")
data class Workspace(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val name: String = "default",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

@Table("member")
data class Member(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val workspaceId: Long,
    val username: String?,
    val passwordHash: String?,
    val displayName: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

@Table("team")
data class Team(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val workspaceId: Long,
    val name: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

@Table("team_member")
data class TeamMember(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val teamId: Long,
    val memberId: Long,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

@Table("attribute_definition")
data class AttributeDefinition(
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
data class Capability(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val memberId: Long,
    val attributeDefinitionId: Long,
    val level: Int? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

@Table("project")
data class Project(
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

@Table("project_member")
data class ProjectMember(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val projectId: Long,
    val memberId: Long,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

@Table("status")
data class Status(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val projectId: Long,
    val name: String,
    val icon: String? = null,
    val isFinal: Boolean = false,
    val isStart: Boolean = false,
    val position: Int = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

@Table("status_transition")
data class StatusTransition(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val projectId: Long,
    val fromStatusId: Long,
    val toStatusId: Long,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

@Table("item")
data class Item(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val projectId: Long,
    val number: Int,
    val title: String,
    val description: String? = null,
    val type: String = "task",
    val parentObjectId: UUID? = null,
    val assigneeObjectId: UUID? = null,
    val externalRef: String? = null,
    val statusObjectId: UUID,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

@Table("requirement")
data class Requirement(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val itemId: Long,
    val attributeDefinitionId: Long,
    val minLevel: Int? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
