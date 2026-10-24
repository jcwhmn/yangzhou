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
}
