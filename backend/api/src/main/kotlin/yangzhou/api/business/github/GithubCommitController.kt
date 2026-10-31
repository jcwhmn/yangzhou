package yangzhou.api.business.github

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class GithubCommitController(
    private val service: GithubCommitService,
) {
    @GetMapping("/items/{itemId}/commits")
    @ResponseStatus(HttpStatus.OK)
    fun commits(
        @PathVariable itemId: UUID,
    ): List<GithubCommitService.RefCommitsDto> = service.commitsForItem(itemId)
}
