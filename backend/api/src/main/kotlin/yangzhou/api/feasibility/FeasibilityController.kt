package yangzhou.api.feasibility

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class FeasibilityController(private val service: FeasibilityService) {

    @GetMapping("/projects/{key}/feasibility")
    fun project(@PathVariable key: String): FeasibilityService.ProjectResultDto = service.project(key)

    @GetMapping("/shortfall")
    fun shortfall(): List<FeasibilityService.ShortfallDto> = service.workspaceShortfall()

    @org.springframework.web.bind.annotation.PostMapping("/projects/{key}/recompute")
    fun recompute(@PathVariable key: String): Map<String, String?> =
        mapOf("signal" to service.recomputeByKey(key))

    @GetMapping("/items/{itemId}/feasibility")
    fun item(@PathVariable itemId: UUID): FeasibilityService.ItemResultDto = service.item(itemId)
}
