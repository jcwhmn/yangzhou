package yangzhou.api.business.item

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** overdue/dueSoon 边界(时钟纪律:today 由调用方传入,可钉死;spec 0051)。 */
class ItemDueSignalsUnitTest {
    private val today = LocalDate.of(2026, 10, 18)

    @Test
    fun `overdue 当天不算,过期且非终态算`() {
        assertFalse(isOverdue(today, today, statusFinal = false))
        assertTrue(isOverdue(today.minusDays(1), today, statusFinal = false))
        // 终态不标逾期(既有行为)
        assertFalse(isOverdue(today.minusDays(5), today, statusFinal = true))
        assertFalse(isOverdue(null, today, statusFinal = false))
    }

    @Test
    fun `dueSoon 含当天,3 天窗口右开`() {
        assertTrue(isDueSoon(today, today))
        assertTrue(isDueSoon(today.plusDays(2), today))
        assertFalse(isDueSoon(today.plusDays(3), today))
        assertFalse(isDueSoon(today.minusDays(1), today))
        assertFalse(isDueSoon(null, today))
    }

    /** YPJ-208(spec 0138):时刻口径( overdue=now>due;dueSoon 窗口 [now, now+3d) 右开)。 */
    @Test
    fun `时刻口径 overdue now 大于 due 才算,dueSoon 右开窗口`() {
        val now = java.time.LocalDateTime.of(2026, 10, 18, 12, 0)
        assertFalse(isOverdue(now, now, statusFinal = false))
        assertTrue(isOverdue(now.minusMinutes(1), now, statusFinal = false))
        assertFalse(isOverdue(now.minusDays(5), now, statusFinal = true))
        assertFalse(isOverdue(null, now, statusFinal = false))
        assertTrue(isDueSoon(now.plusDays(2), now))
        assertFalse(isDueSoon(now.plusDays(3), now))
        assertFalse(isDueSoon(now.minusMinutes(1), now))
    }
}
