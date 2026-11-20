package yangzhou.api.business.project

import yangzhou.persistence.Item
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** YPJ-233(spec 0148)+ YPJ-244/SRS v4 修订(排序锚=渲染锚,2026-11-21 拍板+评审 P0-2/P1-1 修订):
 *  叶锚 = min(计划左端 start??due, 实际左端 actualStart??完成点);父行 = 叶子孙并集最早端(父行自有作废,SRS 4-3);
 *  未排期 = 计划与实际均无元素。纯函数直测(不动 DB)。 */
class GanttRowOrderTest {
    private fun dt(day: Int, hour: Int = 9, minute: Int = 0) = LocalDateTime.of(2026, 12, day, hour, minute)

    private fun item(number: Int, start: LocalDateTime? = null) = Item(
        projectObjectId = UUID.randomUUID(),
        number = number,
        title = "i$number",
        statusObjectId = UUID.randomUUID(),
        startDate = start,
    )

    private val noActual: (Item) -> Pair<LocalDateTime?, LocalDateTime?> = { null to null }

    private fun actualOf(target: Int, aS: LocalDateTime?, aE: LocalDateTime?): (Item) -> Pair<LocalDateTime?, LocalDateTime?> = { if (it.number == target) aS to aE else null to null }

    @Test
    fun `start 升序且无排期靠后`() {
        // 创建序故意与 start 序错开
        val items = listOf(item(1, dt(3)), item(2, null), item(3, dt(1)), item(4, dt(2)))
        assertEquals(listOf(3, 4, 1, 2), ganttWalkOrder(items, { emptyList() }, noActual).map { it.number })
    }

    @Test
    fun `同 start 按创建序兜底`() {
        val items = listOf(item(2, dt(1)), item(1, dt(1)), item(3, dt(1)))
        assertEquals(listOf(1, 2, 3), ganttWalkOrder(items, { emptyList() }, noActual).map { it.number })
    }

    @Test
    fun `未排期组内按创建序`() {
        val items = listOf(item(3), item(1), item(2))
        assertEquals(listOf(1, 2, 3), ganttWalkOrder(items, { emptyList() }, noActual).map { it.number })
    }

    @Test
    fun `叶行仅 actual——按实际起点排,不垫尾(SRS 8-2,YPJ-206 案例)`() {
        val research = item(206) // 三无,仅 actualStart 12-10
        val dated = item(207, dt(18))
        val ordered = ganttWalkOrder(listOf(dated, research), { emptyList() }, actualOf(206, dt(10), null))
        assertEquals(listOf(206, 207), ordered.map { it.number })
    }

    @Test
    fun `完成行实际早于计划——按可见最左端排(评审 P1-1,final actual 12-08 早于 start 12-10)`() {
        val early = item(1, dt(10)) // 计划 12-10,实际 12-08 完成
        val rival = item(2, dt(9))
        // rival 12-09 介于实际 12-08 与计划 12-10 之间 → 按可见最左端(实际 12-08)应排最前
        val ordered = ganttWalkOrder(listOf(early, rival), { emptyList() }, actualOf(1, null, dt(8)))
        assertEquals(listOf(1, 2), ordered.map { it.number })
    }

    @Test
    fun `直建 FINAL(actualEnd 点)参与排序且不入未排期组(评审 P0-2)`() {
        val point = item(1) // 无任何计划字段,仅 actualEnd 12-12(完成点)
        val unsched = item(2) // 全空 → 未排期
        val dated = item(3, dt(15))
        val ordered = ganttWalkOrder(listOf(unsched, dated, point), { emptyList() }, actualOf(1, null, dt(12)))
        assertEquals(listOf(1, 3, 2), ordered.map { it.number })
    }

    @Test
    fun `due-only 以 due 为可见锚位排(SRS 8-2 拍板 Q6-B,数据由 Q11 契约杜绝新增)`() {
        val diamond = item(1, null).copy(dueDate = dt(16))
        val late = item(2, dt(18))
        assertEquals(listOf(1, 2), ganttWalkOrder(listOf(late, diamond), { emptyList() }, noActual).map { it.number })
    }

    @Test
    fun `父行按叶子孙最早排期排序,自有 start 作废(YPJ-244 207-208 案例)`() {
        val p207 = item(207, dt(18))
        val p208 = item(208, dt(21))
        val c223 = item(223, dt(15))
        val kids = mapOf(p208.objectId!! to listOf(c223))
        val ordered = ganttWalkOrder(listOf(p207, p208), { kids[it] ?: emptyList() }, noActual)
        assertEquals(listOf(208, 207), ordered.map { it.number })
    }

    @Test
    fun `子孙全未排期的父行落未排期组(渲染无汇总条,同口径)`() {
        val dated = item(1, dt(9))
        val parent = item(2, dt(3)) // 自有 start 作废(SRS 4-3),子全未排 → 未排期组
        val child = item(3, null)
        val kids = mapOf(parent.objectId!! to listOf(child))
        val ordered = ganttWalkOrder(listOf(parent, dated), { kids[it] ?: emptyList() }, noActual)
        assertEquals(listOf(1, 2), ordered.map { it.number })
    }

    @Test
    fun `父行包络左端并入子孙 actualStart(YPJ-217-218 案例,SRS 7-2)`() {
        val p217 = item(217) // 三无父行
        val c218 = item(218) // 叶,仅 actualStart 12-17 13:21
        val c219 = item(219, dt(18, 9)) // 叶,start 12-18 09:00
        val kids = mapOf(p217.objectId!! to listOf(c218, c219))
        val rival = item(207, dt(18)) // 叶,start 12-18
        val a218 = actualOf(218, dt(17, 13, 21), null)
        val ordered = ganttWalkOrder(listOf(p217, rival), { kids[it] ?: emptyList() }, a218)
        // 217 包络 = min(218 actual 12-17 13:21, 219 12-18 09:00) = 12-17 13:21 → 先于 207
        assertEquals(listOf(217, 207), ordered.map { it.number })
        val inner = ganttWalkOrder(kids[p217.objectId!!]!!, { emptyList() }, a218)
        assertEquals(listOf(218, 219), inner.map { it.number })
    }

    @Test
    fun `中间层父行自有日期作废——不再计入祖先包络(SRS 4-3,219 案)`() {
        val p217 = item(217) // 三无父行
        val c219 = item(219, dt(18, 9)) // 中间层父行:自有 start 12-18 09:00(作废)
        val g219 = item(231, dt(19)) // 219 之叶
        val leaf223 = item(223, dt(18, 12)) // 根级叶
        val kids = mapOf(
            p217.objectId!! to listOf(c219),
            c219.objectId!! to listOf(g219),
        )
        val ordered = ganttWalkOrder(listOf(p217, leaf223), { kids[it] ?: emptyList() }, noActual)
        // 旧口径:217 锚 = 219 自有 12-18 09:00 → 217 先;新口径:219 自有作废,217 锚 = 12-19 → 223(12-18 12:00)反超
        assertEquals(listOf(223, 217), ordered.map { it.number })
    }
}
