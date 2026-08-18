package yangzhou.api.workflow

import jakarta.validation.Valid
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
class WorkflowController(private val service: WorkflowService) {

    @PostMapping("/projects/{key}/statuses")
    @ResponseStatus(HttpStatus.CREATED)
    fun createStatus(
        @PathVariable key: String,
        @Valid @RequestBody request: CreateStatusRequest,
    ): WorkflowService.StatusDto = service.createStatus(key, request.name.trim(), request.icon, request.isFinal, request.position)

    @PatchMapping("/statuses/{statusId}")
    fun updateStatus(
        @PathVariable statusId: UUID,
        @RequestBody request: UpdateStatusRequest,
    ): WorkflowService.StatusDto = service.updateStatus(statusId, request.name, request.icon, request.isFinal, request.position)

    @DeleteMapping("/statuses/{statusId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteStatus(@PathVariable statusId: UUID) = service.deleteStatus(statusId)

    @GetMapping("/projects/{key}/transitions")
    fun listTransitions(@PathVariable key: String): List<WorkflowService.TransitionDto> = service.listTransitions(key)

    @PutMapping("/projects/{key}/transitions")
    fun replaceTransitions(
        @PathVariable key: String,
        @RequestBody request: ReplaceTransitionsRequest,
    ): List<WorkflowService.TransitionDto> = service.replaceTransitions(key, request.transitions)
}
