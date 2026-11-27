package yangzhou.api.business.member

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import yangzhou.api.business.capability.CapabilityService
import yangzhou.api.business.capability.UpsertCapabilityRequest
import yangzhou.api.guard.AdminGuard
import java.util.UUID

@RestController
@RequestMapping("/api")
class MemberController(
    private val memberService: MemberService,
    private val capabilityService: CapabilityService,
    private val adminGuard: AdminGuard,
) {
    @GetMapping("/members")
    fun list(): List<MemberService.MemberResponse> = memberService.list()

    @PostMapping("/members")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody request: CreateMemberRequest,
    ): MemberService.MemberResponse {
        // spec 0177(S1):建虚拟成员 = 全局治理(矩阵行 30)→ 收权 admin only
        adminGuard.requireAdmin()
        return memberService.createVirtual(request.displayName.trim())
    }

    @DeleteMapping("/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable memberId: UUID,
    ) {
        // spec 0177(S1):删号 = 全局治理(矩阵行 28)→ 收权 admin only(非终态 assignee/引用计数守卫住 service,S2 平移)
        adminGuard.requireAdmin()
        memberService.delete(memberId)
    }

    /** spec 0177(S1)§4.4:自助改密(登录者本人);成功踢所有会话含当前,需重登。 */
    @PutMapping("/members/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changeMyPassword(
        @Valid @RequestBody request: ChangeMyPasswordRequest,
    ) = memberService.changeMyPassword(request.oldPassword, request.newPassword)

    @PutMapping("/members/me/last-project")
    fun setMyLastProject(
        @RequestBody request: SetLastProjectRequest,
    ): MemberService.MemberResponse = memberService.setLastProjectKey(memberService.current().objectId, request.key)

    @PutMapping("/members/{memberId}/last-project")
    fun setLastProject(
        @PathVariable memberId: UUID,
        @RequestBody request: SetLastProjectRequest,
    ): MemberService.MemberResponse = memberService.setLastProjectKey(memberId, request.key)

    @PutMapping("/members/{memberId}/github-username")
    fun setGithubUsername(
        @PathVariable memberId: UUID,
        @Valid @RequestBody request: SetGithubUsernameRequest,
    ): MemberService.MemberResponse = memberService.setGithubUsername(memberId, request.githubUsername)

    // ---------- 成员维度能力(虚拟成员/我 通用) ----------

    @GetMapping("/members/{memberId}/capabilities")
    fun listCapabilities(
        @PathVariable memberId: UUID,
    ): List<CapabilityService.CapabilityDto> = capabilityService.listFor(memberService.requireMember(memberId))

    @PutMapping("/members/{memberId}/capabilities")
    fun upsertCapability(
        @PathVariable memberId: UUID,
        @Valid @RequestBody request: UpsertCapabilityRequest,
    ): CapabilityService.CapabilityDto = capabilityService.upsertFor(memberService.requireMember(memberId), request.attribute.trim(), request.level)

    @DeleteMapping("/members/{memberId}/capabilities/{attribute}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteCapability(
        @PathVariable memberId: UUID,
        @PathVariable attribute: String,
    ) = capabilityService.deleteFor(memberService.requireMember(memberId), attribute)
}

data class CreateMemberRequest(
    @field:NotBlank val displayName: String,
)

data class SetLastProjectRequest(
    val key: String?,
)

data class SetGithubUsernameRequest(
    val githubUsername: String?,
)

/** 自助改密(§4.4):≥8 位管创建/重置;登录不校验强度(YPJ-68 兼容)。 */
data class ChangeMyPasswordRequest(
    @field:NotBlank val oldPassword: String,
    @field:NotBlank
    @field:Size(min = 8, message = "新密码至少 8 位")
    val newPassword: String,
)
