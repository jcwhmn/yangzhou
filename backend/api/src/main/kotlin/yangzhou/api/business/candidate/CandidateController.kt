package yangzhou.api.business.candidate

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class CandidateController(
    private val service: CandidateService,
) {
    @GetMapping("/items/{itemId}/candidates")
    fun candidates(
        @PathVariable itemId: UUID,
    ): List<CandidateService.CandidateDto> = service.candidates(itemId)
}
