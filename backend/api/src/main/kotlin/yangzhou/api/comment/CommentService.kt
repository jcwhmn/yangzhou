package yangzhou.api.comment

import jakarta.validation.constraints.NotBlank
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.member.MemberService
import yangzhou.api.support.NotFoundException
import yangzhou.persistence.Comment
import yangzhou.persistence.repository.CommentRepository
import yangzhou.persistence.repository.ItemRepository
import java.util.UUID

/** item 评论(人工讨论,区别于自动留痕)。 */
@Service
class CommentService(
    private val comments: CommentRepository,
    private val itemRepo: ItemRepository,
    private val memberService: MemberService,
) {

    data class CommentDto(
        val commentId: UUID,
        val body: String,
        val author: String,
        val createdAt: String,
    )

    fun list(itemId: UUID): List<CommentDto> {
        val item = itemRepo.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val author = memberService.current()
        return comments.findByItemIdOrderByCreatedAtDesc(item.id!!)
            .map { CommentDto(it.objectId, it.body, author.displayName, it.createdAt.toString()) }
    }

    @Transactional
    fun create(itemId: UUID, body: String): CommentDto {
        val item = itemRepo.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val member = memberService.current()
        val saved = comments.save(Comment(itemId = item.id!!, authorMemberId = member.id!!, body = body.trim()))
        return CommentDto(saved.objectId, saved.body, member.displayName, saved.createdAt.toString())
    }

    @Transactional
    fun delete(commentId: UUID) {
        val c = comments.findByObjectId(commentId) ?: throw NotFoundException("评论不存在")
        comments.delete(c)
    }
}

data class CreateCommentRequest(
    @field:NotBlank val body: String,
)
