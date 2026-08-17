package yangzhou.api.feasibility

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class FeasibilityController(private val service: FeasibilityService) {

    @GetMapping("/api/projects/{key}/feasibility")
    fun project(@PathVariable key: String): FeasibilityService.ProjectResultDto = service.project(key)

    @GetMapping("/api/items/{itemId}/feasibility")
    fun item(@PathVariable itemId: UUID): FeasibilityService.ItemResultDto = service.item(itemId)
}
