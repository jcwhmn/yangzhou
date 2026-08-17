package yangzhou.api.attribute

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.http.HttpStatus
import java.util.UUID

@RestController
@RequestMapping("/api/attributes")
class AttributeController(private val service: AttributeService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateAttributeRequest): AttributeService.AttributeDto =
        service.create(request.name.trim(), request.kind, request.leveled)

    @GetMapping
    fun list(): List<AttributeService.AttributeDto> = service.list()

    @PatchMapping("/{attributeId}")
    fun update(
        @PathVariable attributeId: UUID,
        @RequestBody request: UpdateAttributeRequest,
    ): AttributeService.AttributeDto = service.update(attributeId, request.kind, request.leveled)

    @DeleteMapping("/{attributeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable attributeId: UUID) = service.delete(attributeId)
}
