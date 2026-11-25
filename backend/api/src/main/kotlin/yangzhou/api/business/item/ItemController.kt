package yangzhou.api.business.item

import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
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
import java.util.UUID

@RestController
@RequestMapping("/api")
class ItemController(
    private val service: ItemService,
) {
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
        sprintId = request.sprintId,
        estimateValue = request.estimateValue,
        estimateUnit = request.estimateUnit,
        requirements = request.requirements.map { ItemService.RequirementDto(it.attribute.trim(), it.minLevel) },
    )

    @GetMapping("/projects/{key}/items")
    fun list(
        @PathVariable key: String,
    ): List<ItemService.ItemDto> = service.list(key)

    /** YPJ-238(spec 0166 §3.1):看板列内箭头排序(move 交换);响应带该列全序。 */
    @PostMapping("/projects/{key}/board-order/move")
    fun moveBoardOrder(
        @PathVariable key: String,
        @RequestBody request: MoveBoardOrderRequest,
    ) = service.moveBoardOrder(key, request.itemId, request.dir)

    data class MoveBoardOrderRequest(
        val itemId: UUID,
        val dir: String,
    )

    /** YPJ-279(spec 0169):整列重排——宽容语义(重复后序覆盖/无效忽略/跨列合法),响应带 report。 */
    @PostMapping("/projects/{key}/board-order/apply")
    fun applyBoardOrder(
        @PathVariable key: String,
        @Valid @RequestBody request: ApplyBoardOrderRequest,
    ) = service.applyBoardOrder(key, request.itemIds)

    data class ApplyBoardOrderRequest(
        @field:NotEmpty val itemIds: List<UUID>,
    )

    @GetMapping("/items/{itemId}")
    fun get(
        @PathVariable itemId: UUID,
    ): ItemService.ItemDto = service.get(itemId)

    @PutMapping("/items/{itemId}/requirements")
    fun replaceRequirements(
        @PathVariable itemId: UUID,
        @RequestBody request: ReplaceRequirementsRequest,
    ): ItemService.ItemDto = service.replaceRequirements(
        itemId,
        request.requirements.map { ItemService.RequirementDto(it.attribute.trim(), it.minLevel) },
    )

    @PutMapping("/items/{itemId}/attributes")
    fun replaceAttributes(
        @PathVariable itemId: UUID,
        @RequestBody request: ReplaceItemAttributesRequest,
    ): ItemService.ItemDto = service.replaceAttributes(
        itemId,
        request.values.map { ItemService.ItemAttributeDto(it.attribute.trim(), it.value) },
    )

    @PutMapping("/items/{itemId}/assignee")
    fun assign(
        @PathVariable itemId: UUID,
        @RequestBody request: AssigneeRequest,
    ): ItemService.ItemDto = service.assign(itemId, request.assigneeItemId)

    @GetMapping("/items/{itemId}/activity")
    fun activity(
        @PathVariable itemId: UUID,
    ) = service.activity(itemId)

    @DeleteMapping("/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable itemId: UUID,
    ) = service.delete(itemId)

    @PatchMapping("/items/{itemId}")
    fun update(
        @PathVariable itemId: UUID,
        @RequestBody request: UpdateItemRequest,
    ): ItemService.ItemDto = service.update(
        itemId,
        request.title,
        request.description,
        request.type,
        request.statusItemId,
        request.parentItemId,
        request.startDate,
        request.dueDate,
        request.priority,
        request.estimateValue,
        request.estimateUnit,
        request.closeReason,
        request.closeNote,
    )
}
