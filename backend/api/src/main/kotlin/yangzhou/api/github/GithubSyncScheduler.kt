package yangzhou.api.github

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 轮询入口:yaml 可关(poll-enabled)/调频(poll-interval-ms);首延同间隔,不拖慢启动。 */
@Component
@ConditionalOnProperty(prefix = "yangzhou.github", name = ["poll-enabled"], havingValue = "true", matchIfMissing = true)
class GithubSyncScheduler(private val syncService: GithubSyncService) {

    @Scheduled(
        fixedDelayString = "\${yangzhou.github.poll-interval-ms:300000}",
        initialDelayString = "\${yangzhou.github.poll-interval-ms:300000}",
    )
    fun poll() = syncService.syncAll()
}
