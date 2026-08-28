package yangzhou.api.member

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
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
import yangzhou.api.capability.CapabilityService
import yangzhou.api.capability.UpsertCapabilityRequest
import java.util.UUID

@RestController
@RequestMapping("/api")
class MemberController(
    private val memberService: MemberService,
    private val capabilityService: CapabilityService,
) {

    @GetMapping("/members")
    fun list(): List<MemberService.MemberResponse> = memberService.list()

    @PostMapping("/members")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateMemberRequest): MemberService.MemberResponse =
        memberService.createVirtual(request.displayName.trim())

    @DeleteMapping("/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable memberId: UUID) = memberService.delete(memberId)

    // ---------- 成员维度能力(虚拟成员/我 通用) ----------

    @GetMapping("/members/{memberId}/capabilities")
    fun listCapabilities(@PathVariable memberId: UUID): List<CapabilityService.CapabilityDto> =
        capabilityService.listFor(memberService.requireMember(memberId))

    @PutMapping("/members/{memberId}/capabilities")
    fun upsertCapability(
        @PathVariable memberId: UUID,
        @Valid @RequestBody request: UpsertCapabilityRequest,
    ): CapabilityService.CapabilityDto =
        capabilityService.upsertFor(memberService.requireMember(memberId), request.attribute.trim(), request.level)

    @DeleteMapping("/members/{memberId}/capabilities/{attribute}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteCapability(@PathVariable memberId: UUID, @PathVariable attribute: String) =
        capabilityService.deleteFor(memberService.requireMember(memberId), attribute)
}

data class CreateMemberRequest(
    @field:NotBlank val displayName: String,
)
