package yangzhou.api.business.item

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import yangzhou.api.business.github.GithubCommitService
import yangzhou.persistence.repository.TimeEntryRepository
import java.time.Clock
import java.util.UUID

/**
 * YPJ-184(spec 0125):item 状态进 FINAL 的统一后置动作点。
 * 单钩子单消费者,直接服务调用(仓内无事件机制先例,不引事件框架)。
 * best-effort 仅限快照类动作(GitHub 快照);核心域行为(YPJ-181 停表,spec 0129)失败随迁移事务回滚暴露,不吞。
 */
@Service
class ItemFinalizeService(
    private val githubCommits: GithubCommitService,
    private val timeEntries: TimeEntryRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(ItemFinalizeService::class.java)

    fun onFinalized(itemObjectId: UUID) {
        runCatching { githubCommits.snapshotForItem(itemObjectId, capturedBy = "finalize") }
            .onFailure { log.warn("FINAL 快照失败(不阻塞状态迁移) item={} cause={}", itemObjectId, it.message) }
        // YPJ-181(spec 0129):停掉该 item 全部进行中工时(endedAt=now);运行中的才动,重开再 Done 幂等
        val running = timeEntries.findByItemObjectIdAndEndedAtIsNull(itemObjectId)
        if (running.isNotEmpty()) {
            timeEntries.saveAll(running.map { it.copy(endedAt = clock.instant()) })
            log.info("FINAL 停表:item={}, 停 {} 条进行中工时", itemObjectId, running.size)
        }
    }
}
