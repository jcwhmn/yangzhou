package yangzhou.api.capability

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.http.HttpStatus

@RestController
@RequestMapping("/api/capabilities")
class CapabilityController(private val service: CapabilityService) {

    @GetMapping
    fun list(): List<CapabilityService.CapabilityDto> = service.list()

    @PutMapping
    fun upsert(@Valid @RequestBody request: UpsertCapabilityRequest): CapabilityService.CapabilityDto =
        service.upsert(request.attribute.trim(), request.level)

    @DeleteMapping("/{attribute}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable attribute: String) = service.delete(attribute)
}
