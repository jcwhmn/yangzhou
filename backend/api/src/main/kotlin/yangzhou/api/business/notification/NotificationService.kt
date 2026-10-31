package yangzhou.api.business.notification

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.business.member.MemberService
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Item
import yangzhou.persistence.Notification
import yangzhou.persistence.repository.CommentRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.NotificationRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.StatusRepository
import java.time.Instant
import java.util.UUID

/** 通知收件人 = assignee + 创建者 + 既有评论者(去重、排除操作者;GitHub 事件 actor null 不排除)。 */
const val KIND_STATUS = "status_changed"
const val KIND_ASSIGNED = "assigned"
const val KIND_COMMENTED = "commented"

/**
 * V7-S1:站内通知。触发方(ItemService/CommentService/GithubSyncService)只管喊,
 * 收件人推导集中在这里;动作按钮读时现算「下一列」,不落库、不硬编码状态名。
 */
@Service
class NotificationService(
    private val notifications: NotificationRepository,
    private val items: ItemRepository,
    private val projects: ProjectRepository,
    private val statuses: StatusRepository,
    private val members: MemberRepository,
    private val comments: CommentRepository,
    private val memberService: MemberService,
) {
    // ---------- 触发 ----------

    fun notifyStatusChange(
        item: Item,
        actorMemberObjectId: UUID?,
        oldValue: String?,
        newValue: String?,
    ) = notify(item, KIND_STATUS, actorMemberObjectId, oldValue, newValue)

    fun notifyAssigned(
        item: Item,
        actorMemberObjectId: UUID?,
        assigneeMemberObjectId: UUID,
        assigneeName: String?,
    ) {
        if (actorMemberObjectId != null && actorMemberObjectId == assigneeMemberObjectId) return // 自己指派自己不通知
        save(assigneeMemberObjectId, item, KIND_ASSIGNED, actorMemberObjectId, null, assigneeName)
    }

    fun notifyComment(
        item: Item,
        actorMemberObjectId: UUID,
        excerpt: String,
    ) = notify(item, KIND_COMMENTED, actorMemberObjectId, null, excerpt)

    private fun notify(
        item: Item,
        kind: String,
        actorMemberObjectId: UUID?,
        oldValue: String?,
        newValue: String?,
    ) {
        val recipients = baseRecipients(item) - setOfNotNull(actorMemberObjectId)
        recipients.forEach { save(it, item, kind, actorMemberObjectId, oldValue, newValue) }
    }

    private fun save(
        recipientMemberObjectId: UUID,
        item: Item,
        kind: String,
        actorMemberObjectId: UUID?,
        oldValue: String?,
        newValue: String?,
    ) {
        notifications.save(
            Notification(
                recipientMemberObjectId = recipientMemberObjectId,
                itemObjectId = item.objectId!!,
                kind = kind,
                actorMemberObjectId = actorMemberObjectId,
                oldValue = oldValue,
                newValue = newValue,
            ),
        )
    }

    private fun baseRecipients(item: Item): Set<UUID> {
        val ids = mutableSetOf<UUID>()
        item.assigneeObjectId?.let { members.findByObjectId(it)?.objectId?.let(ids::add) }
        item.createdByObjectId?.let(ids::add)
        comments.findByItemObjectId(item.objectId!!).forEach { ids.add(it.authorMemberObjectId) }
        return ids
    }

    // ---------- 读取 ----------

    data class ActionDto(
        val statusItemId: UUID,
        val statusName: String,
    )

    data class NotificationDto(
        val notificationId: UUID,
        val itemId: UUID,
        val number: String,
        val projectKey: String,
        val title: String,
        val kind: String,
        val actor: String?,
        val oldValue: String?,
        val newValue: String?,
        val read: Boolean,
        val createdAt: String,
        val action: ActionDto?,
    )

    /** 自己的通知;未读在前,最近 50 条;action = 下一列(当前非 final 才有),读时现算。 */
    fun list(): List<NotificationDto> {
        val meId = memberService.current().objectId!!
        val rows =
            notifications
                .findByRecipientMemberObjectId(meId)
                .sortedWith(compareByDescending<Notification> { it.readAt == null }.thenByDescending { it.objectId })
                .take(50)
        if (rows.isEmpty()) return emptyList()
        val itemMap = items.findAllByObjectIdIn(rows.map { it.itemObjectId }).associateBy { it.objectId }
        val actorMap = members.findAllByObjectIdIn(rows.mapNotNull { it.actorMemberObjectId }.toSet()).associateBy { it.objectId }
        val statusCache = mutableMapOf<UUID, List<yangzhou.persistence.Status>>()
        return rows.map { n ->
            val item = itemMap.getValue(n.itemObjectId)
            val project = projects.findByObjectId(item.projectObjectId)
            val sts = statusCache.getOrPut(item.projectObjectId) { statuses.findByProjectObjectIdOrderByPosition(item.projectObjectId) }
            val current = sts.firstOrNull { it.objectId == item.statusObjectId }
            val action =
                if (current != null && !current.isFinal) {
                    sts
                        .filter { it.position > current.position }
                        .minByOrNull { it.position }
                        ?.let { ActionDto(it.objectId, it.name) }
                } else {
                    null
                }
            NotificationDto(
                notificationId = n.objectId,
                itemId = item.objectId,
                number = "${project?.key ?: "?"}-${item.number}",
                projectKey = project?.key ?: "?",
                title = item.title,
                kind = n.kind,
                actor = n.actorMemberObjectId?.let { actorMap[it]?.displayName },
                oldValue = n.oldValue,
                newValue = n.newValue,
                read = n.readAt != null,
                createdAt = n.createdAt.toString(),
                action = action,
            )
        }
    }

    fun unreadCount(): Long = notifications.countByRecipientMemberObjectIdAndReadAtIsNull(memberService.current().objectId!!)

    @Transactional
    fun markRead(notificationId: UUID) {
        val meId = memberService.current().objectId!!
        val n = notifications.findByObjectId(notificationId) ?: throw NotFoundException("通知不存在")
        if (n.recipientMemberObjectId != meId) throw NotFoundException("通知不存在")
        if (n.readAt == null) notifications.save(n.copy(readAt = Instant.now()))
    }

    @Transactional
    fun readAll() {
        val meId = memberService.current().objectId!!
        val now = Instant.now()
        notifications
            .findByRecipientMemberObjectId(meId)
            .filter { it.readAt == null }
            .forEach { notifications.save(it.copy(readAt = now)) }
    }
}
