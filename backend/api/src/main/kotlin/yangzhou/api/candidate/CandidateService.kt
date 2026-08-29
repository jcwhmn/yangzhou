package yangzhou.api.candidate

import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import yangzhou.api.feasibility.FeasibilityService
import yangzhou.api.member.MemberService
import yangzhou.api.support.NotFoundException
import yangzhou.api.workspace.WorkspaceService
import yangzhou.domain.matching.MatchingEngine
import yangzhou.domain.matching.Verdict
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.MemberRepository
import java.util.UUID

/**
 * 候选建议(Q6):rankCandidates 直出——全 workspace 成员池(含我,含虚拟成员),
 * 排序 = 缺门少 → 总差距小,无加权可解释。引擎给建议,人拍板(指派在 item 切片)。
 */
@Service
class CandidateService(
    private val items: ItemRepository,
    private val members: MemberRepository,
    private val memberService: MemberService,
    private val workspaceService: WorkspaceService,
    private val feasibility: FeasibilityService,
) {

    data class VerdictDto(
        val kind: String,
        val attribute: String,
        val delta: Int? = null,
        val required: Int? = null,
        val actual: Int? = null,
    )

    data class CandidateDto(
        val rank: Int,
        val memberId: UUID,
        val displayName: String,
        val virtual: Boolean,
        val signal: String,
        val missingCount: Int,
        val totalDelta: Int,
        val verdicts: List<VerdictDto>,
    )

    fun candidates(itemId: UUID): List<CandidateDto> {
        val item = items.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val domainItem = feasibility.itemToDomain(item)
        val workspaceMembers = members.findByWorkspaceId(workspaceService.required().id!!)
        memberService.current() // 确保已初始化(与可行性端点语义一致)
        // 预计算 持久层↔domain 配对(每成员一次装配;值相等的两成员视为同位,天然一致)
        val domainOf = LinkedHashMap<yangzhou.persistence.Member, yangzhou.domain.Member>()
        workspaceMembers.forEach { domainOf[it] = feasibility.domainMemberOf(it) }
        val ranked = MatchingEngine.rankCandidates(domainItem, domainOf.values.toList())
        return ranked.mapIndexed { index, candidate ->
            val persistMember = domainOf.entries.first { it.value == candidate.member }.key
            CandidateDto(
                rank = index + 1,
                memberId = persistMember.objectId,
                displayName = persistMember.displayName,
                virtual = persistMember.passwordHash == null,
                signal = candidate.match.worst.name,
                missingCount = candidate.match.missingCount,
                totalDelta = candidate.match.totalDelta,
                verdicts = candidate.match.verdicts.map { it.toDto() },
            )
        }
    }

    private fun Verdict.toDto() = when (this) {
        is Verdict.Satisfied -> VerdictDto("satisfied", attribute.name)
        is Verdict.Surplus -> VerdictDto("surplus", attribute.name)
        is Verdict.Gap -> VerdictDto("gap", attribute.name, delta, required, actual)
        is Verdict.Unrated -> VerdictDto("unrated", attribute.name, null, required, null)
        is Verdict.Missing -> VerdictDto("missing", attribute.name)
    }
}

@RestController
@RequestMapping("/api")
class CandidateController(private val service: CandidateService) {

    @GetMapping("/items/{itemId}/candidates")
    fun candidates(@PathVariable itemId: UUID): List<CandidateService.CandidateDto> = service.candidates(itemId)
}
