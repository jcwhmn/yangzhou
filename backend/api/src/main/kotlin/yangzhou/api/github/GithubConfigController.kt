package yangzhou.api.github

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
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
class GithubConfigController(private val service: GithubConfigService) {

    // ---------- workspace PAT ----------

    @PutMapping("/workspace/github-token")
    fun setToken(@Valid @RequestBody request: SetTokenRequest): GithubConfigService.TokenResponse =
        service.setToken(request.token)

    @DeleteMapping("/workspace/github-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun clearToken() = service.clearToken()

    // ---------- 项目仓库 ----------

    @GetMapping("/projects/{key}/repos")
    fun listRepos(@PathVariable key: String): List<GithubConfigService.RepoDto> = service.listRepos(key)

    @PostMapping("/projects/{key}/repos")
    @ResponseStatus(HttpStatus.CREATED)
    fun addRepo(@PathVariable key: String, @Valid @RequestBody request: AddRepoRequest): GithubConfigService.RepoDto =
        service.addRepo(key, request.repo)

    @DeleteMapping("/projects/{key}/repos/{repoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteRepo(@PathVariable key: String, @PathVariable repoId: UUID) = service.deleteRepo(key, repoId)

    // ---------- 事件→状态映射 ----------

    @GetMapping("/projects/{key}/workflow-rules")
    fun listRules(@PathVariable key: String): List<GithubConfigService.RuleDto> = service.listRules(key)

    @PutMapping("/projects/{key}/workflow-rules")
    fun replaceRules(@PathVariable key: String, @Valid @RequestBody request: ReplaceRulesRequest): List<GithubConfigService.RuleDto> =
        service.replaceRules(key, request.rules)
}
