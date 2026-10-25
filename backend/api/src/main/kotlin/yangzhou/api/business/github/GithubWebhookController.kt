package yangzhou.api.business.github

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.Executors
import yangzhou.persistence.repository.ProjectRepoRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.WorkspaceRepository

/** YPJ-85:GitHub webhook 匿名回调,秒级触发既有同步;签名不符/secret 未配置一律 403 全拒。 */
@RestController
@RequestMapping("/api")
class GithubWebhookController(
    private val workspaces: WorkspaceRepository,
    private val projectRepos: ProjectRepoRepository,
    private val projects: ProjectRepository,
    private val syncService: GithubSyncService,
    private val mapper: ObjectMapper,
    @Value("\${yangzhou.github.webhook-secret:}") private val secret: String,
) {
    private val log = LoggerFactory.getLogger(GithubWebhookController::class.java)

    @PostMapping("/github/webhook")
    fun receive(
        @RequestHeader(value = "X-Hub-Signature-256", required = false) signature: String?,
        @RequestHeader("X-GitHub-Event") event: String,
        @RequestBody rawBody: String,
    ): ResponseEntity<Map<String, String>> {
        if (secret.isBlank() || signature == null || !WebhookSignature.verify(secret, rawBody, signature)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(mapOf("status" to "forbidden"))
        }
        if (event !in EVENTS) return ResponseEntity.ok(mapOf("status" to "ignored"))
        val repo = mapper.readTree(rawBody).path("repository").path("full_name").asString(null)
        if (repo.isNullOrEmpty()) return ResponseEntity.ok(mapOf("status" to "ignored"))
        val mounts = projectRepos.findByRepo(repo)
        if (mounts.isEmpty()) return ResponseEntity.ok(mapOf("status" to "ignored"))
        val token = workspaces.findAll().firstOrNull()?.githubToken
        if (token == null) {
            log.warn("webhook 命中挂载 {} 但 workspace 未配置 PAT,跳过同步", repo)
            return ResponseEntity.accepted().body(mapOf("status" to "accepted"))
        }
        // GitHub webhook 10s 超时:syncRepo 全量拉 branches+PRs 可能超时 → 后台执行,立即 202
        val targets = mounts.mapNotNull { m ->
            projects.findById(m.projectId).orElse(null)?.let { m.repo to it }
        }
        executor.submit {
            for ((r, project) in targets) {
                try {
                    syncService.syncRepo(project, r, token)
                } catch (e: Exception) {
                    log.warn("webhook 同步失败,等下轮兜底:{}:{}", r, e.message)
                }
            }
        }
        return ResponseEntity.accepted().body(mapOf("status" to "accepted"))
    }

    companion object {
        private val EVENTS = setOf("push", "pull_request")
        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "github-webhook-sync").apply { isDaemon = true }
        }
    }
}
