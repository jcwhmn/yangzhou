package yangzhou.api.business.item

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import yangzhou.api.business.github.GithubCommitService
import java.util.UUID

/**
 * YPJ-184(spec 0125):item 状态进 FINAL 的统一后置动作点。
 * 单钩子单消费者,直接服务调用(仓内无事件机制先例,不引事件框架)。
 * YPJ-181(item 进 FINAL 自动停表)后续接入同一钩子,实现共享、验收各归各票。
 * 全部动作 best-effort:失败仅日志,绝不阻塞状态迁移。
 */
@Service
class ItemFinalizeService(
    private val githubCommits: GithubCommitService,
) {
    private val log = LoggerFactory.getLogger(ItemFinalizeService::class.java)

    fun onFinalized(itemObjectId: UUID) {
        runCatching { githubCommits.snapshotForItem(itemObjectId, capturedBy = "finalize") }
            .onFailure { log.warn("FINAL 快照失败(不阻塞状态迁移) item={} cause={}", itemObjectId, it.message) }
    }
}
