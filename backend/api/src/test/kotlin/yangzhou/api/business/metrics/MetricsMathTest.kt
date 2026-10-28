package yangzhou.api.business.metrics

import java.util.UUID
import yangzhou.persistence.ItemGroupMemberHistory
import yangzhou.persistence.repository.MetricsHistoryRow
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 纯计算函数直测(无 Spring;spec 0038 ②)。 */
class MetricsMathTest {

    private fun row(itemObjectId: UUID = UUID(0, 1), from: UUID?, to: UUID, at: String) =
        MetricsHistoryRow(itemObjectId = itemObjectId, fromStatusObjectId = from, toStatusObjectId = to, createdAt = Instant.parse(at))

    private val h = listOf(
        row(from = null, to = UUID(0, 10), at = "2026-01-01T09:00:00Z"), // created → To Do(id=10)
        row(from = UUID(0, 10), to = UUID(0, 20), at = "2026-01-02T09:00:00Z"),   // 离开 start → In Progress(id=20)
        row(from = UUID(0, 20), to = UUID(0, 30), at = "2026-01-05T09:00:00Z"),   // → Done(id=30, final)
        row(from = UUID(0, 30), to = UUID(0, 20), at = "2026-01-06T09:00:00Z"),   // 重开 → In Progress
        row(from = UUID(0, 20), to = UUID(0, 30), at = "2026-01-09T09:00:00Z"),   // 再完成(不重复计)
    )

    @Test
    fun percentile_空表与单点() {
        assertNull(percentile(emptyList(), 50.0))
        assertEquals(7.0, percentile(listOf(7.0), 85.0))
    }

    @Test
    fun percentile_线性插值() {
        val sorted = listOf(1.0, 3.0, 20.0)
        assertEquals(3.0, percentile(sorted, 50.0))
        assertEquals(14.9, percentile(sorted, 85.0)!!, 1e-9)
        assertEquals(18.3, percentile(sorted, 95.0)!!, 1e-9)
    }

    @Test
    fun 完成时刻_取首次进final_重开不重复计() {
        assertEquals(Instant.parse("2026-01-05T09:00:00Z"), completionTs(h, setOf(UUID(0, 30))))
        assertNull(completionTs(h, setOf(UUID(0, 99))))
    }

    @Test
    fun 周期起点_取首次离开start_创建行不计() {
        assertEquals(Instant.parse("2026-01-02T09:00:00Z"), cycleStartTs(h, setOf(UUID(0, 10))))
        // 从未离开 start:仅创建行
        val onlyCreated = listOf(row(from = null, to = UUID(0, 10), at = "2026-01-01T09:00:00Z"))
        assertNull(cycleStartTs(onlyCreated, setOf(UUID(0, 10))))
    }

    @Test
    fun 状态回放_各时点() {
        assertNull(statusAt(h, Instant.parse("2026-01-01T08:00:00Z")))
        assertEquals(UUID(0, 10), statusAt(h, Instant.parse("2026-01-01T12:00:00Z")))
        assertEquals(UUID(0, 20), statusAt(h, Instant.parse("2026-01-04T00:00:00Z")))
        assertEquals(UUID(0, 30), statusAt(h, Instant.parse("2026-01-05T12:00:00Z")))
        assertEquals(UUID(0, 20), statusAt(h, Instant.parse("2026-01-07T00:00:00Z"))) // 重开中
        assertEquals(UUID(0, 30), statusAt(h, Instant.parse("2026-01-20T00:00:00Z")))
    }

    @Test
    fun 周桶起点_周一同界() {
        assertEquals(java.time.LocalDate.parse("2026-09-21"), weekStartUtc(java.time.LocalDate.parse("2026-09-25")))
        assertEquals(java.time.LocalDate.parse("2026-10-19"), weekStartUtc(java.time.LocalDate.parse("2026-10-25"))) // 周日
        assertEquals(java.time.LocalDate.parse("2026-09-28"), weekStartUtc(java.time.LocalDate.parse("2026-09-28"))) // 周一自身
    }

    @Test
    fun 天数差_含小数() {
        val d = daysBetween(Instant.parse("2026-01-01T09:00:00Z"), Instant.parse("2026-01-02T21:00:00Z"))
        assertEquals(1.5, d, 1e-9)
        assertTrue(d > 0)
    }

    @Test
    fun covering_边界与离组() {
        val t = Instant.parse("2026-10-05T00:00:00Z")
        assertEquals(emptySet<UUID>(), coveringItemObjectIds(emptyList(), t))
        val episodes = listOf(
            ItemGroupMemberHistory(groupObjectId = UUID(0, 1), itemObjectId = UUID(0, 11), addedAt = t), // 边界:added == t
            ItemGroupMemberHistory(groupObjectId = UUID(0, 1), itemObjectId = UUID(0, 12), addedAt = Instant.parse("2026-10-04T00:00:00Z"), removedAt = t), // removed == t 不覆盖
            ItemGroupMemberHistory(groupObjectId = UUID(0, 1), itemObjectId = UUID(0, 13), addedAt = Instant.parse("2026-10-01T00:00:00Z"), removedAt = t.plusMillis(1)),
            ItemGroupMemberHistory(groupObjectId = UUID(0, 2), itemObjectId = UUID(0, 14), addedAt = t.plusMillis(1)), // 尚未加入
        )
        assertEquals(setOf(UUID(0, 11), UUID(0, 13)), coveringItemObjectIds(episodes, t))
    }
}
