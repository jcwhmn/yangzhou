package yangzhou.api.business.comment

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import yangzhou.api.business.notification.NotificationService
import yangzhou.api.business.member.MemberService
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Comment
import yangzhou.persistence.Item
import yangzhou.persistence.Member
import yangzhou.persistence.repository.CommentRepository
import yangzhou.persistence.repository.ItemRepository
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-88 单测补齐:CommentService(人工讨论 + 通知扇出)。 */
class CommentServiceUnitTest {

    private val comments = mockk<CommentRepository>(relaxed = true)
    private val itemRepo = mockk<ItemRepository>(relaxed = true)
    private val memberService = mockk<MemberService>(relaxed = true)
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val service = CommentService(comments, itemRepo, memberService, notificationService)

    private val item = Item(id = 1L, projectId = 1L, number = 1, title = "t", statusObjectId = UUID.randomUUID())
    private val me = Member(id = 5L, workspaceId = 9L, username = "me", passwordHash = "h", displayName = "me")

    @BeforeEach
    fun setup() {
        clearMocks(comments, itemRepo, memberService, notificationService)
        every { itemRepo.findByObjectId(item.objectId) } returns item
        every { memberService.current() } returns me
    }

    @Nested
    inner class Create {

        @Test
        fun missingItemRejected() {
            every { itemRepo.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.create(UUID.randomUUID(), "hi") }
        }

        @Test
        fun trimmedBodySavedAndNotified() {
            every { comments.save(any()) } answers { firstArg<Comment>().copy(id = 1L) }
            val dto = service.create(item.objectId, "  有空格  ")
            assertEquals("有空格", dto.body)
            verify { notificationService.notifyComment(item, 5L, "有空格") }
        }
    }

    @Nested
    inner class ListComments {

        @Test
        fun newestFirst() {
            val c1 = Comment(id = 1L, itemId = 1L, authorMemberId = 5L, body = "first")
            val c2 = Comment(id = 2L, itemId = 1L, authorMemberId = 5L, body = "second")
            every { comments.findByItemIdOrderByCreatedAtDesc(1L) } returns listOf(c2, c1)
            assertEquals(listOf("second", "first"), service.list(item.objectId).map { it.body })
        }

        @Test
        fun missingItemRejected() {
            every { itemRepo.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.list(UUID.randomUUID()) }
        }
    }

    @Nested
    inner class DeleteComment {

        @Test
        fun missingCommentRejected() {
            every { comments.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.delete(UUID.randomUUID()) }
        }

        @Test
        fun deleted() {
            val c = Comment(id = 1L, itemId = 1L, authorMemberId = 5L, body = "x")
            every { comments.findByObjectId(c.objectId) } returns c
            service.delete(c.objectId)
            verify { comments.delete(c) }
        }
    }
}
