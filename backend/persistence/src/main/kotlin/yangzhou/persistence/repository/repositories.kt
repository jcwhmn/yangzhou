package yangzhou.persistence.repository

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.Repository
import yangzhou.persistence.AttributeDefinition
import yangzhou.persistence.Capability
import yangzhou.persistence.Comment
import yangzhou.persistence.ItemActivity
import yangzhou.persistence.ItemAttribute
import yangzhou.persistence.ChecklistItem
import yangzhou.persistence.ItemStatusHistory
import yangzhou.persistence.ItemDependency
import yangzhou.persistence.Attachment
import yangzhou.persistence.ItemGitRef
import yangzhou.persistence.Item
import yangzhou.persistence.Member
import yangzhou.persistence.Notification
import yangzhou.persistence.Favorite
import yangzhou.persistence.Project
import yangzhou.persistence.ProjectMember
import yangzhou.persistence.ProjectRepo
import yangzhou.persistence.ItemGroup
import yangzhou.persistence.ItemGroupType
import yangzhou.persistence.ItemGroupMember
import yangzhou.persistence.ItemGroupMemberHistory
import yangzhou.persistence.Milestone
import yangzhou.persistence.ProjectView
import yangzhou.persistence.ProjectWorkflowRule
import yangzhou.persistence.Requirement
import yangzhou.persistence.Status
import yangzhou.persistence.Team
import yangzhou.persistence.TimeEntry
import yangzhou.persistence.TeamMember
import yangzhou.persistence.StatusTransition
import yangzhou.persistence.Workspace
import java.util.UUID
import java.time.Instant
import java.time.LocalDate

interface WorkspaceRepository : CrudRepository<Workspace, Long> {
    /** D6(spec 0052):bootstrap 并发占位锁(事务级 advisory lock,事务提交自动释放);包一层 select 使行可映射。 */
    @org.springframework.data.jdbc.repository.query.Query("select 0 as locked where (select pg_advisory_xact_lock(hashtext('bootstrap'))) is null")
    fun lockBootstrap(): Int?
}

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
    fun findByKindAndCreatedAtBetween(kind: String, start: java.time.Instant, end: java.time.Instant): List<ItemActivity>
    fun countByActorMemberId(actorMemberId: Long?): Long
}

interface ItemStatusHistoryRepository : CrudRepository<ItemStatusHistory, Long> {
    fun findByItemIdOrderByCreatedAtAsc(item_id: Long): List<ItemStatusHistory>
    fun countByActorMemberId(actorMemberId: Long?): Long
}

interface AttributeDefinitionRepository : CrudRepository<AttributeDefinition, Long> {
    fun findByWorkspaceId(workspaceId: Long): List<AttributeDefinition>
    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): AttributeDefinition?
    fun findByWorkspaceIdAndObjectId(workspaceId: Long, objectId: UUID): AttributeDefinition?
    fun existsByWorkspaceIdAndName(workspaceId: Long, name: String): Boolean
    fun findByWorkspaceIdAndParentIdIsNullOrderByKindAscIdAsc(workspaceId: Long): List<AttributeDefinition>
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
    fun findByItemId(itemId: Long): List<Comment>
    fun findByObjectId(objectId: UUID): Comment?
    fun countByAuthorMemberId(authorMemberId: Long): Long
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
    fun findByProjectIdAndDeletedAtIsNullOrderByNumber(projectId: Long): List<Item>
    fun findByDeletedAtIsNotNullOrderByDeletedAtDesc(): List<Item>
    fun findByProjectIdAndNumber(projectId: Long, number: Int): Item?
    fun findByAssigneeObjectIdAndDeletedAtIsNull(assigneeObjectId: UUID): List<Item>
    fun findByProjectIdAndExternalRef(projectId: Long, externalRef: String): Item?
    fun existsByParentObjectId(parentObjectId: UUID): Boolean
    fun findByObjectIdIn(objectIds: Collection<UUID>): List<Item>
}

interface ProjectRepoRepository : CrudRepository<ProjectRepo, Long> {
    fun findByProjectId(projectId: Long): List<ProjectRepo>
    fun findByProjectIdAndRepo(projectId: Long, repo: String): ProjectRepo?
    fun findByObjectId(objectId: UUID): ProjectRepo?
    fun existsByProjectIdAndRepo(projectId: Long, repo: String): Boolean
    fun findByRepo(repo: String): List<ProjectRepo>
}

interface ItemGitRefRepository : CrudRepository<ItemGitRef, Long> {
    fun findByItemId(itemId: Long): List<ItemGitRef>
    fun findByRepoAndKindAndRef(repo: String, kind: String, ref: String): List<ItemGitRef>

    /** YPJ-39:补挂行继承锚点行的入册时刻(@CreatedDate 审计会覆盖实体显式值,落库后校正)。 */
    @Modifying
    @Query("update item_git_ref set created_at = :createdAt where id = :id")
    fun updateCreatedAt(id: Long, createdAt: java.time.Instant): Int
}

/** YPJ-50:附件元数据;objectKey 唯一,行随 item 硬删级联。 */
interface AttachmentRepository : CrudRepository<Attachment, Long> {
    fun findByItemId(itemId: Long): List<Attachment>
    fun findByObjectIdAndItemId(objectId: UUID, itemId: Long): Attachment?
}
interface ProjectWorkflowRuleRepository : CrudRepository<ProjectWorkflowRule, Long> {
    fun findByProjectId(projectId: Long): List<ProjectWorkflowRule>
    fun findByProjectIdAndEventType(projectId: Long, eventType: String): ProjectWorkflowRule?
    fun deleteByProjectId(projectId: Long)
}

interface NotificationRepository : CrudRepository<Notification, Long> {
    fun findByRecipientMemberId(recipientMemberId: Long): List<Notification>
    fun findByObjectId(objectId: UUID): Notification?
    fun countByRecipientMemberIdAndReadAtIsNull(recipientMemberId: Long): Long
    fun countByActorMemberId(actorMemberId: Long?): Long
}

interface TimeEntryRepository : CrudRepository<TimeEntry, Long> {
    fun findByItemIdOrderByStartedAtDesc(itemId: Long): List<TimeEntry>
    fun findByMemberIdAndEndedAtIsNull(memberId: Long): List<TimeEntry>
    fun findByItemIdIn(itemIds: Collection<Long>): List<TimeEntry>
    fun findByObjectId(objectId: UUID): TimeEntry?
    fun countByMemberId(memberId: Long): Long
}

interface ChecklistItemRepository : CrudRepository<ChecklistItem, Long> {
    fun findByItemIdOrderByPositionAscIdAsc(itemId: Long): List<ChecklistItem>
    fun findByObjectId(objectId: UUID): ChecklistItem?
    fun deleteByObjectId(objectId: UUID)
}

interface ItemDependencyRepository : CrudRepository<ItemDependency, Long> {
    fun findByItemId(itemId: Long): List<ItemDependency>
    fun findByItemIdIn(itemIds: Collection<Long>): List<ItemDependency>
    fun findByDependsOnItemId(dependsOnItemId: Long): List<ItemDependency>
    fun findByObjectId(objectId: UUID): ItemDependency?
    fun existsByItemIdAndDependsOnItemId(itemId: Long, dependsOnItemId: Long): Boolean
    fun deleteByObjectId(objectId: UUID)
}

interface FavoriteRepository : CrudRepository<Favorite, Long> {
    fun findByMemberIdOrderByCreatedAtAsc(memberId: Long): List<Favorite>
    fun findByMemberIdAndProjectId(memberId: Long, projectId: Long): Favorite?
    fun existsByMemberIdAndProjectId(memberId: Long, projectId: Long): Boolean
    fun deleteByMemberIdAndProjectId(memberId: Long, projectId: Long)
}

interface ItemAttributeRepository : CrudRepository<ItemAttribute, Long> {
    fun findByItemId(itemId: Long): List<ItemAttribute>
    fun findByItemIdIn(itemIds: Collection<Long>): List<ItemAttribute>
    fun deleteByItemId(itemId: Long)
}

interface RequirementRepository : CrudRepository<Requirement, Long> {
    fun findByItemId(itemId: Long): List<Requirement>
    fun findByItemIdIn(itemIds: Collection<Long>): List<Requirement>
    fun deleteByItemId(itemId: Long)
}

interface ItemGroupRepository : CrudRepository<ItemGroup, Long> {
    fun findByProjectIdOrderByCreatedAt(projectId: Long): List<ItemGroup>
    fun findByProjectIdAndObjectId(projectId: Long, objectId: UUID): ItemGroup?
}

interface ItemGroupTypeRepository : CrudRepository<ItemGroupType, Long> {
    fun findByProjectIdOrderByCreatedAt(projectId: Long): List<ItemGroupType>
    fun findByProjectIdAndTypeKey(projectId: Long, typeKey: String): ItemGroupType?
    fun existsByProjectIdAndTypeKey(projectId: Long, typeKey: String): Boolean
}

interface ItemGroupMemberRepository : CrudRepository<ItemGroupMember, Long> {
    fun findByGroupId(groupId: Long): List<ItemGroupMember>
    fun findByGroupIdIn(groupIds: Collection<Long>): List<ItemGroupMember>
    fun existsByGroupId(groupId: Long): Boolean
    fun findByItemIdIn(itemIds: Collection<Long>): List<ItemGroupMember>
}

/** V32 YPJ-54:成员加/离留痕(episode 式);recordAdd/recordRemove 供服务写入点在既有事务内调用。 */
interface ItemGroupMemberHistoryRepository : CrudRepository<ItemGroupMemberHistory, Long> {
    fun findByGroupId(groupId: Long): List<ItemGroupMemberHistory>

    fun findFirstByGroupIdAndItemIdAndRemovedAtIsNull(groupId: Long, itemId: Long): ItemGroupMemberHistory?

    fun recordAdd(groupId: Long, itemId: Long, at: Instant): ItemGroupMemberHistory =
        save(ItemGroupMemberHistory(groupId = groupId, itemId = itemId, addedAt = at))

    /** 无在册行即 no-op(存量态/幂等防护);removed 不可回填,见 spec 0041。 */
    fun recordRemove(groupId: Long, itemId: Long, at: Instant) {
        findFirstByGroupIdAndItemIdAndRemovedAtIsNull(groupId, itemId)?.let { save(it.copy(removedAt = at)) }
    }
}

interface MilestoneRepository : CrudRepository<Milestone, Long> {
    fun findByProjectIdOrderByCreatedAt(projectId: Long): List<Milestone>
    fun findByProjectIdAndObjectId(projectId: Long, objectId: UUID): Milestone?
    fun findByProjectIdAndStatus(projectId: Long, status: String): List<Milestone>
}

// ---------- YPJ-45 Flow Metrics 只读聚合(spec 0037/0038)----------
// Spring Data JDBC @Query 自定义查询(ADR-0004 唯一授权的持久层扩展点);
// 列别名 snake_case(经命名策略映射到 DTO 驼峰属性,实测引号驼峰脱靶);出参一律公共 id(UUID)。

interface MetricsRepository : Repository<Status, Long> {
    @Query(
        """SELECT s.id AS status_id, s.object_id AS status_object_id, s.name,
            s.is_final, s.is_start, s.position
            FROM status s JOIN project p ON p.id = s.project_id
            WHERE p.key = :key ORDER BY s.position""",
    )
    fun statuses(key: String): List<MetricsStatusRow>

    @Query(
        """SELECT i.id AS item_id, i.object_id AS item_object_id, i.number,
            i.title, i.type, i.status_object_id,
            i.assignee_object_id, i.created_at
            FROM item i JOIN project p ON p.id = i.project_id
            WHERE p.key = :key AND i.deleted_at IS NULL""",
    )
    fun items(key: String): List<MetricsItemRow>

    @Query(
        """SELECT h.item_id, h.from_status_id,
            h.to_status_id, h.created_at
            FROM item_status_history h
            JOIN item i ON i.id = h.item_id JOIN project p ON p.id = i.project_id
            WHERE p.key = :key ORDER BY h.item_id, h.created_at""",
    )
    fun history(key: String): List<MetricsHistoryRow>

    @Query(
        """SELECT DISTINCT m.id AS member_id, m.object_id AS member_object_id, m.display_name
            FROM member m
            JOIN item i ON i.assignee_object_id = m.object_id
            JOIN project p ON p.id = i.project_id
            WHERE p.key = :key AND i.deleted_at IS NULL""",
    )
    fun assignees(key: String): List<MetricsMemberRow>

    @Query(
        """SELECT g.id AS group_id, g.object_id AS group_object_id, g.type, g.name,
            g.status, g.start_date, g.end_date, g.created_at
            FROM item_group g JOIN project p ON p.id = g.project_id
            WHERE p.key = :key AND (:type IS NULL OR g.type = :type)
            ORDER BY g.start_date NULLS LAST, g.id""",
    )
    fun groups(key: String, type: String?): List<MetricsGroupRow>

    @Query(
        """SELECT gm.group_id, gm.item_id, gm.created_at
            FROM item_group_member gm
            JOIN item_group g ON g.id = gm.group_id JOIN project p ON p.id = g.project_id
            WHERE p.key = :key""",
    )
    fun groupMembers(key: String): List<MetricsGroupMemberRow>
}

data class MetricsStatusRow(
    val statusId: Long,
    val statusObjectId: UUID,
    val name: String,
    val isFinal: Boolean,
    val isStart: Boolean,
    val position: Int,
)

data class MetricsItemRow(
    val itemId: Long,
    val itemObjectId: UUID,
    val number: Int,
    val title: String,
    val type: String,
    val statusObjectId: UUID,
    val assigneeObjectId: UUID?,
    val createdAt: Instant,
)

data class MetricsHistoryRow(
    val itemId: Long,
    val fromStatusId: Long?,
    val toStatusId: Long,
    val createdAt: Instant,
)

data class MetricsMemberRow(
    val memberId: Long,
    val memberObjectId: UUID,
    val displayName: String,
)

data class MetricsGroupRow(
    val groupId: Long,
    val groupObjectId: UUID,
    val type: String,
    val name: String,
    val status: String,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val createdAt: Instant,
)

data class MetricsGroupMemberRow(
    val groupId: Long,
    val itemId: Long,
    val createdAt: Instant,
)

interface ProjectViewRepository : CrudRepository<ProjectView, Long> {
    fun findByProjectIdAndCreatorIdOrderByCreatedAt(projectId: Long, creatorId: Long): List<ProjectView>
    fun findByObjectIdAndCreatorId(objectId: UUID, creatorId: Long): ProjectView?
    fun existsByProjectIdAndCreatorIdAndName(projectId: Long, creatorId: Long, name: String): Boolean
    fun countByCreatorId(creatorId: Long): Long
}


// ---------- YPJ-64 持久层收敛(spec 0049):JDBC client 清退,@Query 唯一扩展点 ----------

data class SearchHitRow(
    val itemId: UUID,
    val projectKey: String,
    val number: String,
    val title: String,
    val status: String?,
)

interface SearchRepository : Repository<Item, Long> {
    @Query(
        """SELECT i.object_id AS item_id, p.key AS project_key,
            p.key || '-' || i.number AS number, i.title, s.name AS status
            FROM item i JOIN project p ON i.project_id = p.id
            LEFT JOIN status s ON i.status_object_id = s.object_id
            WHERE (i.title ILIKE :q ESCAPE '\' OR i.description ILIKE :q ESCAPE '\' OR (p.key || '-' || i.number::text) ILIKE :q ESCAPE '\'
                OR EXISTS (SELECT 1 FROM item_attribute ia WHERE ia.item_id = i.id AND ia.value ILIKE :q ESCAPE '\'))
            AND i.deleted_at IS NULL
            ORDER BY p.key, i.number
            LIMIT 50""",
    )
    fun search(q: String): List<SearchHitRow>
}

data class DeletionPreviewRow(
    val itemCount: Long,
    val groupCount: Long,
    val milestoneCount: Long,
    val viewCount: Long,
    val statusCount: Long,
    val memberCount: Long,
)

interface ProjectDeletionRepository : Repository<Project, Long> {
    @Query(
        """SELECT (SELECT count(*) FROM item WHERE project_id = :pid)           AS item_count,
            (SELECT count(*) FROM item_group WHERE project_id = :pid)     AS group_count,
            (SELECT count(*) FROM milestone WHERE project_id = :pid)      AS milestone_count,
            (SELECT count(*) FROM project_view WHERE project_id = :pid)   AS view_count,
            (SELECT count(*) FROM status WHERE project_id = :pid)         AS status_count,
            (SELECT count(*) FROM project_member WHERE project_id = :pid) AS member_count""",
    )
    fun deletionPreview(pid: Long): DeletionPreviewRow

    @Modifying
    @Query("DELETE FROM requirement WHERE item_id IN (SELECT id FROM item WHERE project_id = :pid)")
    fun purgeRequirements(pid: Long): Int

    @Modifying
    @Query(
        "DELETE FROM item_group_member WHERE item_id IN (SELECT id FROM item WHERE project_id = :pid) " +
            "OR group_id IN (SELECT id FROM item_group WHERE project_id = :pid)",
    )
    fun purgeItemGroupMembers(pid: Long): Int

    @Modifying
    @Query("DELETE FROM item WHERE project_id = :pid")
    fun purgeItems(pid: Long): Int

    @Modifying
    @Query("DELETE FROM item_group WHERE project_id = :pid")
    fun purgeItemGroups(pid: Long): Int

    @Modifying
    @Query("DELETE FROM item_group_type WHERE project_id = :pid")
    fun purgeItemGroupTypes(pid: Long): Int

    @Modifying
    @Query("DELETE FROM milestone WHERE project_id = :pid")
    fun purgeMilestones(pid: Long): Int

    @Modifying
    @Query("DELETE FROM status WHERE project_id = :pid")
    fun purgeStatuses(pid: Long): Int

    @Modifying
    @Query("DELETE FROM project_view WHERE project_id = :pid")
    fun purgeProjectViews(pid: Long): Int

    @Modifying
    @Query("UPDATE member SET last_project_key = NULL WHERE last_project_key = :key")
    fun clearLastProjectKey(key: String): Int

    @Modifying
    @Query("DELETE FROM project WHERE id = :pid")
    fun deleteProject(pid: Long): Int
}

interface ItemTreeRepository : Repository<Item, Long> {
    /** 软删/恢复共用:at=null 即恢复(替换旧字符串插值,参数化)。 */
    @Modifying
    @Query(
        """WITH RECURSIVE subtree AS (
            SELECT id, object_id FROM item WHERE id = :rootId
            UNION ALL
            SELECT i.id, i.object_id FROM item i JOIN subtree s ON i.parent_object_id = s.object_id
        )
        UPDATE item SET deleted_at = :at WHERE id IN (SELECT id FROM subtree)""",
    )
    fun updateSubtreeDeletedAt(rootId: Long, at: java.time.Instant?): Int

    @Modifying
    @Query(
        """WITH RECURSIVE subtree AS (
            SELECT id, object_id FROM item WHERE id = :rootId
            UNION ALL
            SELECT i.id, i.object_id FROM item i JOIN subtree s ON i.parent_object_id = s.object_id
        )
        DELETE FROM requirement WHERE item_id IN (SELECT id FROM subtree)""",
    )
    fun purgeRequirementsInSubtree(rootId: Long): Int

    @Modifying
    @Query(
        """WITH RECURSIVE subtree AS (
            SELECT id, object_id FROM item WHERE id = :rootId
            UNION ALL
            SELECT i.id, i.object_id FROM item i JOIN subtree s ON i.parent_object_id = s.object_id
        )
        DELETE FROM item WHERE id IN (SELECT id FROM subtree)""",
    )
    fun purgeItemsInSubtree(rootId: Long): Int

    @Query("SELECT parent_object_id FROM item WHERE object_id = :objectId")
    fun findParentObjectId(objectId: UUID): UUID?
}
