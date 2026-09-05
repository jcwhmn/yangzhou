package yangzhou.api.item

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.http.HttpStatus
import java.util.UUID

@RestController
@RequestMapping("/api")
class ItemController(private val service: ItemService) {

    @PostMapping("/projects/{key}/items")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable key: String,
        @Valid @RequestBody request: CreateItemRequest,
    ): ItemService.ItemDto = service.create(
        projectKey = key,
        title = request.title.trim(),
        description = request.description,
        type = request.type,
        parentItemId = request.parentItemId,
        statusItemId = request.statusItemId,
        externalRef = request.externalRef,
        requirements = request.requirements.map { ItemService.RequirementDto(it.attribute.trim(), it.minLevel) },
    )

    @GetMapping("/projects/{key}/items")
    fun list(@PathVariable key: String): List<ItemService.ItemDto> = service.list(key)

    @GetMapping("/items/{itemId}")
    fun get(@PathVariable itemId: UUID): ItemService.ItemDto = service.get(itemId)

    @PutMapping("/items/{itemId}/requirements")
    fun replaceRequirements(
        @PathVariable itemId: UUID,
        @RequestBody request: ReplaceRequirementsRequest,
    ): ItemService.ItemDto = service.replaceRequirements(
        itemId,
        request.requirements.map { ItemService.RequirementDto(it.attribute.trim(), it.minLevel) },
    )

    @PutMapping("/items/{itemId}/assignee")
    fun assign(
        @PathVariable itemId: UUID,
        @RequestBody request: AssigneeRequest,
    ): ItemService.ItemDto = service.assign(itemId, request.assigneeItemId)

    @GetMapping("/items/{itemId}/activity")
    fun activity(@PathVariable itemId: UUID) = service.activity(itemId)

    @PatchMapping("/items/{itemId}")
    fun update(
        @PathVariable itemId: UUID,
        @RequestBody request: UpdateItemRequest,
    ): ItemService.ItemDto = service.update(itemId, request.title, request.description, request.statusItemId, request.parentItemId)
}
