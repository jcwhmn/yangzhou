package yangzhou.api.feasibility

import org.springframework.stereotype.Service
import yangzhou.domain.matching.Candidate
import yangzhou.domain.matching.MatchResult
import yangzhou.domain.matching.MatchingEngine
import yangzhou.domain.matching.RollupResult
import yangzhou.domain.matching.Verdict
import yangzhou.api.member.MemberService
import yangzhou.api.support.NotFoundException
import yangzhou.api.workspace.WorkspaceService
import yangzhou.persistence.repository.AttributeDefinitionRepository
import yangzhou.persistence.repository.CapabilityRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.RequirementRepository
import java.util.UUID

/**
 * 可行性/差距分析(单人 V1):Requirement × Capability → 判定行 + 聚合信号。
 * 引擎是纯函数,这里只做数据装配(DOMAIN.md「输出形态」)。
 */
@Service
class FeasibilityService(
    private val projects: ProjectRepository,
    private val itemRepo: ItemRepository,
    private val requirementRepo: RequirementRepository,
    private val definitions: AttributeDefinitionRepository,
    private val capabilities: CapabilityRepository,
    private val memberService: MemberService,
    private val workspaceService: WorkspaceService,
) {

    data class VerdictDto(
        val kind: String, // satisfied | surplus | gap | unrated | missing
        val attribute: String,
        val delta: Int? = null,
        val required: Int? = null,
        val actual: Int? = null,
    )

    data class ItemResultDto(
        val itemId: UUID,
        val number: String,
        val title: String,
        val signal: String, // GREEN | YELLOW | RED
        val missingCount: Int,
        val totalDelta: Int,
        val verdicts: List<VerdictDto>,
    )

    data class ProjectResultDto(
        val projectKey: String,
        val signal: String,
        val missingCount: Int,
        val totalDelta: Int,
        val items: List<ItemResultDto>,
    )

    fun project(projectKey: String): ProjectResultDto {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        val member = domainMember()
        val projectItems = itemRepo.findByProjectIdOrderByNumber(project.id!!)
        val domainItems = projectItems.map { it.toDomain() }
        val rollup: RollupResult = MatchingEngine.rollupProject(
            yangzhou.domain.Project(project.key, domainItems),
            member,
        )
        val byNumber = projectItems.associateBy { "${project.key}-${it.number}" }
        return ProjectResultDto(
            projectKey = project.key,
            signal = rollup.worst.name,
            missingCount = rollup.results.sumOf { it.second.missingCount },
            totalDelta = rollup.results.sumOf { it.second.totalDelta },
            items = rollup.results.map { (item, match) ->
                ItemResultDto(
                    itemId = byNumber[item.id]!!.objectId,
                    number = item.id,
                    title = item.title,
                    signal = match.worst.name,
                    missingCount = match.missingCount,
                    totalDelta = match.totalDelta,
                    verdicts = match.verdicts.map { it.toDto() },
                )
            },
        )
    }

    fun item(itemId: UUID): ItemResultDto {
        val item = itemRepo.findByObjectId(itemId) ?: throw NotFoundException("item 不存在")
        val project = projects.findAll().firstOrNull { it.id == item.projectId }
            ?: throw NotFoundException("项目不存在")
        val match: MatchResult = MatchingEngine.matchItem(item.toDomain(), domainMember())
        return ItemResultDto(
            itemId = item.objectId,
            number = "${project.key}-${item.number}",
            title = item.title,
            signal = match.worst.name,
            missingCount = match.missingCount,
            totalDelta = match.totalDelta,
            verdicts = match.verdicts.map { it.toDto() },
        )
    }

    // ---------- 装配 ----------

    private fun domainMember(): yangzhou.domain.Member {
        val me = memberService.current()
        val attrNames = definitions.findByWorkspaceId(workspaceService.required().id!!).associate { it.id!! to it.name }
        val caps = capabilities.findByMemberId(me.id!!).mapNotNull { c ->
            attrNames[c.attributeDefinitionId]?.let { yangzhou.domain.Capability(yangzhou.domain.Attribute(it), c.level) }
        }
        return yangzhou.domain.Member(me.displayName, caps)
    }

    private fun yangzhou.persistence.Item.toDomain(): yangzhou.domain.Item {
        val attrs = definitions.findByWorkspaceId(workspaceService.required().id!!).associate { it.id!! to it.name }
        val reqs = requirementRepo.findByItemId(id!!)
            .mapNotNull { r ->
                attrs[r.attributeDefinitionId]?.let {
                    yangzhou.domain.Requirement(yangzhou.domain.Attribute(it), r.minLevel)
                }
            }
        // domain Item.id 就是「编号」——引擎输出直接可读
        val project = projects.findAll().firstOrNull { p -> p.id == projectId }
        val number = "${project?.key ?: "?"}-$number"
        return yangzhou.domain.Item(number, title, reqs)
    }

    private fun Verdict.toDto() = when (this) {
        is Verdict.Satisfied -> VerdictDto("satisfied", attribute.name)
        is Verdict.Surplus -> VerdictDto("surplus", attribute.name)
        is Verdict.Gap -> VerdictDto("gap", attribute.name, delta, required, actual)
        is Verdict.Unrated -> VerdictDto("unrated", attribute.name, null, required, null)
        is Verdict.Missing -> VerdictDto("missing", attribute.name)
    }
}
