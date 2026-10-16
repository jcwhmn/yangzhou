package yangzhou.api.view

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import java.util.UUID

/** YPJ-47 视图配置(spec 0045):表格筛选/列配置的保存复用,用户×项目私有。 */
@RestController
@RequestMapping("/api")
@Tag(name = "views")
class ViewController(private val service: ViewService) {

    @GetMapping("/projects/{key}/views")
    fun list(@PathVariable key: String): List<ViewService.ViewDto> = service.list(key)

    @PostMapping("/projects/{key}/views")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable key: String,
        @Valid @RequestBody request: ViewService.CreateViewRequest,
    ): ViewService.ViewDto = service.create(key, request)

    @PatchMapping("/projects/{key}/views/{viewId}")
    fun update(
        @PathVariable key: String,
        @PathVariable viewId: UUID,
        @RequestBody request: ViewService.UpdateViewRequest,
    ): ViewService.ViewDto = service.update(key, viewId, request)

    @DeleteMapping("/projects/{key}/views/{viewId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable key: String, @PathVariable viewId: UUID) = service.delete(key, viewId)
}
