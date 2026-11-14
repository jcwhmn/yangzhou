package yangzhou.api.business.item

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import yangzhou.api.business.github.GithubCommitService
import java.util.UUID

/** YPJ-184(spec 0125):FINAL 钩子 best-effort——快照失败绝不外泄。 */
class ItemFinalizeServiceUnitTest {
    private val githubCommits = mockk<GithubCommitService>()
    private val service = ItemFinalizeService(githubCommits)

    private val itemObjectId = UUID(0, 7)

    @Test
    fun `进 FINAL 转发快照 captured_by finalize`() {
        every { githubCommits.snapshotForItem(itemObjectId, capturedBy = "finalize") } returns Pair(1, 0)

        service.onFinalized(itemObjectId)

        verify(exactly = 1) { githubCommits.snapshotForItem(itemObjectId, capturedBy = "finalize") }
    }

    @Test
    fun `快照抛异常不外泄——状态迁移不被阻塞`() {
        every { githubCommits.snapshotForItem(any(), any()) } throws RuntimeException("GitHub 不可达")

        service.onFinalized(itemObjectId) // 不抛即绿
    }
}
