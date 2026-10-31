package yangzhou.api.business.dependency

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
class DependencyController(
    private val service: DependencyService,
) {
    @GetMapping("/items/{itemId}/dependencies")
    fun list(
        @PathVariable itemId: UUID,
    ): DependencyService.DependencyListDto = service.list(itemId)

    @PostMapping("/items/{itemId}/dependencies")
    @ResponseStatus(HttpStatus.CREATED)
    fun add(
        @PathVariable itemId: UUID,
        @RequestBody request: CreateDependencyRequest,
    ): DependencyService.DependencyDto = service.add(itemId, request.dependsOnItemId)

    @DeleteMapping("/dependencies/{dependencyItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(
        @PathVariable dependencyItemId: UUID,
    ) = service.remove(dependencyItemId)
}
