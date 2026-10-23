package yangzhou.api.attachment

import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
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

@Tag(name = "Attachment", description = "item 附件(presign 直传直取,字节不经后端)")
@RequestMapping("/api")
@RestController
class AttachmentController(private val service: AttachmentService) {

    @PostMapping("/items/{itemId}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@PathVariable itemId: UUID, @Valid @RequestBody request: RegisterRequest): AttachmentUploadedResponse =
        service.register(itemId, request.filename!!, request.contentType!!, request.sizeBytes!!)

    @GetMapping("/items/{itemId}/attachments")
    fun list(@PathVariable itemId: UUID): List<AttachmentResponse> = service.list(itemId)

    @GetMapping("/items/{itemId}/attachments/{objectId}/download-url")
    fun downloadUrl(@PathVariable itemId: UUID, @PathVariable objectId: UUID): AttachmentDownloadResponse =
        service.downloadUrl(itemId, objectId)

    @DeleteMapping("/items/{itemId}/attachments/{objectId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable itemId: UUID, @PathVariable objectId: UUID) = service.delete(itemId, objectId)
}

data class RegisterRequest(
    @field:NotBlank val filename: String?,
    @field:NotBlank val contentType: String?,
    @field:NotNull @field:Positive val sizeBytes: Long?,
)
