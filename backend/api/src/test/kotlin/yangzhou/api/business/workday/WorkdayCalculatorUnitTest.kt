package yangzhou.api.business.workday

import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-208(spec 0138 切片 b):时长铺排纯函数(09:00–18:00 即 8h 工作日)。 */
class WorkdayCalculatorUnitTest {
    private val calc = WorkdayCalculator(LocalTime.of(9, 0), LocalTime.of(18, 0))

    @Test
    fun `当日装得下——从开工起算`() {
        assertEquals(
            LocalDateTime.of(2026, 11, 17, 13, 0),
            calc.dueFrom(LocalDateTime.of(2026, 11, 17, 9, 0), 4.0),
        )
    }

    @Test
    fun `start 晚于开工——从 start 起算`() {
        assertEquals(
            LocalDateTime.of(2026, 11, 17, 17, 30),
            calc.dueFrom(LocalDateTime.of(2026, 11, 17, 14, 30), 3.0),
        )
    }

    @Test
    fun `跨日铺排——剩余顺延次日开工`() {
        assertEquals(
            LocalDateTime.of(2026, 11, 18, 15, 30),
            calc.dueFrom(LocalDateTime.of(2026, 11, 17, 14, 30), 10.0),
        )
    }

    @Test
    fun `恰好铺满前收工——8h 得当日 17 点(9 点开工)`() {
        assertEquals(
            LocalDateTime.of(2026, 11, 17, 17, 0),
            calc.dueFrom(LocalDateTime.of(2026, 11, 17, 9, 0), 8.0),
        )
    }

    @Test
    fun `start 在收工后或工前——顺延开工起算`() {
        assertEquals(
            LocalDateTime.of(2026, 11, 18, 10, 0),
            calc.dueFrom(LocalDateTime.of(2026, 11, 17, 20, 0), 1.0),
        )
        assertEquals(
            LocalDateTime.of(2026, 11, 17, 10, 0),
            calc.dueFrom(LocalDateTime.of(2026, 11, 17, 7, 0), 1.0),
        )
    }

    @Test
    fun `非正时长返回 start 非法日历拒绝`() {
        val t = LocalDateTime.of(2026, 11, 17, 12, 0)
        assertEquals(t, calc.dueFrom(t, 0.0))
        assertEquals(t, calc.dueFrom(t, -1.0))
        assertFailsWith<IllegalArgumentException> {
            WorkdayCalculator(LocalTime.of(18, 0), LocalTime.of(9, 0))
        }
    }
}
