package yangzhou.api.project

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import yangzhou.api.AbstractApiTest
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JCW-124:V8-S5 Excel 导出黑盒(两 sheet/表头/数据/空项目/404)。 */
class ExcelExportApiTest : AbstractApiTest() {

    @Test
    fun `导出 xlsx——两 sheet 数据正确`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        val item = createItem(authed, "CHE", "带日期任务")
        authed.patch().uri("/api/items/${item["itemId"].asText()}")
            .body(mapOf("startDate" to "2026-09-01", "dueDate" to "2026-09-30"))
            .exchange().expectStatus().isOk()
        authed.post().uri("/api/items/${item["itemId"].asText()}/time-entries")
            .body(mapOf("minutes" to 90, "note" to "补录"))
            .exchange().expectStatus().isCreated()

        val result = authed.get().uri("/api/projects/CHE/export.xlsx").exchange()
            .expectStatus().isOk()
            .expectHeader().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            .expectBody(ByteArray::class.java).returnResult()
        val bytes = result.responseBody!!
        assertTrue(bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) // zip 魔数

        XSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
            assertEquals(2, wb.numberOfSheets)
            assertEquals("项目 item 清单", wb.getSheetAt(0).sheetName)
            val s1 = wb.getSheetAt(0)
            assertEquals("编号", s1.getRow(0).getCell(0).stringCellValue)
            assertEquals("CHE-1", s1.getRow(1).getCell(0).stringCellValue)
            assertEquals("带日期任务", s1.getRow(1).getCell(1).stringCellValue)
            assertEquals(90.0, s1.getRow(1).getCell(7).numericCellValue)

            assertEquals("工时明细", wb.getSheetAt(1).sheetName)
            val s2 = wb.getSheetAt(1)
            assertEquals("me", s2.getRow(1).getCell(0).stringCellValue)
            assertEquals(90.0, s2.getRow(1).getCell(4).numericCellValue)
            assertEquals("补录", s2.getRow(1).getCell(5).stringCellValue)
        }
    }

    @Test
    fun `空项目只有表头——未知项目 404`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")

        val bytes = authed.get().uri("/api/projects/CHE/export.xlsx").exchange()
            .expectStatus().isOk()
            .expectBody(ByteArray::class.java).returnResult().responseBody!!
        XSSFWorkbook(ByteArrayInputStream(bytes)).use { wb ->
            assertEquals(0, wb.getSheetAt(0).lastRowNum) // 只有表头行(0-indexed)
        }

        authed.get().uri("/api/projects/NOPE/export.xlsx")
            .exchange().expectStatus().isNotFound()
    }
}
