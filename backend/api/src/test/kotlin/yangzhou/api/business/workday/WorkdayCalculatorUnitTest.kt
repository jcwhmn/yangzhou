package yangzhou.api.business.workday

import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-208(spec 0138 切片 b):时长铺排纯函数(09:00–18:00 即 8h 工作日)。 */
class WorkdayCalculatorUnitTest {
    private val calc = WorkdayCalculator(LocalTime.of(9, 0), LocalTime.of(18, 0))

    // ---------- SRS v4 §3.2(2026-11-21 拍板):24h 全天窗(两端 00:00)----------

    private val fullDay = WorkdayCalculator(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT)

    // ---------- YPJ-226(spec 0163):工作制 skipWeekends=true(2026-11-20 周五/周一为 11-23/11-25... 用 2026-11 例:11-20=周五,11-21=周六,11-22=周日,11-23=周一) ----------

    private val workMode = WorkdayCalculator(LocalTime.of(9, 0), LocalTime.of(18, 0), skipWeekends = true)

    @Test
    fun `全天窗 dueFrom 等于钟表加法——跨零点不折断`() {
        assertEquals(
            LocalDateTime.of(2026, 11, 18, 0, 30),
            fullDay.dueFrom(LocalDateTime.of(2026, 11, 17, 14, 30), 10.0),
        )
    }

    @Test
    fun `全天窗 hoursBetween 等于钟表差`() {
        assertEquals(
            10.0,
            fullDay.hoursBetween(LocalDateTime.of(2026, 11, 17, 14, 30), LocalDateTime.of(2026, 11, 18, 0, 30)),
        )
    }

    @Test
    fun `全天窗恰好整日——返回次日零点`() {
        assertEquals(
            LocalDateTime.of(2026, 11, 18, 0, 0),
            fullDay.dueFrom(LocalDateTime.of(2026, 11, 17, 0, 0), 24.0),
        )
    }

    @Test
    fun `工作制周五尾量不足——跳周末落到周一`() {
        // 周五 11-20 16:00 剩 2h,当日只余 2h?——用 17:00 起 1h:当日余 1h 恰铺满收工?改为 4h 跨周末
        assertEquals(
            LocalDateTime.of(2026, 11, 23, 12, 0),
            workMode.dueFrom(LocalDateTime.of(2026, 11, 20, 17, 0), 4.0),
        )
    }

    @Test
    fun `工作制 start 落周六——钳到周一开工`() {
        assertEquals(
            LocalDateTime.of(2026, 11, 23, 13, 0),
            workMode.dueFrom(LocalDateTime.of(2026, 11, 21, 10, 0), 4.0),
        )
    }

    @Test
    fun `工作制 hoursBetween 不计周末`() {
        // 周五 18:00 后到周一 09:00:0;周五 16:00→周一 13:00 = 2h + 4h
        assertEquals(
            0.0,
            workMode.hoursBetween(LocalDateTime.of(2026, 11, 20, 18, 0), LocalDateTime.of(2026, 11, 23, 9, 0)),
        )
        assertEquals(
            6.0,
            workMode.hoursBetween(LocalDateTime.of(2026, 11, 20, 16, 0), LocalDateTime.of(2026, 11, 23, 13, 0)),
        )
    }

    @Test
    fun `全天窗加跳周末——铺算跨周末顺延周一,窗口正交 spec 0163`() {
        val fullDaySkip = WorkdayCalculator(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, skipWeekends = true)
        assertEquals(
            LocalDateTime.of(2026, 11, 24, 10, 0),
            fullDaySkip.dueFrom(LocalDateTime.of(2026, 11, 20, 10, 0), 48.0),
        )
    }

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

    // ---------- YPJ-243(spec 0150):hoursBetween(dueFrom 的逆) ----------

    @Test
    fun `hoursBetween——当日部分区间`() {
        assertEquals(
            5.0,
            calc.hoursBetween(LocalDateTime.of(2026, 11, 17, 9, 0), LocalDateTime.of(2026, 11, 17, 14, 0)),
        )
    }

    @Test
    fun `hoursBetween——跨日累加`() {
        assertEquals(
            13.0, // 周二 9h(09-18)+ 周三 4h(09-13)
            calc.hoursBetween(LocalDateTime.of(2026, 11, 17, 9, 0), LocalDateTime.of(2026, 11, 18, 13, 0)),
        )
    }

    @Test
    fun `hoursBetween——同刻与倒挂为 0`() {
        val t = LocalDateTime.of(2026, 11, 17, 12, 0)
        assertEquals(0.0, calc.hoursBetween(t, t))
        assertEquals(0.0, calc.hoursBetween(LocalDateTime.of(2026, 11, 17, 14, 0), t))
    }

    @Test
    fun `hoursBetween——收工后起点顺延次日起算`() {
        assertEquals(
            1.0, // 周二 19:00 → 周三 10:00:仅周三 09-10 计 1h
            calc.hoursBetween(LocalDateTime.of(2026, 11, 17, 19, 0), LocalDateTime.of(2026, 11, 18, 10, 0)),
        )
    }

    @Test
    fun `hoursBetween——跨周末照算(周六日不跳,同 dueFrom)`() {
        assertEquals(
            20.0, // 周五 17-18=1h + 周六 9h + 周日 9h + 周一 09-10=1h
            calc.hoursBetween(LocalDateTime.of(2026, 11, 20, 17, 0), LocalDateTime.of(2026, 11, 23, 10, 0)),
        )
    }
}
