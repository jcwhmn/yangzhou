package yangzhou.api.business.project

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class ProjectController(
    private val service: ProjectService,
    private val excelExportService: ExcelExportService,
    private val feasibilityService: yangzhou.api.business.feasibility.FeasibilityService,
) {
    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody request: CreateProjectRequest,
    ): ProjectService.ProjectDto = service.create(request.key.trim(), request.name.trim())

    @GetMapping("/projects")
    fun list(): List<ProjectService.ProjectDto> = service.list()

    @GetMapping("/projects/{key}")
    fun get(
        @PathVariable key: String,
    ): ProjectService.ProjectDto = service.get(key)

    @GetMapping("/projects/{key}/deletion-preview")
    fun deletionPreview(
        @PathVariable key: String,
    ): ProjectService.DeletionPreviewDto = service.deletionPreview(key)

    /** YPJ-60:物理删除项目及从属数据;防护在 CLI 层(dry-run 默认,--force 才调)。 */
    @DeleteMapping("/projects/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable key: String,
    ) = service.delete(key)

    @GetMapping("/projects/{key}/gantt")
    fun gantt(
        @PathVariable key: String,
    ): ProjectService.GanttDto = service.gantt(key)

    @GetMapping("/projects/{key}/export.xlsx")
    fun exportXlsx(
        @PathVariable key: String,
        response: jakarta.servlet.http.HttpServletResponse,
    ) {
        val file = excelExportService.export(key)
        response.contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        response.setHeader("Content-Disposition", "attachment; filename=\"${file.filename}\"")
        response.outputStream.write(file.bytes)
    }
}
