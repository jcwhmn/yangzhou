package yangzhou.api.sprint

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import yangzhou.api.item.ItemService
import java.util.UUID

@RestController
@RequestMapping("/api")
class SprintController(private val service: SprintService) {

    @GetMapping("/projects/{key}/sprints")
    fun list(@PathVariable key: String): List<SprintService.SprintDto> = service.list(key)

    @PostMapping("/projects/{key}/sprints")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable key: String,
        @RequestBody request: SprintService.CreateSprintRequest,
    ): SprintService.SprintDto = service.create(key, request)

    @GetMapping("/projects/{key}/sprints/{groupId}")
    fun get(@PathVariable key: String, @PathVariable groupId: UUID): SprintService.SprintDto =
        service.get(key, groupId)

    @PatchMapping("/projects/{key}/sprints/{groupId}")
    fun update(
        @PathVariable key: String,
        @PathVariable groupId: UUID,
        @RequestBody request: SprintService.UpdateSprintRequest,
    ): SprintService.SprintDto = service.update(key, groupId, request)

    @DeleteMapping("/projects/{key}/sprints/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable key: String, @PathVariable groupId: UUID) = service.delete(key, groupId)

    @GetMapping("/projects/{key}/sprints/{groupId}/items")
    fun items(@PathVariable key: String, @PathVariable groupId: UUID): List<ItemService.ItemDto> =
        service.items(key, groupId)

    @GetMapping("/projects/{key}/backlog")
    fun backlog(@PathVariable key: String): List<ItemService.ItemDto> = service.backlog(key)
}
