package yangzhou.api.business.time

import org.springframework.http.HttpStatus
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
class TimeEntryController(
    private val service: TimeEntryService,
) {
    @PostMapping("/items/{itemId}/time-entries")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable itemId: UUID,
        @RequestBody request: CreateTimeEntryRequest,
    ): TimeEntryService.TimeEntryDto = service.create(itemId, request.minutes, request.startedAt, request.endedAt, request.note?.takeIf { it.isNotBlank() })

    @PostMapping("/time-entries/{entryId}/stop")
    fun stop(
        @PathVariable entryId: UUID,
    ): TimeEntryService.TimeEntryDto = service.stop(entryId)

    @GetMapping("/items/{itemId}/time-entries")
    fun listForItem(
        @PathVariable itemId: UUID,
    ): TimeEntryService.ItemTimeLog = service.listForItem(itemId)

    @GetMapping("/projects/{key}/time-summary")
    fun summary(
        @PathVariable key: String,
    ): List<TimeEntryService.MemberSummary> = service.summary(key)

    @GetMapping("/items/{itemId}/time-entry-suggestions")
    fun suggestions(
        @PathVariable itemId: UUID,
    ): List<TimeEntryService.SuggestedEntry> = service.suggest(itemId)
}
