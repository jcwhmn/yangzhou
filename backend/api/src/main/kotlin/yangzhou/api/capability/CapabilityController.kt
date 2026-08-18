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
@RequestMapping("/api")
class CapabilityController(private val service: CapabilityService) {

    @GetMapping("/capabilities")
    fun list(): List<CapabilityService.CapabilityDto> = service.list()

    @PutMapping("/capabilities")
    fun upsert(@Valid @RequestBody request: UpsertCapabilityRequest): CapabilityService.CapabilityDto =
        service.upsert(request.attribute.trim(), request.level)

    @DeleteMapping("/capabilities/{attribute}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable attribute: String) = service.delete(attribute)
}
