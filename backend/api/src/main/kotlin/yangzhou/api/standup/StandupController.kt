package yangzhou.api.standup

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class StandupController(private val service: StandupService) {

    @GetMapping("/standup")
    fun standup(@RequestParam("member", required = false) member: UUID?): StandupDto = service.standup(member)
}
