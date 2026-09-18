package yangzhou.api.checklist

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import yangzhou.api.support.BadRequestException
import yangzhou.api.support.NotFoundException
import yangzhou.persistence.ChecklistItem
import yangzhou.persistence.repository.ChecklistItemRepository
import yangzhou.persistence.repository.ItemRepository
import java.time.Instant
import java.util.UUID

/** V9-S5 检查清单:item 内步骤条目(text + done),随 item 级联删。 */
@Service
class ChecklistService(
    private val checklists: ChecklistItemRepository,
    private val items: ItemRepository,
) {

    data class EntryDto(val checklistItemId: UUID, val text: String, val done: Boolean)

    data class ChecklistDto(val entries: List<EntryDto>, val doneCount: Int, val totalCount: Int)

    fun list(itemId: UUID): ChecklistDto {
        val item = items.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        return toDto(checklists.findByItemIdOrderByPositionAscIdAsc(item.id!!))
    }

    @Transactional
    fun add(itemId: UUID, text: String): EntryDto {
        val item = items.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val position = checklists.findByItemIdOrderByPositionAscIdAsc(item.id!!).maxOfOrNull { it.position + 1 } ?: 0
        val saved = checklists.save(
            ChecklistItem(itemId = item.id!!, text = text.trim(), position = position),
        )
        return EntryDto(saved.objectId, saved.text, saved.done)
    }

    @Transactional
    fun update(entryObjectId: UUID, text: String?, done: Boolean?): EntryDto {
        val entry = checklists.findByObjectId(entryObjectId) ?: throw NotFoundException("条目不存在")
        val newDone = done ?: entry.done
        val newText = when {
            text == null -> entry.text
            text.isBlank() -> throw BadRequestException("text 不能为空")
            else -> text.trim()
        }
        val saved = checklists.save(entry.copy(text = newText, done = newDone, updatedAt = Instant.now()))
        return EntryDto(saved.objectId, saved.text, saved.done)
    }

    @Transactional
    fun remove(entryObjectId: UUID) {
        val entry = checklists.findByObjectId(entryObjectId) ?: throw NotFoundException("条目不存在")
        checklists.delete(entry)
    }

    private fun toDto(rows: List<ChecklistItem>): ChecklistDto = ChecklistDto(
        entries = rows.map { EntryDto(it.objectId, it.text, it.done) },
        doneCount = rows.count { it.done },
        totalCount = rows.size,
    )
}

data class UpsertChecklistRequest(
    @field:NotBlank val text: String,
)

data class PatchChecklistRequest(
    val text: String? = null,
    val done: Boolean? = null,
)

@RestController
@RequestMapping("/api")
class ChecklistController(private val service: ChecklistService) {

    @GetMapping("/items/{itemId}/checklist")
    fun list(@PathVariable itemId: UUID): ChecklistService.ChecklistDto = service.list(itemId)

    @PostMapping("/items/{itemId}/checklist")
    @ResponseStatus(HttpStatus.CREATED)
    fun add(
        @PathVariable itemId: UUID,
        @Valid @RequestBody request: UpsertChecklistRequest,
    ): ChecklistService.EntryDto = service.add(itemId, request.text)

    @PatchMapping("/checklist/{entryId}")
    fun update(
        @PathVariable entryId: UUID,
        @RequestBody request: PatchChecklistRequest,
    ): ChecklistService.EntryDto = service.update(entryId, request.text, request.done)

    @DeleteMapping("/checklist/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(@PathVariable entryId: UUID) = service.remove(entryId)
}
