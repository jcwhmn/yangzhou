package yangzhou.api.notification

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.member.MemberService
import yangzhou.api.support.NotFoundException
import yangzhou.persistence.Item
import yangzhou.persistence.Notification
import yangzhou.persistence.repository.CommentRepository
import yangzhou.persistence.Comment
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

    fun notifyStatusChange(item: Item, actorMemberId: Long?, oldValue: String?, newValue: String?) =
        notify(item, KIND_STATUS, actorMemberId, oldValue, newValue)

    fun notifyAssigned(item: Item, actorMemberId: Long?, assigneeMemberId: Long, assigneeName: String?) {
        if (actorMemberId != null && actorMemberId == assigneeMemberId) return // 自己指派自己不通知
        save(assigneeMemberId, item, KIND_ASSIGNED, actorMemberId, null, assigneeName)
    }

    fun notifyComment(item: Item, actorMemberId: Long, excerpt: String) =
        notify(item, KIND_COMMENTED, actorMemberId, null, excerpt)

    private fun notify(item: Item, kind: String, actorMemberId: Long?, oldValue: String?, newValue: String?) {
        val recipients = baseRecipients(item) - setOfNotNull(actorMemberId)
        recipients.forEach { save(it, item, kind, actorMemberId, oldValue, newValue) }
    }

    private fun save(recipientMemberId: Long, item: Item, kind: String, actorMemberId: Long?, oldValue: String?, newValue: String?) {
        notifications.save(
            Notification(
                recipientMemberId = recipientMemberId,
                itemId = item.id!!,
                kind = kind,
                actorMemberId = actorMemberId,
                oldValue = oldValue,
                newValue = newValue,
            ),
        )
    }

    private fun baseRecipients(item: Item): Set<Long> {
        val ids = mutableSetOf<Long>()
        item.assigneeObjectId?.let { members.findByObjectId(it)?.id?.let(ids::add) }
        item.createdBy?.let(ids::add)
        comments.findByItemId(item.id!!).forEach { ids.add(it.authorMemberId) }
        return ids
    }

    // ---------- 读取 ----------

    data class ActionDto(val statusItemId: UUID, val statusName: String)

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
        val meId = memberService.current().id!!
        val rows = notifications.findByRecipientMemberId(meId)
            .sortedWith(compareByDescending<Notification> { it.readAt == null }.thenByDescending { it.id })
            .take(50)
        if (rows.isEmpty()) return emptyList()
        val itemMap = items.findAllById(rows.map { it.itemId }).associateBy { it.id }
        val actorMap = members.findAllById(rows.mapNotNull { it.actorMemberId }.toSet()).associateBy { it.id }
        val statusCache = mutableMapOf<Long, List<yangzhou.persistence.Status>>()
        return rows.map { n ->
            val item = itemMap.getValue(n.itemId)
            val project = projects.findById(item.projectId).orElse(null)
            val sts = statusCache.getOrPut(item.projectId) { statuses.findByProjectIdOrderByPosition(item.projectId) }
            val current = sts.firstOrNull { it.objectId == item.statusObjectId }
            val action = if (current != null && !current.isFinal) {
                sts.filter { it.position > current.position }.minByOrNull { it.position }
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
                actor = n.actorMemberId?.let { actorMap[it]?.displayName },
                oldValue = n.oldValue,
                newValue = n.newValue,
                read = n.readAt != null,
                createdAt = n.createdAt.toString(),
                action = action,
            )
        }
    }

    fun unreadCount(): Long = notifications.countByRecipientMemberIdAndReadAtIsNull(memberService.current().id!!)

    @Transactional
    fun markRead(notificationId: UUID) {
        val meId = memberService.current().id!!
        val n = notifications.findByObjectId(notificationId) ?: throw NotFoundException("通知不存在")
        if (n.recipientMemberId != meId) throw NotFoundException("通知不存在")
        if (n.readAt == null) notifications.save(n.copy(readAt = Instant.now()))
    }

    @Transactional
    fun readAll() {
        val meId = memberService.current().id!!
        val now = Instant.now()
        notifications.findByRecipientMemberId(meId).filter { it.readAt == null }
            .forEach { notifications.save(it.copy(readAt = now)) }
    }
}
