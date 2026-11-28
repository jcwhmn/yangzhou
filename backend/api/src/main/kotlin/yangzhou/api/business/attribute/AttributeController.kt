package yangzhou.api.business.attribute

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class AttributeController(
    private val service: AttributeService,
    private val adminGuard: yangzhou.api.guard.AdminGuard,
) {
    /** S3(YPJ-299)矩阵行 31/Q15:属性词表管理 admin only(GET 保持登录即可)。 */
    @PostMapping("/attributes")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody request: CreateAttributeRequest,
    ): AttributeService.AttributeDto {
        adminGuard.requireAdmin()
        return service.create(request.name.trim(), request.kind, request.leveled, request.parentId)
    }

    @GetMapping("/attributes")
    fun list(): List<AttributeService.AttributeDto> = service.list()

    @PatchMapping("/attributes/{attributeId}")
    fun update(
        @PathVariable attributeId: UUID,
        @RequestBody request: UpdateAttributeRequest,
    ): AttributeService.AttributeDto {
        adminGuard.requireAdmin() // S3(YPJ-299)行 31
        return service.update(attributeId, request.kind, request.leveled, request.parentObjectId, request.unassign)
    }

    @DeleteMapping("/attributes/{attributeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable attributeId: UUID,
    ) {
        adminGuard.requireAdmin() // S3(YPJ-299)行 31
        service.delete(attributeId)
    }
}
