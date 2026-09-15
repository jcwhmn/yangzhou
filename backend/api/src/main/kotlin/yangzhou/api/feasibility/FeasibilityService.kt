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
        val domainItems = projectItems.map { itemToDomain(it) }
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
        val match: MatchResult = MatchingEngine.matchItem(itemToDomain(item), domainMember())
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

    // ---------- V9-S1 冗余重算(sync 写路径;signal = 当前登录成员视角) ----------

    private val rank = mapOf("GREEN" to 0, "YELLOW" to 1, "RED" to 2)

    /** 重算单 item 冗余并返回 signal。 */
    fun recomputeItemSignal(item: yangzhou.persistence.Item): String {
        val signal = item(item.objectId).signal

        itemRepo.save(item.copy(feasSignal = signal))
        return signal
    }

    /** 重算项目:全部 item 冗余 + 聚合到 project(RED>YELLOW>GREEN;无 item = null)。 */
    fun recomputeProjectSignal(projectId: Long): String? {
        val projectItems = itemRepo.findByProjectIdOrderByNumber(projectId)
        var worst: String? = null
        for (it in projectItems) {
            val s = recomputeItemSignal(it)
            worst = if (worst == null || rank.getValue(s) > rank.getValue(worst)) s else worst
        }
        val project = projects.findById(projectId).orElse(null) ?: return null
        projects.save(project.copy(feasSignal = worst))
        return worst
    }

    /** 兜底重算(V9-Q1):按 key,返回聚合 signal。 */
    fun recomputeByKey(key: String): String? {
        val project = projects.findByKey(key) ?: throw yangzhou.api.support.NotFoundException("项目不存在:\$key")
        return recomputeProjectSignal(project.id!!)
    }

    /** capability 变更影响全 workspace:重算所有项目(V9-Q2)。 */
    fun recomputeWorkspace() {
        projects.findAll().forEach { recomputeProjectSignal(it.id!!) }
    }

    /** V9-S1 短板聚合(服务端算,替代首页 N 次调用+客户端聚合)。 */
    fun workspaceShortfall(): List<ShortfallDto> {
        val byAttr = LinkedHashMap<String, MutableList<Pair<ProjectResultDto, ItemResultDto>>>()
        projects.findAll().map { it.key }.forEach { key ->
            runCatching { project(key) }.getOrNull()?.let { proj ->
                proj.items.forEach { item ->
                    item.verdicts.filter { it.kind == "gap" || it.kind == "unrated" || it.kind == "missing" }
                        .forEach { v -> byAttr.getOrPut(v.attribute) { mutableListOf() }.add(proj to item) }
                }
            }
        }
        return byAttr.map { (attr, list) ->
            ShortfallDto(
                attribute = attr,
                deltaSum = list.sumOf { (p, i) -> 0 + i.verdicts.filter { v -> v.attribute == attr }.sumOf { it.delta ?: 0 } },
                missingCount = list.count { (p, i) -> i.verdicts.any { it.attribute == attr && it.kind == "missing" } },
                unratedCount = list.count { (p, i) -> i.verdicts.any { it.attribute == attr && it.kind == "unrated" } },
                items = list.map { (p, i) -> ShortfallItemDto(p.projectKey, i.itemId, i.number, i.title) }.distinctBy { it.itemId },
            )
        }.sortedByDescending { it.missingCount * 100 + it.deltaSum }
    }

    data class ShortfallItemDto(val projectKey: String, val itemId: UUID, val number: String, val title: String)
    data class ShortfallDto(
        val attribute: String,
        val deltaSum: Int,
        val missingCount: Int,
        val unratedCount: Int,
        val items: List<ShortfallItemDto>,
    )

    // ---------- 装配 ----------

    fun domainMember(): yangzhou.domain.Member = domainMemberOf(memberService.current())

    /** 装配:任一持久层成员 → 引擎输入(候选服务复用)。 */
    fun domainMemberOf(me: yangzhou.persistence.Member): yangzhou.domain.Member {
        val attrNames = definitions.findByWorkspaceId(workspaceService.required().id!!).associate { it.id!! to it.name }
        val caps = capabilities.findByMemberId(me.id!!).mapNotNull { c ->
            attrNames[c.attributeDefinitionId]?.let { yangzhou.domain.Capability(yangzhou.domain.Attribute(it), c.level) }
        }
        return yangzhou.domain.Member(me.displayName, caps)
    }

    fun itemToDomain(item: yangzhou.persistence.Item): yangzhou.domain.Item {
        val attrs = definitions.findByWorkspaceId(workspaceService.required().id!!).associate { it.id!! to it.name }
        val reqs = requirementRepo.findByItemId(item.id!!)
            .mapNotNull { r ->
                attrs[r.attributeDefinitionId]?.let {
                    yangzhou.domain.Requirement(yangzhou.domain.Attribute(it), r.minLevel)
                }
            }
        // domain Item.id 就是「编号」——引擎输出直接可读
        val project = projects.findAll().firstOrNull { p -> p.id == item.projectId }
        val number = "${project?.key ?: "?"}-${item.number}"
        return yangzhou.domain.Item(number, item.title, reqs)
    }

    private fun Verdict.toDto() = when (this) {
        is Verdict.Satisfied -> VerdictDto("satisfied", attribute.name)
        is Verdict.Surplus -> VerdictDto("surplus", attribute.name)
        is Verdict.Gap -> VerdictDto("gap", attribute.name, delta, required, actual)
        is Verdict.Unrated -> VerdictDto("unrated", attribute.name, null, required, null)
        is Verdict.Missing -> VerdictDto("missing", attribute.name)
    }
}
