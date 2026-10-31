package yangzhou.api.business.notification

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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
    private val service =
        NotificationService(
            notifications,
            items,
            projects,
            statuses,
            members,
            comments,
            memberService,
        )

    private val projectObjectId = UUID(0, 1)
    private val assigneeId = UUID(0, 5)
    private val assignee =
        Member(
            id = 1,
            objectId = assigneeId,
            workspaceObjectId = UUID(0, 9),
            username = null,
            passwordHash = null,
            displayName = "assignee",
        )
    private val creatorObjectId = UUID(0, 6)
    private val creator =
        Member(
            id = 1,
            objectId = creatorObjectId,
            workspaceObjectId = UUID(0, 9),
            username = null,
            passwordHash = null,
            displayName = "creator",
        )
    private val openStatus = Status(id = 1L, objectId = UUID(0, 1), projectObjectId = projectObjectId, name = "To Do", position = 0)
    private val item =
        Item(
            id = 1L,
            objectId = UUID(0, 1),
            projectObjectId = projectObjectId,
            number = 3,
            title = "t",
            assigneeObjectId = assignee.objectId,
            statusObjectId = openStatus.objectId,
            createdByObjectId = creatorObjectId,
        )
    private val project = Project(id = 1, objectId = projectObjectId, workspaceObjectId = UUID(0, 9), key = "CHE", name = "CHE")

    @BeforeEach
    fun setup() {
        clearMocks(notifications, items, projects, statuses, members, comments, memberService)
        every { notifications.save(any<Notification>()) } answers { firstArg<Notification>() }
        every { memberService.current() } returns creator
        every { members.findByObjectId(assignee.objectId) } returns assignee
        every { comments.findByItemObjectId(UUID(0, 1)) } returns emptyList()
    }

    @Nested
    inner class Recipients {
        @Test
        fun assigneeAndCreatorNotifiedActorExcluded() {
            service.notifyStatusChange(item, actorMemberObjectId = creatorObjectId, "To Do", "Done")
            val slots = mutableListOf<Notification>()
            verify { notifications.save(capture(slots)) }
            // assignee + creator,actor(creator)排除
            assertEquals(listOf(UUID(0, 5)), slots.map { it.recipientMemberObjectId })
            assertEquals(KIND_STATUS, slots[0].kind)
        }

        @Test
        fun commentersDedupedWithAssignee() {
            val commenter =
                Member(
                    id = 5L,
                    objectId = UUID(0, 5),
                    workspaceObjectId = UUID(0, 9),
                    username = null,
                    passwordHash = null,
                    displayName = "same-as-assignee",
                )
            every { comments.findByItemObjectId(UUID(0, 1)) } returns
                listOf(
                    Comment(
                        id = 1L,
                        objectId = UUID(0, 1),
                        itemObjectId = UUID(0, 1),
                        authorMemberObjectId = UUID(0, 5),
                        body = "c",
                    ), // 评论者 = assignee,去重
                    Comment(id = 2L, objectId = UUID(0, 2), itemObjectId = UUID(0, 1), authorMemberObjectId = UUID(0, 7), body = "c"),
                )
            service.notifyStatusChange(item, actorMemberObjectId = null, null, null) // actor null(GitHub 事件)不排除
            val slots = mutableListOf<Notification>()
            verify { notifications.save(capture(slots)) }
            assertEquals(setOf(UUID(0, 5), UUID(0, 6), UUID(0, 7)), slots.map { it.recipientMemberObjectId }.toSet())
        }

        @Test
        fun selfAssignNotNotified() {
            val self = item.copy(assigneeObjectId = null)
            service.notifyAssigned(
                self,
                actorMemberObjectId = creatorObjectId,
                assigneeMemberObjectId = creatorObjectId,
                assigneeName = "me",
            )
            io.mockk.verify(exactly = 0) { notifications.save(any()) }
        }

        @Test
        fun assignedGoesToAssigneeOnly() {
            service.notifyAssigned(
                item,
                actorMemberObjectId = creatorObjectId,
                assigneeMemberObjectId = UUID(0, 5),
                assigneeName = "assignee",
            )
            val slot = slot<Notification>()
            verify { notifications.save(capture(slot)) }
            assertEquals(UUID(0, 5), slot.captured.recipientMemberObjectId)
            assertEquals(KIND_ASSIGNED, slot.captured.kind)
            assertEquals("assignee", slot.captured.newValue)
        }
    }

    @Nested
    inner class ListView {
        private fun notif(
            id: Long,
            read: Boolean = false,
        ) = Notification(
            id = id,
            recipientMemberObjectId = creatorObjectId,
            itemObjectId = UUID(0, 1),
            kind = KIND_STATUS,
            actorMemberObjectId = assigneeId,
            readAt = if (read) java.time.Instant.now() else null,
        )

        @Test
        fun unreadFirstCapped50AndActionComputed() {
            val open = Status(id = 1L, objectId = UUID(0, 1), projectObjectId = projectObjectId, name = "To Do", position = 0)
            val mid = Status(id = 2L, objectId = UUID(0, 2), projectObjectId = projectObjectId, name = "In Progress", position = 1)
            val final =
                Status(id = 3L, objectId = UUID(0, 3), projectObjectId = projectObjectId, name = "Done", isFinal = true, position = 2)
            every { notifications.findByRecipientMemberObjectId(creatorObjectId) } returns (1..60).map { notif(it.toLong()) }
            every { items.findAllByObjectIdIn(any()) } returns listOf(item)
            every { members.findAllByObjectIdIn(any()) } returns listOf(assignee)
            every { projects.findByObjectId(projectObjectId) } returns (project)
            every { statuses.findByProjectObjectIdOrderByPosition(projectObjectId) } returns listOf(openStatus, mid, final)
            val dtos = service.list()
            assertEquals(50, dtos.size) // 截断 50
            assertTrue(dtos[0].read == false)
            assertEquals("In Progress", dtos[0].action?.statusName) // 下一列
            assertEquals("CHE-3", dtos[0].number)
            assertEquals("assignee", dtos[0].actor)
        }

        @Test
        fun finalStatusHasNoAction() {
            val final =
                Status(id = 3L, objectId = UUID(0, 3), projectObjectId = projectObjectId, name = "Done", isFinal = true, position = 2)
            item.statusObjectId.let { }
            val doneItem = item.copy(statusObjectId = final.objectId)
            every { notifications.findByRecipientMemberObjectId(creatorObjectId) } returns listOf(notif(1L))
            every { items.findAllByObjectIdIn(any()) } returns listOf(doneItem)
            every { members.findAllByObjectIdIn(any()) } returns emptyList()
            every { projects.findByObjectId(projectObjectId) } returns (project)
            every { statuses.findByProjectObjectIdOrderByPosition(projectObjectId) } returns listOf(final)
            assertNull(service.list()[0].action)
        }
    }

    @Nested
    inner class ReadOps {
        @Test
        fun unreadCountByMe() {
            every { notifications.countByRecipientMemberObjectIdAndReadAtIsNull(creatorObjectId) } returns 7
            assertEquals(7L, service.unreadCount())
        }

        @Test
        fun markReadOthersNotificationIs404() {
            val n =
                Notification(
                    id = 1L,
                    objectId = UUID(0, 1),
                    recipientMemberObjectId = UUID(0, 99),
                    itemObjectId = UUID(0, 1),
                    kind = KIND_STATUS,
                )
            every { notifications.findByObjectId(n.objectId) } returns n
            assertFailsWith<NotFoundException> { service.markRead(n.objectId) }
        }

        @Test
        fun markReadIdempotent() {
            var n =
                Notification(
                    id = 1L,
                    objectId = UUID(0, 1),
                    recipientMemberObjectId = creatorObjectId,
                    itemObjectId = UUID(0, 1),
                    kind = KIND_STATUS,
                )
            every { notifications.findByObjectId(n.objectId) } answers { n }
            every { notifications.save(any<Notification>()) } answers {
                n = firstArg()
                n
            }
            service.markRead(n.objectId)
            verify(exactly = 1) { notifications.save(any()) }
            service.markRead(n.objectId) // 已读 → 不再写
            verify(exactly = 1) { notifications.save(any()) }
        }

        @Test
        fun readAllStampsUnreadOnly() {
            val unread =
                Notification(
                    id = 1L,
                    objectId = UUID(0, 1),
                    recipientMemberObjectId = creatorObjectId,
                    itemObjectId = UUID(0, 1),
                    kind = KIND_STATUS,
                )
            val read =
                Notification(
                    id = 2L,
                    objectId = UUID(0, 2),
                    recipientMemberObjectId = creatorObjectId,
                    itemObjectId = UUID(0, 1),
                    kind = KIND_STATUS,
                    readAt = java.time.Instant.now(),
                )
            every { notifications.findByRecipientMemberObjectId(creatorObjectId) } returns listOf(unread, read)
            service.readAll()
            verify(exactly = 1) { notifications.save(any()) }
        }
    }
}
