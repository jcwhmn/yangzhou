package yangzhou.api.business.project

import yangzhou.persistence.Item
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** YPJ-233(拍板 2026-11-18):甘特同级排序——start 升序、未排期靠后、创建序兜底;纯比较器直测(不动 DB)。 */
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
        assertEquals(listOf(3, 4, 1, 2), items.sortedWith(ganttSiblingOrder).map { it.number })
    }

    @Test
    fun `同 start 按创建序兜底`() {
        val items = listOf(item(2, dt(1)), item(1, dt(1)), item(3, dt(1)))
        assertEquals(listOf(1, 2, 3), items.sortedWith(ganttSiblingOrder).map { it.number })
    }

    @Test
    fun `未排期组内按创建序`() {
        val items = listOf(item(3), item(1), item(2))
        assertEquals(listOf(1, 2, 3), items.sortedWith(ganttSiblingOrder).map { it.number })
    }

    @Test
    fun `due-only 有 due 无 start 落未排期组(字面语义防误改)`() {
        val diamond = item(1, null).copy(dueDate = dt(5))
        val late = item(2, dt(9))
        assertEquals(listOf(2, 1), listOf(diamond, late).sortedWith(ganttSiblingOrder).map { it.number })
    }
}
