package yangzhou.api.notification

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class NotificationController(private val service: NotificationService) {

    @GetMapping("/notifications")
    fun list(): List<NotificationService.NotificationDto> = service.list()

    @GetMapping("/notifications/unread-count")
    fun unreadCount(): Map<String, Long> = mapOf("count" to service.unreadCount())

    @PutMapping("/notifications/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun read(@PathVariable notificationId: UUID) = service.markRead(notificationId)

    @PostMapping("/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun readAll() = service.readAll()
}
