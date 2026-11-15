package yangzhou.api.business.item

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import yangzhou.api.business.github.GithubCommitService
import yangzhou.persistence.repository.TimeEntryRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** YPJ-184(spec 0125):FINAL 钩子 best-effort——快照失败绝不外泄。 */
class ItemFinalizeServiceUnitTest {
    private val githubCommits = mockk<GithubCommitService>()
    private val timeEntries = mockk<TimeEntryRepository>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneId.of("UTC"))
    private val service = ItemFinalizeService(githubCommits, timeEntries, clock)

    private val itemObjectId = UUID(0, 7)

    @Test
    fun `进 FINAL 转发快照 captured_by finalize`() {
        every { githubCommits.snapshotForItem(itemObjectId, capturedBy = "finalize") } returns Pair(1, 0)
        every { timeEntries.findByItemObjectIdAndEndedAtIsNull(itemObjectId) } returns emptyList()

        service.onFinalized(itemObjectId)

        verify(exactly = 1) { githubCommits.snapshotForItem(itemObjectId, capturedBy = "finalize") }
    }

    @Test
    fun `进 FINAL 停表——运行中工时 endedAt 固定为时钟时刻`() {
        every { githubCommits.snapshotForItem(any(), any()) } returns Pair(0, 0)
        val running = yangzhou.persistence.TimeEntry(itemObjectId = itemObjectId, memberObjectId = UUID(0, 8), startedAt = Instant.parse("2026-11-14T10:00:00Z"))
        every { timeEntries.findByItemObjectIdAndEndedAtIsNull(itemObjectId) } returns listOf(running)
        every { timeEntries.saveAll(any<List<yangzhou.persistence.TimeEntry>>()) } answers { firstArg() }

        service.onFinalized(itemObjectId)

        verify(exactly = 1) {
            timeEntries.saveAll(
                match<List<yangzhou.persistence.TimeEntry>> {
                    it.size == 1 && it[0].endedAt == Instant.parse("2026-11-15T00:00:00Z")
                },
            )
        }
    }

    @Test
    fun `快照抛异常不外泄——状态迁移不被阻塞`() {
        every { githubCommits.snapshotForItem(any(), any()) } throws RuntimeException("GitHub 不可达")

        service.onFinalized(itemObjectId) // 不抛即绿
    }
}
