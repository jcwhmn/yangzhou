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
    private val projectGuard: yangzhou.api.guard.ProjectGuard,
) {
    @GetMapping("/members/me")
    fun me(): MemberService.WhoamiResponse = memberService.whoami()

    /** 矩阵行 3b(⚠️-3):全局成员目录 = admin ∨ editor-anywhere;纯池成员/viewer 403(web 中间态归 S6)。 */
    @GetMapping("/members")
    fun list(): List<MemberService.MemberResponse> {
        projectGuard.requireEditorAnywhere()
        return memberService.list()
    }

    @PostMapping("/members")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody request: CreateMemberRequest,
    ): MemberService.MemberResponse {
        // spec 0177(S1/S2):建号 = 全局治理(矩阵行 24/30)→ admin only
        adminGuard.requireAdmin()
        return memberService.create(request.displayName.trim(), request.username, request.password, request.projectIds)
    }

    /** S2 §4.4:重置凭据——真人重置密码(踢会话),虚拟设凭据即升级。 */
    @PutMapping("/members/{memberId}/credentials")
    fun resetCredentials(
        @PathVariable memberId: UUID,
        @Valid @RequestBody request: ResetCredentialsRequest,
    ): MemberService.MemberResponse {
        adminGuard.requireAdmin()
        return memberService.resetCredentials(memberId, request.username, request.password)
    }

    /** S2 §4.4:禁用/复职(黑名单写语义);最后 admin 不可禁用自己。 */
    @PutMapping("/members/{memberId}/status")
    fun setStatus(
        @PathVariable memberId: UUID,
        @Valid @RequestBody request: SetMemberStatusRequest,
    ): MemberService.MemberResponse {
        adminGuard.requireAdmin()
        return memberService.setStatus(memberId, request.status)
    }

    /** S2 §4.4:授/收 workspace admin;最后 admin 不可收。 */
    @PutMapping("/members/{memberId}/admin")
    fun setAdmin(
        @PathVariable memberId: UUID,
        @Valid @RequestBody request: SetMemberAdminRequest,
    ): MemberService.MemberResponse {
        adminGuard.requireAdmin()
        return memberService.setAdmin(memberId, request.admin)
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

    /** 矩阵行 4/5(C1):本人恒可;他人 = admin ∨ 池交集 editor(requirePoolEditor)。 */
    @PutMapping("/members/{memberId}/github-username")
    fun setGithubUsername(
        @PathVariable memberId: UUID,
        @Valid @RequestBody request: SetGithubUsernameRequest,
    ): MemberService.MemberResponse {
        requireSelfOrPoolEditor(memberId)
        return memberService.setGithubUsername(memberId, request.githubUsername)
    }

    // ---------- 成员维度能力(虚拟成员/我 通用) ----------

    @GetMapping("/members/{memberId}/capabilities")
    fun listCapabilities(
        @PathVariable memberId: UUID,
    ): List<CapabilityService.CapabilityDto> = capabilityService.listFor(memberService.requireMember(memberId))

    @PutMapping("/members/{memberId}/capabilities")
    fun upsertCapability(
        @PathVariable memberId: UUID,
        @Valid @RequestBody request: UpsertCapabilityRequest,
    ): CapabilityService.CapabilityDto {
        requireSelfOrPoolEditor(memberId)
        return capabilityService.upsertFor(memberService.requireMember(memberId), request.attribute.trim(), request.level)
    }

    @DeleteMapping("/members/{memberId}/capabilities/{attribute}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteCapability(
        @PathVariable memberId: UUID,
        @PathVariable attribute: String,
    ) {
        requireSelfOrPoolEditor(memberId)
        capabilityService.deleteFor(memberService.requireMember(memberId), attribute)
    }

    /** 行 4/5:目标==me 放行,否则池交集 editor 门。 */
    private fun requireSelfOrPoolEditor(memberId: UUID) {
        if (memberId != memberService.current().objectId) projectGuard.requirePoolEditor(memberId)
    }
}

data class CreateMemberRequest(
    @field:NotBlank val displayName: String,
    val username: String? = null,
    @field:Size(min = 8, message = "密码至少 8 位")
    val password: String? = null,
    val projectIds: List<UUID>? = null,
)

/** S2 §4.4:重置凭据(真人重置/虚拟升级,均 ≥8 位)。 */
data class ResetCredentialsRequest(
    val username: String? = null,
    @field:NotBlank
    @field:Size(min = 8, message = "密码至少 8 位")
    val password: String,
)

/** S2 §4.4:禁用/复职(active/disabled;请求线收小写,service 归一映射)。 */
data class SetMemberStatusRequest(
    @field:NotBlank val status: String,
)

/** S2 §4.4:授/收 workspace admin。 */
data class SetMemberAdminRequest(
    val admin: Boolean,
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
