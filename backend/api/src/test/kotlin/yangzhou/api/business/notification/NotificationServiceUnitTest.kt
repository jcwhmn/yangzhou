package yangzhou.api.business.notification

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import yangzhou.api.business.member.MemberService
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Comment
import yangzhou.persistence.Item
import yangzhou.persistence.Member
import yangzhou.persistence.Notification
import yangzhou.persistence.Project
import yangzhou.persistence.Status
import yangzhou.persistence.repository.CommentRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.NotificationRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.StatusRepository
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** YPJ-88 单测补齐:NotificationService(收件人推导/未读排序/下一列 action)。 */
class NotificationServiceUnitTest {

    private val notifications = mockk<NotificationRepository>(relaxed = true)
    private val items = mockk<ItemRepository>(relaxed = true)
    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val statuses = mockk<StatusRepository>(relaxed = true)
    private val members = mockk<MemberRepository>(relaxed = true)
    private val comments = mockk<CommentRepository>(relaxed = true)
    private val memberService = mockk<MemberService>(relaxed = true)
    private val service = NotificationService(
        notifications, items, projects, statuses, members, comments, memberService,
    )

    private val projectId = 1L
    private val assigneeId = 5L
    private val assignee = Member(id = assigneeId, workspaceId = 9L, username = null, passwordHash = null, displayName = "assignee")
    private val creatorId = 6L
    private val creator = Member(id = creatorId, workspaceId = 9L, username = null, passwordHash = null, displayName = "creator")
    private val openStatus = Status(id = 1L, projectId = projectId, name = "To Do", position = 0)
    private val item = Item(
        id = 1L, projectId = projectId, number = 3, title = "t",
        assigneeObjectId = assignee.objectId, statusObjectId = openStatus.objectId,
        createdBy = creatorId,
    )
    private val project = Project(id = projectId, workspaceId = 9L, key = "CHE", name = "CHE")

    @BeforeEach
    fun setup() {
        clearMocks(notifications, items, projects, statuses, members, comments, memberService)
        every { notifications.save(any<Notification>()) } answers { firstArg<Notification>() }
        every { memberService.current() } returns creator
        every { members.findByObjectId(assignee.objectId) } returns assignee
        every { comments.findByItemId(1L) } returns emptyList()
    }

    @Nested
    inner class Recipients {

        @Test
        fun assigneeAndCreatorNotifiedActorExcluded() {
            service.notifyStatusChange(item, actorMemberId = creatorId, "To Do", "Done")
            val slots = mutableListOf<Notification>()
            verify { notifications.save(capture(slots)) }
            // assignee + creator,actor(creator)排除
            assertEquals(listOf(5L), slots.map { it.recipientMemberId })
            assertEquals(KIND_STATUS, slots[0].kind)
        }

        @Test
        fun commentersDedupedWithAssignee() {
            val commenter = Member(id = 5L, workspaceId = 9L, username = null, passwordHash = null, displayName = "same-as-assignee")
            every { comments.findByItemId(1L) } returns listOf(
                Comment(id = 1L, itemId = 1L, authorMemberId = 5L, body = "c"), // 评论者 = assignee,去重
                Comment(id = 2L, itemId = 1L, authorMemberId = 7L, body = "c"),
            )
            service.notifyStatusChange(item, actorMemberId = null, null, null) // actor null(GitHub 事件)不排除
            val slots = mutableListOf<Notification>()
            verify { notifications.save(capture(slots)) }
            assertEquals(setOf(5L, 6L, 7L), slots.map { it.recipientMemberId }.toSet())
        }

        @Test
        fun selfAssignNotNotified() {
            val self = item.copy(assigneeObjectId = null)
            service.notifyAssigned(self, actorMemberId = creatorId, assigneeMemberId = creatorId, assigneeName = "me")
            io.mockk.verify(exactly = 0) { notifications.save(any()) }
        }

        @Test
        fun assignedGoesToAssigneeOnly() {
            service.notifyAssigned(item, actorMemberId = creatorId, assigneeMemberId = 5L, assigneeName = "assignee")
            val slot = slot<Notification>()
            verify { notifications.save(capture(slot)) }
            assertEquals(5L, slot.captured.recipientMemberId)
            assertEquals(KIND_ASSIGNED, slot.captured.kind)
            assertEquals("assignee", slot.captured.newValue)
        }
    }

    @Nested
    inner class ListView {

        private fun notif(id: Long, read: Boolean = false) = Notification(
            id = id, recipientMemberId = creatorId, itemId = 1L, kind = KIND_STATUS,
            actorMemberId = assigneeId, readAt = if (read) java.time.Instant.now() else null,
        )

        @Test
        fun unreadFirstCapped50AndActionComputed() {
            val open = Status(id = 1L, projectId = projectId, name = "To Do", position = 0)
            val mid = Status(id = 2L, projectId = projectId, name = "In Progress", position = 1)
            val final = Status(id = 3L, projectId = projectId, name = "Done", isFinal = true, position = 2)
            every { notifications.findByRecipientMemberId(creatorId) } returns (1..60).map { notif(it.toLong()) }
            every { items.findAllById(any()) } returns listOf(item)
            every { members.findAllById(any()) } returns listOf(assignee)
            every { projects.findById(projectId) } returns java.util.Optional.of(project)
            every { statuses.findByProjectIdOrderByPosition(projectId) } returns listOf(openStatus, mid, final)
            val dtos = service.list()
            assertEquals(50, dtos.size) // 截断 50
            assertTrue(dtos[0].read == false)
            assertEquals("In Progress", dtos[0].action?.statusName) // 下一列
            assertEquals("CHE-3", dtos[0].number)
            assertEquals("assignee", dtos[0].actor)
        }

        @Test
        fun finalStatusHasNoAction() {
            val final = Status(id = 3L, projectId = projectId, name = "Done", isFinal = true, position = 2)
            item.statusObjectId.let { }
            val doneItem = item.copy(statusObjectId = final.objectId)
            every { notifications.findByRecipientMemberId(creatorId) } returns listOf(notif(1L))
            every { items.findAllById(any()) } returns listOf(doneItem)
            every { members.findAllById(any()) } returns emptyList()
            every { projects.findById(projectId) } returns java.util.Optional.of(project)
            every { statuses.findByProjectIdOrderByPosition(projectId) } returns listOf(final)
            assertNull(service.list()[0].action)
        }
    }

    @Nested
    inner class ReadOps {

        @Test
        fun unreadCountByMe() {
            every { notifications.countByRecipientMemberIdAndReadAtIsNull(creatorId) } returns 7L
            assertEquals(7L, service.unreadCount())
        }

        @Test
        fun markReadOthersNotificationIs404() {
            val n = Notification(id = 1L, recipientMemberId = 99L, itemId = 1L, kind = KIND_STATUS)
            every { notifications.findByObjectId(n.objectId) } returns n
            assertFailsWith<NotFoundException> { service.markRead(n.objectId) }
        }

        @Test
        fun markReadIdempotent() {
            var n = Notification(id = 1L, recipientMemberId = creatorId, itemId = 1L, kind = KIND_STATUS)
            every { notifications.findByObjectId(n.objectId) } answers { n }
            every { notifications.save(any<Notification>()) } answers { n = firstArg(); n }
            service.markRead(n.objectId)
            verify(exactly = 1) { notifications.save(any()) }
            service.markRead(n.objectId) // 已读 → 不再写
            verify(exactly = 1) { notifications.save(any()) }
        }

        @Test
        fun readAllStampsUnreadOnly() {
            val unread = Notification(id = 1L, recipientMemberId = creatorId, itemId = 1L, kind = KIND_STATUS)
            val read = Notification(id = 2L, recipientMemberId = creatorId, itemId = 1L, kind = KIND_STATUS, readAt = java.time.Instant.now())
            every { notifications.findByRecipientMemberId(creatorId) } returns listOf(unread, read)
            service.readAll()
            verify(exactly = 1) { notifications.save(any()) }
        }
    }
}
