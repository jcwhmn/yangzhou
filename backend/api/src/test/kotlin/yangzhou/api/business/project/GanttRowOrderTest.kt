package yangzhou.api.business.project

import yangzhou.persistence.Item
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** YPJ-233(spec 0148)+ YPJ-244 修订(排序锚=渲染锚):叶行 start 升序、未排期靠后、创建序兜底;
 *  父行取子孙并集最早端(自有 start 忽略,与 0139 汇总条同源)。纯函数直测(不动 DB)。 */
class GanttRowOrderTest {
    private fun dt(day: Int, hour: Int = 9) = LocalDateTime.of(2026, 12, day, hour, 0)

    private fun item(number: Int, start: LocalDateTime? = null) = Item(
        projectObjectId = UUID.randomUUID(),
        number = number,
        title = "i$number",
        statusObjectId = UUID.randomUUID(),
        startDate = start,
    )

    @Test
    fun `start 升序且无 start 靠后`() {
        // 创建序故意与 start 序错开
        val items = listOf(item(1, dt(3)), item(2, null), item(3, dt(1)), item(4, dt(2)))
        assertEquals(listOf(3, 4, 1, 2), ganttWalkOrder(items) { emptyList() }.map { it.number })
    }

    @Test
    fun `同 start 按创建序兜底`() {
        val items = listOf(item(2, dt(1)), item(1, dt(1)), item(3, dt(1)))
        assertEquals(listOf(1, 2, 3), ganttWalkOrder(items) { emptyList() }.map { it.number })
    }

    @Test
    fun `未排期组内按创建序`() {
        val items = listOf(item(3), item(1), item(2))
        assertEquals(listOf(1, 2, 3), ganttWalkOrder(items) { emptyList() }.map { it.number })
    }

    @Test
    fun `due-only 有 due 无 start 落未排期组(字面语义防误改)`() {
        val diamond = item(1, null).copy(dueDate = dt(5))
        val late = item(2, dt(9))
        assertEquals(listOf(2, 1), ganttWalkOrder(listOf(diamond, late)) { emptyList() }.map { it.number })
    }

    @Test
    fun `父行按子孙最早排期排序,自有 start 忽略(YPJ-244 207-208 案例)`() {
        val p207 = item(207, dt(18))
        val p208 = item(208, dt(21))
        val c223 = item(223, dt(15))
        val kids = mapOf(p208.objectId!! to listOf(c223))
        val ordered = ganttWalkOrder(listOf(p207, p208)) { kids[it] ?: emptyList() }
        assertEquals(listOf(208, 207), ordered.map { it.number })
    }

    @Test
    fun `子孙全未排期的父行落未排期组(渲染无汇总条,同口径)`() {
        val dated = item(1, dt(9))
        val parent = item(2, dt(3)) // 自有 start 但渲染不画,排序同口径忽略
        val child = item(3, null)
        val kids = mapOf(parent.objectId!! to listOf(child))
        val ordered = ganttWalkOrder(listOf(parent, dated)) { kids[it] ?: emptyList() }
        assertEquals(listOf(1, 2), ordered.map { it.number })
    }

    @Test
    fun `子孙 due-only 以 due 参与父锚(与汇总条同源),due-only 叶自身仍落未排期组`() {
        val parent = item(1, dt(20))
        val dueOnlyChild = item(2, null).copy(dueDate = dt(16))
        val leaf = item(3, dt(18))
        val kids = mapOf(parent.objectId!! to listOf(dueOnlyChild))
        val ordered = ganttWalkOrder(listOf(parent, leaf, dueOnlyChild)) { kids[it] ?: emptyList() }
        assertEquals(listOf(1, 3, 2), ordered.map { it.number })
    }
}
