package yangzhou.api.business.item

import yangzhou.api.error.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-130(spec 0093):分页排序解析 / ILIKE 转义纯函数单测。 */
class ItemPageSortTest {
    @Test
    fun `默认与合法解析`() {
        assertEquals(listOf("number" to "asc", "" to "", "" to ""), parseItemSort(null))
        assertEquals(listOf("number" to "asc", "" to "", "" to ""), parseItemSort(""))
        assertEquals(listOf("number" to "asc", "" to "", "" to ""), parseItemSort("   "))
        assertEquals(
            listOf("title" to "desc", "status" to "asc", "" to ""),
            parseItemSort("title:desc,status:asc"),
        )
        assertEquals(listOf("overdue" to "asc", "" to "", "" to ""), parseItemSort("overdue:asc"))
    }

    @Test
    fun `非法字段与方向 fail-fast`() {
        assertFailsWith<BadRequestException> { parseItemSort("hacker:asc") }
        assertFailsWith<BadRequestException> { parseItemSort("title:up") }
        assertFailsWith<BadRequestException> { parseItemSort("title") }
        assertFailsWith<BadRequestException> { parseItemSort("title:asc;DROP TABLE item") }
    }

    @Test
    fun `恒返 3 槽且超 3 键截断`() {
        assertEquals(3, parseItemSort("title:asc,status:desc,priority:asc,dueDate:desc").size)
        assertEquals("priority" to "asc", parseItemSort("title:asc,status:desc,priority:asc,dueDate:desc")[2])
        assertEquals(3, parseItemSort("title:asc").size)
    }

    @Test
    fun `页大小白名单`() {
        assertEquals(setOf(20, 50, 100), ITEM_PAGE_SIZES)
    }

    @Test
    fun `ILIKE 转义对齐字面子串语义`() {
        assertEquals("a\\%b", escapeLike("a%b"))
        assertEquals("a\\_b", escapeLike("a_b"))
        assertEquals("a\\\\b", escapeLike("a\\b"))
        assertEquals("普通-文字 123", escapeLike("普通-文字 123"))
    }
}
