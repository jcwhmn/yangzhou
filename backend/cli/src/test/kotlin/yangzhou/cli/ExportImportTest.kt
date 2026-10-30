package yangzhou.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/** spec 0088:parseCsv(RFC4180 子集)与 esc 的纯转换行为锁定——Linear CSV 导入正确性的地基。 */
class ExportImportTest {

    @Test
    fun `basic two rows map header to values`() {
        val rows = ExportImport.parseCsv("number,title\r\nYPJ-1,alpha\r\nYPJ-2,beta\r\n")
        assertEquals(2, rows.size)
        assertEquals("YPJ-1", rows[0]["number"])
        assertEquals("beta", rows[1]["title"])
    }

    @Test
    fun `quoted field keeps commas`() {
        val rows = ExportImport.parseCsv("a,b\r\n\"x,y\",z\r\n")
        assertEquals("x,y", rows[0]["a"])
        assertEquals("z", rows[0]["b"])
    }

    @Test
    fun `doubled quotes unescape inside quotes`() {
        val rows = ExportImport.parseCsv("a\r\n\"he said \"\"hi\"\"\"\r\n")
        assertEquals("he said \"hi\"", rows[0]["a"])
    }

    @Test
    fun `newline inside quotes stays in field`() {
        val rows = ExportImport.parseCsv("a,b\r\n\"l1\nl2\",c\r\n")
        assertEquals("l1\nl2", rows[0]["a"])
        assertEquals("c", rows[0]["b"])
    }

    @Test
    fun `fully empty line skipped and header trimmed`() {
        val rows = ExportImport.parseCsv(" a , b \r\n\r\n1,2\r\n")
        assertEquals(1, rows.size)
        assertEquals("1", rows[0]["a"])
        assertEquals("2", rows[0]["b"])
    }

    @Test
    fun `short data row pads missing columns`() {
        val rows = ExportImport.parseCsv("a,b,c\r\n1\r\n")
        assertEquals("", rows[0]["b"])
        assertEquals("", rows[0]["c"])
    }

    @Test
    fun `esc leaves plain value untouched`() {
        assertEquals("plain", ExportImport.esc("plain"))
    }

    @Test
    fun `esc wraps value containing comma quote or newline`() {
        assertEquals("\"a,b\"", ExportImport.esc("a,b"))
        assertEquals("\"a\"\"b\"", ExportImport.esc("a\"b"))
        assertEquals("\"a\nb\"", ExportImport.esc("a\nb"))
    }
}
