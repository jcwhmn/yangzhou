package yangzhou.api.project

import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Service
import yangzhou.api.support.NotFoundException
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.TimeEntryRepository
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * V8-S5 Excel 导出:真 xlsx(POI)。sheet1 item 清单(树序),sheet2 工时明细。
 * 工时折算同工时服务:补录取 minutes,计时取起止差(进行中折算到 now)。
 */
@Service
class ExcelExportService(
    private val projects: ProjectRepository,
    private val items: ItemRepository,
    private val members: MemberRepository,
    private val timeEntries: TimeEntryRepository,
    private val projectService: ProjectService,
) {

    private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    fun export(key: String): ExportFile {
        val project = projects.findByKey(key) ?: throw NotFoundException("项目不存在:$key")
        val projectId = project.id ?: error("no id")
        val gantt = projectService.gantt(key)
        val allItems = items.findByProjectIdAndDeletedAtIsNullOrderByNumber(projectId)
        val itemByObjectId = allItems.associateBy { it.objectId }
        val memberMap = members.findByWorkspaceId(project.workspaceId).associateBy { it.objectId }

        val entries = timeEntries.findByItemIdIn(allItems.mapNotNull { it.id })
        val entryItemMap = allItems.associateBy { it.id }
        val entryMemberMap = members.findAllById(entries.map { it.memberId }.toSet()).associateBy { it.id }
        val minutesByItemId = entries.groupBy { it.itemId }
            .mapValues { (_, list) -> list.sumOf { e -> minutesOf(e) } }

        XSSFWorkbook().use { wb ->
            // sheet1:item 清单(树序)
            val s1 = wb.createSheet("项目 item 清单")
            val h1 = s1.createRow(0)
            listOf("编号", "标题", "类型", "状态", "负责人", "开始日期", "截止日期", "工时合计(分钟)", "超期")
                .forEachIndexed { c, title -> h1.createCell(c).setCellValue(title) }
            gantt.rows.forEachIndexed { i, row ->
                val item = itemByObjectId[row.itemId]
                val r: Row = s1.createRow(i + 1)
                r.createCell(0).setCellValue(row.number)
                r.createCell(1).setCellValue(row.title)
                r.createCell(2).setCellValue(item?.type ?: "")
                r.createCell(3).setCellValue(row.statusName)
                r.createCell(4).setCellValue(item?.assigneeObjectId?.let { memberMap[it]?.displayName } ?: "")
                r.createCell(5).setCellValue(row.startDate ?: "")
                r.createCell(6).setCellValue(row.dueDate ?: "")
                r.createCell(7).setCellValue((item?.id?.let { minutesByItemId[it] } ?: 0L).toDouble())
                r.createCell(8).setCellValue(if (row.overdue) "是" else "否")
            }

            // sheet2:工时明细
            val s2 = wb.createSheet("工时明细")
            val h2 = s2.createRow(0)
            listOf("成员", "item", "开始时间", "结束时间", "分钟", "备注")
                .forEachIndexed { c, title -> h2.createCell(c).setCellValue(title) }
            entries.sortedByDescending { it.startedAt }.forEachIndexed { i, e ->
                val r: Row = s2.createRow(i + 1)
                r.createCell(0).setCellValue(entryMemberMap[e.memberId]?.displayName ?: "?")
                r.createCell(1).setCellValue(entryItemMap[e.itemId]?.let { "${project.key}-${it.number}" } ?: "?")
                r.createCell(2).setCellValue(timeFmt.format(e.startedAt))
                r.createCell(3).setCellValue(e.endedAt?.let { timeFmt.format(it) } ?: "(计时中)")
                r.createCell(4).setCellValue(minutesOf(e).toDouble())
                r.createCell(5).setCellValue(e.note ?: "")
            }

            val out = java.io.ByteArrayOutputStream()
            wb.write(out)
            return ExportFile("${project.key}-export.xlsx", out.toByteArray())
        }
    }

    private fun minutesOf(e: yangzhou.persistence.TimeEntry): Long =
        e.minutes?.toLong() ?: Duration.between(e.startedAt, e.endedAt ?: Instant.now()).toMinutes()

    data class ExportFile(val filename: String, val bytes: ByteArray)
}
