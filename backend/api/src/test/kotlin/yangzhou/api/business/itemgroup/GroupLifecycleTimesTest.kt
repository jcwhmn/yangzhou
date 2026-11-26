package yangzhou.api.business.itemgroup

import yangzhou.persistence.GroupStatusHistory
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** YPJ-269(spec 0174):组生命周期公式单测——与 item 侧 LifecycleTimes 同构(首次迁出 START/最近迁入 FINAL)。 */
class GroupLifecycleTimesTest {
    private val s1 = UUID.randomUUID() // planned(START)
    private val s2 = UUID.randomUUID() // active
    private val s3 = UUID.randomUUID() // completed(FINAL)

    private val base = Instant.ofEpochSecond(1_700_000_000L)

    private fun row(
        to: UUID,
        offsetMinutes: Long,
    ): GroupStatusHistory = GroupStatusHistory(
        groupObjectId = UUID.randomUUID(),
        toGroupStatusObjectId = to,
        source = "action",
        createdAt = base.plusSeconds(offsetMinutes * 60),
    )

    private fun rows(vararg targets: UUID) = targets.mapIndexed { i, t -> row(t, (i + 1) * 10L) }

    @Test
    fun `startedAt_首次迁出START_仅created行为null`() {
        assertNull(GroupLifecycleTimes.startedAt(rows(s1), setOf(s1))) // 仅初始进入
        assertEquals(base.plusSeconds(20 * 60), GroupLifecycleTimes.startedAt(rows(s1, s2, s3), setOf(s1)))
        // backward 后再 start:首次迁出时刻不覆写
        assertEquals(base.plusSeconds(20 * 60), GroupLifecycleTimes.startedAt(rows(s1, s2, s1, s2, s3), setOf(s1)))
    }

    @Test
    fun `completedAt_最近迁入FINAL_reopen未再完成保留上次`() {
        assertNull(GroupLifecycleTimes.completedAt(rows(s1, s2), setOf(s3)))
        assertEquals(base.plusSeconds(30 * 60), GroupLifecycleTimes.completedAt(rows(s1, s2, s3), setOf(s3)))
        // completed→reopen→active:completedAt 保留上次完成时刻
        assertEquals(base.plusSeconds(30 * 60), GroupLifecycleTimes.completedAt(rows(s1, s2, s3, s2), setOf(s3)))
        // 再次完成:取最近
        assertEquals(base.plusSeconds(50 * 60), GroupLifecycleTimes.completedAt(rows(s1, s2, s3, s2, s3), setOf(s3)))
    }
}
