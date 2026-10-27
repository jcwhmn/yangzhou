package yangzhou.persistence

import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Table("workspace")
data class Workspace(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val name: String = "default",
    val githubToken: String? = null,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)

@Table("member")
data class Member(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val workspaceId: Long,
    val username: String?,
    val passwordHash: String?,
    val displayName: String,
    val color: String? = null,
    val lastProjectKey: String? = null,
    val githubUsername: String? = null,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)

@Table("team")
data class Team(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val workspaceId: Long,
    val name: String,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)

@Table("team_member")
data class TeamMember(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val teamId: Long,
    val memberId: Long,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)

@Table("item_activity")
data class ItemActivity(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val itemId: Long,
    val kind: String,
    val oldValue: String? = null,
    val newValue: String? = null,
    val actorMemberId: Long? = null,
    val createdAt: Instant = Instant.now(),
)

@Table("item_status_history")
data class ItemStatusHistory(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val itemId: Long,
    val fromStatusId: Long? = null,
    val toStatusId: Long,
    val source: String,
    val actorMemberId: Long? = null,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)

@Table("comment")
data class Comment(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val itemId: Long,
    val authorMemberId: Long,
    val body: String,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)

@Table("attribute_definition")
data class AttributeDefinition(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val workspaceId: Long,
    val name: String,
    val kind: String = "skill",
    val leveled: Boolean = false,
    val parentId: Long? = null,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)

@Table("capability")
data class Capability(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val memberId: Long,
    val attributeDefinitionId: Long,
    val level: Int? = null,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
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
    val feasSignal: String? = null,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)

@Table("project_member")
data class ProjectMember(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val projectId: Long,
    val memberId: Long,
    val role: String = ROLE_MEMBER,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
) {
    companion object {
        const val ROLE_MEMBER = "member"
        const val ROLE_LEADER = "leader"
    }
}

@Table("status")
data class Status(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val projectId: Long,
    val name: String,
    val icon: String? = null,
    val isFinal: Boolean = false,
    val isStart: Boolean = false,
    val wipLimit: Int? = null,
    val position: Int = 0,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)

@Table("status_transition")
data class StatusTransition(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val projectId: Long,
    val fromStatusId: Long,
    val toStatusId: Long,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
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
    val startDate: LocalDate? = null,
    val dueDate: LocalDate? = null,
    val feasSignal: String? = null,
    val priority: String? = null,
    val deletedAt: Instant? = null,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)

/** V7:站内通知(收件人维度;动作按钮读时现算,不落库)。 */
@Table("notification")
data class Notification(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val recipientMemberId: Long,
    val itemId: Long,
    val kind: String,
    val actorMemberId: Long? = null,
    val oldValue: String? = null,
    val newValue: String? = null,
    val readAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
)

/** V9:item 检查清单条目(text + done,随 item 级联)。 */
@Table("checklist_item")
data class ChecklistItem(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val itemId: Long,
    val text: String,
    val done: Boolean = false,
    val position: Int = 0,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)

/** V9:item 依赖(阻塞关系):本 item 依赖 depends_on_item;依赖未终态 → 本 item 被阻塞。 */
@Table("item_dependency")
data class ItemDependency(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val itemId: Long,
    val dependsOnItemId: Long,
    val createdAt: Instant = Instant.now(),
)

/** V9:成员收藏的项目(跨浏览器/重装持久)。 */
@Table("favorite")
data class Favorite(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val memberId: Long,
    val projectId: Long,
    val createdAt: Instant = Instant.now(),
)

/** V8:工时(计时器 ended_at 空=计时中 + 手动补录 minutes)。 */
@Table("time_entry")
data class TimeEntry(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val itemId: Long,
    val memberId: Long,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val minutes: Int? = null,
    val note: String? = null,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant? = null,
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)

/** V6:项目挂载的 GitHub 仓库(monorepo 1 个,前后端分离/微服务多个);无 default 标记。 */
@Table("project_repo")
data class ProjectRepo(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val projectId: Long,
    val repo: String,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)

/** V6:item↔GitHub 分支/PR 链接;YPJ-39 起分支/PR 可挂多票(uk 每票唯一)。 */
@Table("item_git_ref")
data class ItemGitRef(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val itemId: Long,
    val kind: String,
    val repo: String,
    val ref: String,
    val url: String? = null,
    val state: String? = null,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)

/** V6:每项目「GitHub 事件→目标状态」映射(Kaneo WorkflowRule 同款);GitHub 永不知道状态名。 */
@Table("project_workflow_rule")
data class ProjectWorkflowRule(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val projectId: Long,
    val eventType: String,
    val statusObjectId: UUID,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)

@Table("item_attribute")
data class ItemAttribute(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val itemId: Long,
    val attributeDefinitionId: Long,
    val value: String,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)

@Table("requirement")
data class Requirement(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val itemId: Long,
    val attributeDefinitionId: Long,
    val minLevel: Int? = null,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)

@Table("item_group")
data class ItemGroup(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val projectId: Long,
    val type: String = "sprint",
    val name: String,
    val status: String = "planned",
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val releasedDate: LocalDate? = null,
    val milestoneId: Long? = null,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)

/** V35 YPJ-48:项目级 group 类型运行时定义(PRD §6);builtin 种子 sprint/release,自定义类型走最小语义。 */
@Table("item_group_type")
data class ItemGroupType(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val projectId: Long,
    val typeKey: String,
    val name: String,
    val builtin: Boolean = false,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)

/** item × group 多对多;不变量「至多一个 planned/active sprint」在 SprintService 保证(见 spec 0010 裁决 2);release 归属同理单选(release 端点语义,spec 0012)。 */
@Table("item_group_member")
data class ItemGroupMember(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val groupId: Long,
    val itemId: Long,
    val createdAt: Instant = Instant.now(),
)

/** V32 YPJ-54:成员加/离留痕(episode 式);同组同 item 至多一行 removed_at 为 null(部分唯一索引 ux_igmh_open)。 */
@Table("item_group_member_history")
data class ItemGroupMemberHistory(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val groupId: Long,
    val itemId: Long,
    val addedAt: Instant,
    val removedAt: Instant? = null,
)

/** V14 Milestone:项目时间轴目标节点(PRD §9);非 ItemGroup;0..1 in_progress 由 ux_milestone_project_current 硬约束。 */
@Table("milestone")
data class Milestone(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val projectId: Long,
    val name: String,
    val status: String = "planned",
    val targetDate: LocalDate? = null,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)

/** YPJ-47 视图配置:表格筛选/列配置按 用户×项目 私有保存(spec 0045)。filters/columns 为小 JSON 串(TEXT 列)。 */
@Table("project_view")
data class ProjectView(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val projectId: Long,
    val creatorId: Long,
    val name: String,
    val filters: String = "{}",
    val columns: String = "{}",
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)

/** YPJ-50:item 附件元数据;字节在 S3 兼容存储(presign 直传直取),objectKey 即桶内路径。 */
@Table("attachment")
data class Attachment(
    @Id val id: Long? = null,
    val objectId: UUID = UUID.randomUUID(),
    val itemId: Long,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val objectKey: String,
    @CreatedDate
    var createdAt: Instant = Instant.now(),
    @LastModifiedDate
    var updatedAt: Instant = Instant.now(),
    @CreatedBy
    var createdBy: Long? = null,
    @LastModifiedBy
    var updatedBy: Long? = null,
)
