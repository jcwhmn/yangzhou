package yangzhou.api.business.release

import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class CreateReleaseRequest(
    @field:NotBlank val name: String?,
    /** 归组维度,可空(如 "1.0");非实体,纯字段。 */
    val version: String? = null,
    /** 成熟度标签,封闭枚举可空;与 status 正交(stage=多成熟,status=发了没有)。 */
    val stage: String? = null,
    val targetDate: String? = null,
    /** YPJ-202(spec 0135):US-R5 事后补建——"released" 直接落终态;缺省/"planned" 同现状。 */
    val status: String? = null,
    /** 仅 status=released 生效;缺省 = 今天(Clock);未来日 400(补建是历史)。 */
    val releasedDate: String? = null,
)

data class UpdateReleaseRequest(
    val name: String? = null,
    /** null = 不动,空串 = 清空(同 milestone 语义)。 */
    val version: String? = null,
    val stage: String? = null,
    val status: String? = null,
    val targetDate: String? = null,
    /** 仅 status=released 迁移时生效;缺省 = 今天(Clock)。 */
    val releasedDate: String? = null,
)

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
        @RequestBody request: CreateReleaseRequest,
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

    /** item ↔ release 挂接(US-R3,spec 0133):{releaseId|null};返回当前归属真相。 */
    @GetMapping("/items/{itemId}/release-membership")
    fun getMembership(
        @PathVariable itemId: UUID,
    ) = service.getMembership(itemId)

    @PutMapping("/items/{itemId}/release-membership")
    fun setMembership(
        @PathVariable itemId: UUID,
        @RequestBody request: SetReleaseMembershipRequest,
    ) = service.setMembership(itemId, request)

    /** YPJ-202(spec 0135):US-R6 挂接变更留痕(倒序)。 */
    @GetMapping("/projects/{key}/releases/{releaseId}/history")
    fun history(
        @PathVariable key: String,
        @PathVariable releaseId: UUID,
    ) = service.history(key, releaseId)
}
