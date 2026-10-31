package yangzhou.api.business.release

import jakarta.validation.Valid
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
import java.util.UUID

/**
 * V15 Release:交付集合(PRD §7.3/§8,spec 0012)。ItemGroup type='release'(§27 共享抽象)。
 * 生命周期 planned→released 单向(released 终态,历史保留);支持事后补建 = 创建即 released(§8.2)。
 * milestone_id 可空 N:1(§26.3 裁决);membership 不受状态限制(历史 release 可补录 items)。
 */
@RestController
@RequestMapping("/api")
class ReleaseController(
    private val service: ReleaseService,
) {
    @GetMapping("/projects/{key}/releases")
    fun list(
        @PathVariable key: String,
    ): List<ReleaseService.ReleaseDto> = service.list(key)

    @PostMapping("/projects/{key}/releases")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable key: String,
        @Valid @RequestBody request: CreateReleaseRequest,
    ): ReleaseService.ReleaseDto = service.create(key, request)

    @PatchMapping("/projects/{key}/releases/{releaseId}")
    fun update(
        @PathVariable key: String,
        @PathVariable releaseId: UUID,
        @RequestBody request: UpdateReleaseRequest,
    ): ReleaseService.ReleaseDto = service.update(key, releaseId, request)

    @DeleteMapping("/projects/{key}/releases/{releaseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable key: String,
        @PathVariable releaseId: UUID,
    ) = service.delete(key, releaseId)
}
