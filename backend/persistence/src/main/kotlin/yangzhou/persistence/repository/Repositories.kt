package yangzhou.persistence.repository

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.Repository
import yangzhou.persistence.Attachment
import yangzhou.persistence.AttributeDefinition
import yangzhou.persistence.Capability
import yangzhou.persistence.ChecklistItem
import yangzhou.persistence.Comment
import yangzhou.persistence.Favorite
import yangzhou.persistence.Item
import yangzhou.persistence.ItemActivity
import yangzhou.persistence.ItemAttribute
import yangzhou.persistence.ItemDependency
import yangzhou.persistence.ItemGitRef
import yangzhou.persistence.ItemGroup
import yangzhou.persistence.ItemGroupMember
import yangzhou.persistence.ItemGroupMemberHistory
import yangzhou.persistence.ItemGroupType
import yangzhou.persistence.ItemStatusHistory
import yangzhou.persistence.Member
import yangzhou.persistence.Milestone
import yangzhou.persistence.Notification
import yangzhou.persistence.Project
import yangzhou.persistence.ProjectMember
import yangzhou.persistence.ProjectRepo
import yangzhou.persistence.ProjectView
import yangzhou.persistence.ProjectWorkflowRule
import yangzhou.persistence.Requirement
import yangzhou.persistence.Status
import yangzhou.persistence.StatusTransition
import yangzhou.persistence.Team
import yangzhou.persistence.TeamMember
import yangzhou.persistence.TimeEntry
import yangzhou.persistence.Workspace
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface WorkspaceRepository : CrudRepository<Workspace, Long> {
    /** D6(spec 0052):bootstrap 并发占位锁(事务级 advisory lock,事务提交自动释放);包一层 select 使行可映射。 */
    @org.springframework.data.jdbc.repository.query.Query(
        "select 0 as locked where (select pg_advisory_xact_lock(hashtext('bootstrap'))) is null",
    )
    fun lockBootstrap(): Int?
}

interface MemberRepository : CrudRepository<Member, Long> {
    fun findAllByObjectIdIn(objectIds: Collection<UUID>): List<Member>

    fun findByUsername(username: String): Member?

    fun findByObjectId(objectId: UUID): Member?

    fun findByWorkspaceObjectId(workspaceObjectId: UUID): List<Member>

    fun findByWorkspaceObjectIdAndGithubUsername(
        workspaceObjectId: UUID,
        githubUsername: String,
    ): Member?
}

interface TeamRepository : CrudRepository<Team, Long> {
    fun findByWorkspaceObjectId(workspaceObjectId: UUID): List<Team>

    fun findByWorkspaceObjectIdAndName(
        workspaceObjectId: UUID,
        name: String,
    ): Team?

    fun findByObjectId(objectId: UUID): Team?

    fun existsByWorkspaceObjectIdAndName(
        workspaceObjectId: UUID,
        name: String,
    ): Boolean
}

interface TeamMemberRepository : CrudRepository<TeamMember, Long> {
    fun findByTeamObjectId(teamObjectId: UUID): List<TeamMember>

    fun existsByTeamObjectIdAndMemberObjectId(
        teamObjectId: UUID,
        memberObjectId: UUID,
    ): Boolean
}

interface ItemActivityRepository : CrudRepository<ItemActivity, Long> {
    fun findByItemObjectIdOrderByCreatedAtDesc(item_object_id: UUID): List<ItemActivity>

    fun findByKindAndCreatedAtBetween(
        kind: String,
        start: java.time.Instant,
        end: java.time.Instant,
    ): List<ItemActivity>

    fun countByActorMemberObjectId(actorMemberObjectId: UUID?): Long
}

interface ItemStatusHistoryRepository : CrudRepository<ItemStatusHistory, Long> {
    fun findByItemObjectIdOrderByCreatedAtAsc(item_object_id: UUID): List<ItemStatusHistory>

    fun countByActorMemberObjectId(actorMemberObjectId: UUID?): Long
}

interface AttributeDefinitionRepository : CrudRepository<AttributeDefinition, Long> {
    fun findAllByObjectIdIn(objectIds: Collection<UUID>): List<AttributeDefinition>

    fun findByWorkspaceObjectId(workspaceObjectId: UUID): List<AttributeDefinition>

    fun findByWorkspaceObjectIdAndName(
        workspaceObjectId: UUID,
        name: String,
    ): AttributeDefinition?

    fun findByWorkspaceObjectIdAndObjectId(
        workspaceObjectId: UUID,
        objectId: UUID,
    ): AttributeDefinition?

    fun existsByWorkspaceObjectIdAndName(
        workspaceObjectId: UUID,
        name: String,
    ): Boolean

    fun findByWorkspaceObjectIdAndParentObjectIdIsNullOrderByKindAscIdAsc(workspaceObjectId: UUID): List<AttributeDefinition>
}

interface CapabilityRepository : CrudRepository<Capability, Long> {
    fun findByMemberObjectId(memberObjectId: UUID): List<Capability>

    fun findByMemberObjectIdAndAttributeDefinitionObjectId(
        memberObjectId: UUID,
        attributeDefinitionObjectId: UUID,
    ): Capability?

    fun deleteByMemberObjectId(memberObjectId: UUID)
}

interface ProjectRepository : CrudRepository<Project, Long> {
    fun findByKey(key: String): Project?

    fun findByObjectId(objectId: UUID): Project?

    fun existsByKey(key: String): Boolean
}

interface ProjectMemberRepository : CrudRepository<ProjectMember, Long> {
    fun findByProjectObjectId(projectObjectId: UUID): List<ProjectMember>

    fun findByProjectObjectIdOrderByIdAsc(projectObjectId: UUID): List<ProjectMember>

    fun existsByProjectObjectIdAndMemberObjectId(
        projectObjectId: UUID,
        memberObjectId: UUID,
    ): Boolean

    fun findByProjectObjectIdAndMemberObjectId(
        projectObjectId: UUID,
        memberObjectId: UUID,
    ): ProjectMember?
}

interface CommentRepository : CrudRepository<Comment, Long> {
    fun findByItemObjectIdOrderByCreatedAtDesc(itemObjectId: UUID): List<Comment>

    fun findByItemObjectId(itemObjectId: UUID): List<Comment>

    fun findByObjectId(objectId: UUID): Comment?

    fun countByAuthorMemberObjectId(authorMemberObjectId: UUID): Long
}

interface StatusRepository : CrudRepository<Status, Long> {
    fun findAllByObjectIdIn(objectIds: Collection<UUID>): List<Status>

    fun findByProjectObjectIdOrderByPosition(projectObjectId: UUID): List<Status>

    fun findByProjectObjectIdAndObjectId(
        projectObjectId: UUID,
        objectId: UUID,
    ): Status?

    fun findByObjectId(objectId: UUID): Status?

    fun deleteByObjectId(objectId: UUID)
}

interface StatusTransitionRepository : CrudRepository<StatusTransition, Long> {
    fun findByProjectObjectId(projectObjectId: UUID): List<StatusTransition>

    fun deleteByProjectObjectId(projectObjectId: UUID)
}

interface ItemRepository : CrudRepository<Item, Long> {
    fun findByObjectId(objectId: UUID): Item?

    fun findAllByObjectIdIn(objectIds: Collection<UUID>): List<Item>

    fun findByProjectObjectIdAndStatusObjectId(
        projectObjectId: UUID,
        statusObjectId: UUID,
    ): List<Item>

    fun findByProjectObjectIdAndDeletedAtIsNullOrderByNumber(projectObjectId: UUID): List<Item>

    fun findByDeletedAtIsNotNullOrderByDeletedAtDesc(): List<Item>

    fun findByProjectObjectIdAndNumber(
        projectObjectId: UUID,
        number: Int,
    ): Item?

    fun findByAssigneeObjectIdAndDeletedAtIsNull(assigneeObjectId: UUID): List<Item>

    fun findByProjectObjectIdAndExternalRef(
        projectObjectId: UUID,
        externalRef: String,
    ): Item?

    fun existsByParentObjectId(parentObjectId: UUID): Boolean

    fun findByObjectIdIn(objectIds: Collection<UUID>): List<Item>
}

interface ProjectRepoRepository : CrudRepository<ProjectRepo, Long> {
    fun findByProjectObjectId(projectObjectId: UUID): List<ProjectRepo>

    fun findByProjectObjectIdAndRepo(
        projectObjectId: UUID,
        repo: String,
    ): ProjectRepo?

    fun findByObjectId(objectId: UUID): ProjectRepo?

    fun existsByProjectObjectIdAndRepo(
        projectObjectId: UUID,
        repo: String,
    ): Boolean

    fun findByRepo(repo: String): List<ProjectRepo>
}

interface ItemGitRefRepository : CrudRepository<ItemGitRef, Long> {
    fun findByItemObjectId(itemObjectId: UUID): List<ItemGitRef>

    fun findByRepoAndKindAndRef(
        repo: String,
        kind: String,
        ref: String,
    ): List<ItemGitRef>

    /** YPJ-39:补挂行继承锚点行的入册时刻(@CreatedDate 审计会覆盖实体显式值,落库后校正)。 */
    @Modifying
    @Query("update item_git_ref set created_at = :createdAt where id = :id")
    fun updateCreatedAt(
        id: Long,
        createdAt: java.time.Instant,
    ): Int
}

/** YPJ-50:附件元数据;objectKey 唯一,行随 item 硬删级联。 */
interface AttachmentRepository : CrudRepository<Attachment, Long> {
    fun findByItemObjectId(itemObjectId: UUID): List<Attachment>

    fun findByObjectIdAndItemObjectId(
        objectId: UUID,
        itemObjectId: UUID,
    ): Attachment?
}

interface ProjectWorkflowRuleRepository : CrudRepository<ProjectWorkflowRule, Long> {
    fun findByProjectObjectId(projectObjectId: UUID): List<ProjectWorkflowRule>

    fun findByProjectObjectIdAndEventType(
        projectObjectId: UUID,
        eventType: String,
    ): ProjectWorkflowRule?

    fun deleteByProjectObjectId(projectObjectId: UUID)
}

interface NotificationRepository : CrudRepository<Notification, Long> {
    fun findByRecipientMemberObjectId(recipientMemberObjectId: UUID): List<Notification>

    fun findByObjectId(objectId: UUID): Notification?

    fun countByRecipientMemberObjectIdAndReadAtIsNull(recipientMemberObjectId: UUID): Long

    fun countByActorMemberObjectId(actorMemberObjectId: UUID?): Long
}

interface TimeEntryRepository : CrudRepository<TimeEntry, Long> {
    fun findByItemObjectIdOrderByStartedAtDesc(itemObjectId: UUID): List<TimeEntry>

    fun findByMemberObjectIdAndEndedAtIsNull(memberObjectId: UUID): List<TimeEntry>

    fun findByItemObjectIdIn(itemObjectIds: Collection<UUID>): List<TimeEntry>

    fun findByObjectId(objectId: UUID): TimeEntry?

    fun countByMemberObjectId(memberObjectId: UUID): Long
}

interface ChecklistItemRepository : CrudRepository<ChecklistItem, Long> {
    fun findByItemObjectIdOrderByPositionAscIdAsc(itemObjectId: UUID): List<ChecklistItem>

    fun findByObjectId(objectId: UUID): ChecklistItem?

    fun deleteByObjectId(objectId: UUID)
}

interface ItemDependencyRepository : CrudRepository<ItemDependency, Long> {
    fun findByItemObjectId(itemObjectId: UUID): List<ItemDependency>

    fun findByItemObjectIdIn(itemObjectIds: Collection<UUID>): List<ItemDependency>

    fun findByDependsOnItemObjectId(dependsOnItemObjectId: UUID): List<ItemDependency>

    fun findByObjectId(objectId: UUID): ItemDependency?

    fun existsByItemObjectIdAndDependsOnItemObjectId(
        itemObjectId: UUID,
        dependsOnItemObjectId: UUID,
    ): Boolean

    fun deleteByObjectId(objectId: UUID)
}

interface FavoriteRepository : CrudRepository<Favorite, Long> {
    fun findByMemberObjectIdOrderByCreatedAtAsc(memberObjectId: UUID): List<Favorite>

    fun findByMemberObjectIdAndProjectObjectId(
        memberObjectId: UUID,
        projectObjectId: UUID,
    ): Favorite?

    fun existsByMemberObjectIdAndProjectObjectId(
        memberObjectId: UUID,
        projectObjectId: UUID,
    ): Boolean

    fun deleteByMemberObjectIdAndProjectObjectId(
        memberObjectId: UUID,
        projectObjectId: UUID,
    )
}

interface ItemAttributeRepository : CrudRepository<ItemAttribute, Long> {
    fun findByItemObjectId(itemObjectId: UUID): List<ItemAttribute>

    fun findByItemObjectIdIn(itemObjectIds: Collection<UUID>): List<ItemAttribute>

    fun deleteByItemObjectId(itemObjectId: UUID)
}

interface RequirementRepository : CrudRepository<Requirement, Long> {
    fun findByItemObjectId(itemObjectId: UUID): List<Requirement>

    fun findByItemObjectIdIn(itemObjectIds: Collection<UUID>): List<Requirement>

    fun deleteByItemObjectId(itemObjectId: UUID)
}

interface ItemGroupRepository : CrudRepository<ItemGroup, Long> {
    fun findAllByObjectIdIn(objectIds: Collection<UUID>): List<ItemGroup>

    fun findByProjectObjectIdOrderByCreatedAt(projectObjectId: UUID): List<ItemGroup>

    fun findByProjectObjectIdAndObjectId(
        projectObjectId: UUID,
        objectId: UUID,
    ): ItemGroup?
}

interface ItemGroupTypeRepository : CrudRepository<ItemGroupType, Long> {
    fun findByProjectObjectIdOrderByCreatedAt(projectObjectId: UUID): List<ItemGroupType>

    fun findByProjectObjectIdAndTypeKey(
        projectObjectId: UUID,
        typeKey: String,
    ): ItemGroupType?

    fun existsByProjectObjectIdAndTypeKey(
        projectObjectId: UUID,
        typeKey: String,
    ): Boolean
}

interface ItemGroupMemberRepository : CrudRepository<ItemGroupMember, Long> {
    fun findByGroupObjectId(groupObjectId: UUID): List<ItemGroupMember>

    fun findByGroupObjectIdIn(groupObjectIds: Collection<UUID>): List<ItemGroupMember>

    fun existsByGroupObjectId(groupObjectId: UUID): Boolean

    fun findByItemObjectIdIn(itemObjectIds: Collection<UUID>): List<ItemGroupMember>
}

/** V32 YPJ-54:成员加/离留痕(episode 式);recordAdd/recordRemove 供服务写入点在既有事务内调用。 */
interface ItemGroupMemberHistoryRepository : CrudRepository<ItemGroupMemberHistory, Long> {
    fun findByGroupObjectId(groupObjectId: UUID): List<ItemGroupMemberHistory>

    fun findFirstByGroupObjectIdAndItemObjectIdAndRemovedAtIsNull(
        groupObjectId: UUID,
        itemObjectId: UUID,
    ): ItemGroupMemberHistory?

    fun recordAdd(
        groupObjectId: UUID,
        itemObjectId: UUID,
        at: Instant,
    ): ItemGroupMemberHistory = save(ItemGroupMemberHistory(groupObjectId = groupObjectId, itemObjectId = itemObjectId, addedAt = at))

    /** 无在册行即 no-op(存量态/幂等防护);removed 不可回填,见 spec 0041。 */
    fun recordRemove(
        groupObjectId: UUID,
        itemObjectId: UUID,
        at: Instant,
    ) {
        findFirstByGroupObjectIdAndItemObjectIdAndRemovedAtIsNull(groupObjectId, itemObjectId)?.let { save(it.copy(removedAt = at)) }
    }
}

interface MilestoneRepository : CrudRepository<Milestone, Long> {
    fun findByObjectId(objectId: UUID): Milestone?

    fun findByProjectObjectIdOrderByCreatedAt(projectObjectId: UUID): List<Milestone>

    fun findByProjectObjectIdAndObjectId(
        projectObjectId: UUID,
        objectId: UUID,
    ): Milestone?

    fun findByProjectObjectIdAndStatus(
        projectObjectId: UUID,
        status: String,
    ): List<Milestone>
}

// ---------- YPJ-45 Flow Metrics 只读聚合(spec 0037/0038)----------
// Spring Data JDBC @Query 自定义查询(ADR-0004 唯一授权的持久层扩展点);
// 列别名 snake_case(经命名策略映射到 DTO 驼峰属性,实测引号驼峰脱靶);出参一律公共 id(UUID)。

interface MetricsRepository : Repository<Status, Long> {
    @Query(
        """SELECT s.object_id AS status_object_id, s.name,
            s.is_final, s.is_start, s.position
            FROM status s JOIN project p ON p.object_id = s.project_object_id
            WHERE p.key = :key ORDER BY s.position""",
    )
    fun statuses(key: String): List<MetricsStatusRow>

    @Query(
        """SELECT i.object_id AS item_object_id, i.number,
            i.title, i.type, i.status_object_id,
            i.assignee_object_id, i.created_at
            FROM item i JOIN project p ON p.object_id = i.project_object_id
            WHERE p.key = :key AND i.deleted_at IS NULL""",
    )
    fun items(key: String): List<MetricsItemRow>

    @Query(
        """SELECT h.item_object_id, h.from_status_object_id,
            h.to_status_object_id, h.created_at
            FROM item_status_history h
            JOIN item i ON i.object_id = h.item_object_id JOIN project p ON p.object_id = i.project_object_id
            WHERE p.key = :key ORDER BY h.item_object_id, h.created_at""",
    )
    fun history(key: String): List<MetricsHistoryRow>

    @Query(
        """SELECT DISTINCT m.object_id AS member_object_id, m.display_name
            FROM member m
            JOIN item i ON i.assignee_object_id = m.object_id
            JOIN project p ON p.object_id = i.project_object_id
            WHERE p.key = :key AND i.deleted_at IS NULL""",
    )
    fun assignees(key: String): List<MetricsMemberRow>

    @Query(
        """SELECT g.object_id AS group_object_id, g.type, g.name,
            g.status, g.start_date, g.end_date, g.created_at
            FROM item_group g JOIN project p ON p.object_id = g.project_object_id
            WHERE p.key = :key AND (:type IS NULL OR g.type = :type)
            ORDER BY g.start_date NULLS LAST, g.id""",
    )
    fun groups(
        key: String,
        type: String?,
    ): List<MetricsGroupRow>

    @Query(
        """SELECT gm.group_object_id, gm.item_object_id, gm.created_at
            FROM item_group_member gm
            JOIN item_group g ON g.object_id = gm.group_object_id JOIN project p ON p.object_id = g.project_object_id
            WHERE p.key = :key""",
    )
    fun groupMembers(key: String): List<MetricsGroupMemberRow>
}

data class MetricsStatusRow(
    val statusObjectId: UUID,
    val name: String,
    val isFinal: Boolean,
    val isStart: Boolean,
    val position: Int,
)

data class MetricsItemRow(
    val itemObjectId: UUID,
    val number: Int,
    val title: String,
    val type: String,
    val statusObjectId: UUID,
    val assigneeObjectId: UUID?,
    val createdAt: Instant,
)

data class MetricsHistoryRow(
    val itemObjectId: UUID,
    val fromStatusObjectId: UUID?,
    val toStatusObjectId: UUID,
    val createdAt: Instant,
)

data class MetricsMemberRow(
    val memberObjectId: UUID,
    val displayName: String,
)

data class MetricsGroupRow(
    val groupObjectId: UUID,
    val type: String,
    val name: String,
    val status: String,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val createdAt: Instant,
)

data class MetricsGroupMemberRow(
    val groupObjectId: UUID,
    val itemObjectId: UUID,
    val createdAt: Instant,
)

interface ProjectViewRepository : CrudRepository<ProjectView, Long> {
    fun findByProjectObjectIdAndCreatorObjectIdOrderByCreatedAt(
        projectObjectId: UUID,
        creatorObjectId: UUID,
    ): List<ProjectView>

    fun findByObjectIdAndCreatorObjectId(
        objectId: UUID,
        creatorObjectId: UUID,
    ): ProjectView?

    fun existsByProjectObjectIdAndCreatorObjectIdAndName(
        projectObjectId: UUID,
        creatorObjectId: UUID,
        name: String,
    ): Boolean

    fun countByCreatorObjectId(creatorObjectId: UUID): Long
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
            FROM item i JOIN project p ON i.project_object_id = p.object_id
            LEFT JOIN status s ON i.status_object_id = s.object_id
            WHERE (i.title ILIKE :q ESCAPE '\' OR i.description ILIKE :q ESCAPE '\' OR (p.key || '-' || i.number::text) ILIKE :q ESCAPE '\'
                OR EXISTS (SELECT 1 FROM item_attribute ia WHERE ia.item_object_id = i.object_id AND ia.value ILIKE :q ESCAPE '\'))
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
        """SELECT (SELECT count(*) FROM item WHERE project_object_id = :pid)           AS item_count,
            (SELECT count(*) FROM item_group WHERE project_object_id = :pid)     AS group_count,
            (SELECT count(*) FROM milestone WHERE project_object_id = :pid)      AS milestone_count,
            (SELECT count(*) FROM project_view WHERE project_object_id = :pid)   AS view_count,
            (SELECT count(*) FROM status WHERE project_object_id = :pid)         AS status_count,
            (SELECT count(*) FROM project_member WHERE project_object_id = :pid) AS member_count""",
    )
    fun deletionPreview(pid: UUID): DeletionPreviewRow

    @Modifying
    @Query("DELETE FROM requirement WHERE item_object_id IN (SELECT object_id FROM item WHERE project_object_id = :pid)")
    fun purgeRequirements(pid: UUID): Int

    @Modifying
    @Query(
        "DELETE FROM item_group_member WHERE item_object_id IN (SELECT object_id FROM item WHERE project_object_id = :pid) " +
            "OR group_object_id IN (SELECT object_id FROM item_group WHERE project_object_id = :pid)",
    )
    fun purgeItemGroupMembers(pid: UUID): Int

    @Modifying
    @Query("DELETE FROM item WHERE project_object_id = :pid")
    fun purgeItems(pid: UUID): Int

    @Modifying
    @Query("DELETE FROM item_group WHERE project_object_id = :pid")
    fun purgeItemGroups(pid: UUID): Int

    @Modifying
    @Query("DELETE FROM item_group_type WHERE project_object_id = :pid")
    fun purgeItemGroupTypes(pid: UUID): Int

    @Modifying
    @Query("DELETE FROM milestone WHERE project_object_id = :pid")
    fun purgeMilestones(pid: UUID): Int

    @Modifying
    @Query("DELETE FROM status WHERE project_object_id = :pid")
    fun purgeStatuses(pid: UUID): Int

    @Modifying
    @Query("DELETE FROM project_view WHERE project_object_id = :pid")
    fun purgeProjectViews(pid: UUID): Int

    @Modifying
    @Query("UPDATE member SET last_project_key = NULL WHERE last_project_key = :key")
    fun clearLastProjectKey(key: String): Int

    @Modifying
    @Query("DELETE FROM project WHERE object_id = :pid")
    fun deleteProject(pid: UUID): Int
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
    fun updateSubtreeDeletedAt(
        rootId: Long,
        at: java.time.Instant?,
    ): Int

    @Modifying
    @Query(
        """WITH RECURSIVE subtree AS (
            SELECT id, object_id FROM item WHERE id = :rootId
            UNION ALL
            SELECT i.id, i.object_id FROM item i JOIN subtree s ON i.parent_object_id = s.object_id
        )
        DELETE FROM requirement WHERE item_object_id IN (SELECT object_id FROM subtree)""",
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
