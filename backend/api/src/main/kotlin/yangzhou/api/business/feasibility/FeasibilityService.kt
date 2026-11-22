package yangzhou.api.business.feasibility

import org.springframework.stereotype.Service
import yangzhou.api.business.member.MemberService
import yangzhou.api.business.workspace.WorkspaceService
import yangzhou.api.error.NotFoundException
import yangzhou.domain.matching.MatchResult
import yangzhou.domain.matching.MatchingEngine
import yangzhou.domain.matching.RollupResult
import yangzhou.domain.matching.Verdict
import yangzhou.persistence.StatusType
import yangzhou.persistence.repository.AttributeDefinitionRepository
import yangzhou.persistence.repository.CapabilityRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.RequirementRepository
import yangzhou.persistence.repository.StatusRepository
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
    private val statusRepo: StatusRepository, // YPJ-242:FINAL 判定
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
        val projectItems = itemRepo.findByProjectObjectIdAndDeletedAtIsNullOrderByNumber(project.objectId!!)
        val domainItems = projectItems.map { itemToDomain(it) }
        val rollup: RollupResult =
            MatchingEngine.rollupProject(
                yangzhou.domain.Project(project.key, domainItems),
                member,
            )
        val byNumber = projectItems.associateBy { "${project.key}-${it.number}" }
        return ProjectResultDto(
            projectKey = project.key,
            signal = rollup.worst.name,
            missingCount = rollup.results.sumOf { it.second.missingCount },
            totalDelta = rollup.results.sumOf { it.second.totalDelta },
            items =
            rollup.results.map { (item, match) ->
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
        val project =
            projects.findAll().firstOrNull { it.objectId == item.projectObjectId }
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

    /** 重算项目:全部 item 冗余 + 聚合到 project(RED>YELLOW>GREEN;无 item = null)。
     * YPJ-242(spec 0162):批量读(ctx 装配/需求一次取)→ 引擎纯内存 → 只 save 变化行;
     * FINAL item 不重算、不进 rollup(冻结 = 转 FINAL 前最后一次重算;全 FINAL → null)。 */
    fun recomputeProjectSignal(projectObjectId: UUID): String? = recomputeProjectSignal(projectObjectId, loadCtx())

    /** 批量装配:definitions + 当前成员 capabilities 一次读,跨项目/跨 item 复用。 */
    private fun loadCtx(): BatchCtx {
        val attrs = definitions.findByWorkspaceObjectId(workspaceService.required().objectId!!).associate { it.objectId!! to it.name }
        val caps =
            capabilities.findByMemberObjectId(memberService.current().objectId!!).mapNotNull { c ->
                attrs[c.attributeDefinitionObjectId]?.let { yangzhou.domain.Capability(yangzhou.domain.Attribute(it), c.level) }
            }
        return BatchCtx(yangzhou.domain.Member(memberService.current().displayName, caps), attrs)
    }

    private data class BatchCtx(
        val member: yangzhou.domain.Member,
        val attrs: Map<UUID, String>,
    )

    private fun recomputeProjectSignal(projectObjectId: UUID, ctx: BatchCtx): String? {
        projects.lockByObjectId(projectObjectId) // YPJ-128 统一锁序:入口先拿 project 行锁(对已持锁事务 no-op;spec 0096)
        val project = projects.findByObjectId(projectObjectId) ?: return null
        val projectItems = itemRepo.findByProjectObjectIdAndDeletedAtIsNullOrderByNumber(projectObjectId)
        val statusById = statusRepo.findByProjectObjectIdOrderByPosition(projectObjectId).associateBy { it.objectId }
        val finalIds = statusById.values.filter { it.statusType == StatusType.FINAL }.mapNotNull { it.objectId }.toSet()
        val reqsByItem =
            requirementRepo.findByItemObjectIdIn(projectItems.mapNotNull { it.objectId })
                .groupBy { it.itemObjectId }
                .mapValues { (_, rs) ->
                    rs.mapNotNull { r -> ctx.attrs[r.attributeDefinitionObjectId]?.let { yangzhou.domain.Requirement(yangzhou.domain.Attribute(it), r.minLevel) } }
                }
        var worst: String? = null
        val changed = mutableListOf<yangzhou.persistence.Item>()
        for (item in projectItems) {
            if (item.statusObjectId in finalIds) continue // FINAL 冻结(YPJ-242)
            val domain = yangzhou.domain.Item(
                "${project.key}-${item.number}",
                item.title,
                reqsByItem[item.objectId].orEmpty(),
            )
            val signal = MatchingEngine.matchItem(domain, ctx.member).worst.name
            if (item.feasSignal != signal) changed += item.copy(feasSignal = signal)
            worst = if (worst == null || rank.getValue(signal) > rank.getValue(worst)) signal else worst
        }
        changed.sortedBy { it.number }.forEach { itemRepo.save(it) } // 编号序锁序(YPJ-26)
        if (project.feasSignal != worst) projects.save(project.copy(feasSignal = worst))
        return worst
    }

    /** 兜底重算(V9-Q1):按 key,返回聚合 signal。 */
    fun recomputeByKey(key: String): String? {
        val project = projects.findByKey(key) ?: throw yangzhou.api.error.NotFoundException("项目不存在:\$key")
        return recomputeProjectSignal(project.objectId!!)
    }

    /** capability 变更影响全 workspace:重算所有项目(V9-Q2)。
     * YPJ-242:ctx 装配一次跨项目复用;锁序不变(project id 升序)。 */
    fun recomputeWorkspace() {
        val ctx = loadCtx()
        // YPJ-10:按 id 升序锁定 project 行——并发重算各自按同一全序 UPDATE,消除锁序环
        // (此前 findAll 无序,行锁获取顺序不定 → PostgreSQL 40P01 deadlock → 500,日志实锤)
        projects.findAll().sortedBy { it.objectId }.forEach { recomputeProjectSignal(it.objectId!!, ctx) }
    }

    /** V9-S1 短板聚合(服务端算,替代首页 N 次调用+客户端聚合)。 */
    fun workspaceShortfall(): List<ShortfallDto> {
        val byAttr = LinkedHashMap<String, MutableList<Pair<ProjectResultDto, ItemResultDto>>>()
        projects.findAll().map { it.key }.forEach { key ->
            runCatching { project(key) }.getOrNull()?.let { proj ->
                proj.items.forEach { item ->
                    item.verdicts
                        .filter { it.kind == "gap" || it.kind == "unrated" || it.kind == "missing" }
                        .forEach { v -> byAttr.getOrPut(v.attribute) { mutableListOf() }.add(proj to item) }
                }
            }
        }
        return byAttr
            .map { (attr, list) ->
                ShortfallDto(
                    attribute = attr,
                    deltaSum = list.sumOf { (p, i) -> 0 + i.verdicts.filter { v -> v.attribute == attr }.sumOf { it.delta ?: 0 } },
                    missingCount = list.count { (p, i) -> i.verdicts.any { it.attribute == attr && it.kind == "missing" } },
                    unratedCount = list.count { (p, i) -> i.verdicts.any { it.attribute == attr && it.kind == "unrated" } },
                    items = list.map { (p, i) -> ShortfallItemDto(p.projectKey, i.itemId, i.number, i.title) }.distinctBy { it.itemId },
                )
            }.sortedByDescending { it.missingCount * 100 + it.deltaSum }
    }

    data class ShortfallItemDto(
        val projectKey: String,
        val itemId: UUID,
        val number: String,
        val title: String,
    )

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
        val attrNames = definitions.findByWorkspaceObjectId(workspaceService.required().objectId!!).associate { it.objectId!! to it.name }
        val caps =
            capabilities.findByMemberObjectId(me.objectId!!).mapNotNull { c ->
                attrNames[c.attributeDefinitionObjectId]?.let { yangzhou.domain.Capability(yangzhou.domain.Attribute(it), c.level) }
            }
        return yangzhou.domain.Member(me.displayName, caps)
    }

    fun itemToDomain(item: yangzhou.persistence.Item): yangzhou.domain.Item {
        val attrs = definitions.findByWorkspaceObjectId(workspaceService.required().objectId!!).associate { it.objectId!! to it.name }
        val reqs =
            requirementRepo
                .findByItemObjectId(item.objectId!!)
                .mapNotNull { r ->
                    attrs[r.attributeDefinitionObjectId]?.let {
                        yangzhou.domain.Requirement(yangzhou.domain.Attribute(it), r.minLevel)
                    }
                }
        // domain Item.objectId 就是「编号」——引擎输出直接可读
        val project = projects.findAll().firstOrNull { p -> p.objectId == item.projectObjectId }
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
