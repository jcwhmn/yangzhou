package yangzhou.api.github

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class GithubBranchController(private val service: GithubBranchService) {

    @PostMapping("/items/{itemId}/git-refs")
    fun attachGitRef(@PathVariable itemId: UUID, @Valid @RequestBody request: AttachGitRefRequest): GithubBranchService.BranchDto =
        service.attach(itemId, request.repo, request.ref)

    @PostMapping("/items/{itemId}/branches")
    @ResponseStatus(HttpStatus.CREATED)
    fun createBranch(@PathVariable itemId: UUID, @Valid @RequestBody request: CreateBranchRequest): GithubBranchService.BranchDto =
        service.createBranch(itemId, request.repoId!!, request.baseBranch, request.name)
}
