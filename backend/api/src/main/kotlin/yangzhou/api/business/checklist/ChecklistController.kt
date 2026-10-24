package yangzhou.api.business.checklist

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

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
