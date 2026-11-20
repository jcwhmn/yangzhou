package yangzhou.api.business.workday

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * YPJ-208(spec 0138 切片 b):按工作日历做时长换算的纯函数。
 * dueFrom(start, hours):从 start 起把 hours 铺进工作时段(每日 workdayStart→workdayEnd);
 * start 早于开工按开工起算,在收工后则顺延次日开工;当日剩余不足顺延次日;
 * 恰好铺满当日收工时返回当日收工时刻。周六日照算(节假日/双休日历另票)。
 * 非正时长返回 start。
 */
class WorkdayCalculator(
    private val workdayStart: LocalTime,
    private val workdayEnd: LocalTime,
) {
    init {
        require(workdayStart < workdayEnd) { "workdayStart 必须早于 workdayEnd" }
    }

    fun dueFrom(
        start: LocalDateTime,
        hours: Double,
    ): LocalDateTime {
        if (hours <= 0.0) return start
        var day = start.toLocalDate()
        var cursor = maxOf(start, day.atTime(workdayStart))
        var remainingMin = Math.round(hours * 60)
        while (remainingMin > 0) {
            val dayEnd = day.atTime(workdayEnd)
            if (cursor >= dayEnd) {
                day = day.plusDays(1)
                cursor = day.atTime(workdayStart)
                continue
            }
            val available = Duration.between(cursor, dayEnd).toMinutes()
            if (remainingMin >= available) {
                remainingMin -= available
                if (remainingMin == 0L) return dayEnd
                day = day.plusDays(1)
                cursor = day.atTime(workdayStart)
            } else {
                return cursor.plusMinutes(remainingMin)
            }
        }
        return cursor
    }

    /** YPJ-243(spec 0150):dueFrom 的逆——[start, due] 落在工作时段内的时长(逐日 [max(start,开工), min(due,收工)] 累加);due<=start 返回 0;周六日照算同 dueFrom。 */
    fun hoursBetween(
        start: LocalDateTime,
        due: LocalDateTime,
    ): Double {
        if (!due.isAfter(start)) return 0.0
        var day = start.toLocalDate()
        var totalMin = 0L
        while (day <= due.toLocalDate()) {
            val s = maxOf(start, day.atTime(workdayStart))
            val e = minOf(due, day.atTime(workdayEnd))
            if (e.isAfter(s)) totalMin += Duration.between(s, e).toMinutes()
            day = day.plusDays(1)
        }
        return totalMin / 60.0
    }
}
