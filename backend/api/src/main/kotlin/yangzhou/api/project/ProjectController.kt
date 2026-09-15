package yangzhou.api.project

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.http.HttpStatus

@RestController
@RequestMapping("/api")
class ProjectController(
    private val service: ProjectService,
    private val excelExportService: ExcelExportService,
    private val feasibilityService: yangzhou.api.feasibility.FeasibilityService,
) {

    @PostMapping("/projects")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateProjectRequest): ProjectService.ProjectDto =
        service.create(request.key.trim(), request.name.trim())

    @GetMapping("/projects")
    fun list(): List<ProjectService.ProjectDto> = service.list()

    @GetMapping("/projects/{key}")
    fun get(@PathVariable key: String): ProjectService.ProjectDto = service.get(key)

    @GetMapping("/projects/{key}/gantt")
    fun gantt(@PathVariable key: String): ProjectService.GanttDto = service.gantt(key)

    @GetMapping("/projects/{key}/export.xlsx")
    fun exportXlsx(@PathVariable key: String, response: jakarta.servlet.http.HttpServletResponse) {
        val file = excelExportService.export(key)
        response.contentType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        response.setHeader("Content-Disposition", "attachment; filename=\"${file.filename}\"")
        response.outputStream.write(file.bytes)
    }
}
