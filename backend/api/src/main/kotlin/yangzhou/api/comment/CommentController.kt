package yangzhou.api.comment

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class CommentController(private val service: CommentService) {

    @GetMapping("/items/{itemId}/comments")
    fun list(@PathVariable itemId: UUID): List<CommentService.CommentDto> = service.list(itemId)

    @PostMapping("/items/{itemId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@PathVariable itemId: UUID, @Valid @RequestBody request: CreateCommentRequest): CommentService.CommentDto =
        service.create(itemId, request.body)

    @DeleteMapping("/comments/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable commentId: UUID) = service.delete(commentId)
}
