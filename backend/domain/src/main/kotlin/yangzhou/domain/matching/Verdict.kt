package yangzhou.domain.matching

import yangzhou.domain.Attribute
import yangzhou.domain.Capability
import yangzhou.domain.Item
import yangzhou.domain.Member
import yangzhou.domain.Project
import yangzhou.domain.Requirement

/** 单条 requirement 的判定,形态见 DOMAIN.md「输出形态」表。 */
sealed interface Verdict {
    val attribute: Attribute

    data class Satisfied(override val attribute: Attribute) : Verdict
    data class Surplus(override val attribute: Attribute) : Verdict
    data class Gap(
        override val attribute: Attribute,
        val delta: Int,
        val required: Int,
        val actual: Int,
    ) : Verdict
    data class Unrated(override val attribute: Attribute, val required: Int) : Verdict
    data class Missing(override val attribute: Attribute) : Verdict
}

/** 聚合信号:红=有缺门 > 黄=有差距/未知 > 绿=全满足。 */
enum class Signal { GREEN, YELLOW, RED }

/** 单 item 匹配结果:逐条判定 + 聚合。 */
data class MatchResult(
    val verdicts: List<Verdict>,
    val missingCount: Int,
    val totalDelta: Int,
    val worst: Signal,
)

/** 项目 rollup:逐 item 结果 + 取最差。 */
data class RollupResult(
    val results: List<Pair<Item, MatchResult>>,
    val worst: Signal,
)

/** 团队模式候选:member + 其匹配结果。 */
data class Candidate(val member: Member, val match: MatchResult)

object MatchingEngine {

    fun judge(req: Requirement, caps: List<Capability>): Verdict {
        val cap = caps.firstOrNull { it.attribute == req.attribute }
            ?: return Verdict.Missing(req.attribute)
        val min = req.minLevel ?: return Verdict.Satisfied(req.attribute)
        val level = cap.level
        return when {
            level == null -> Verdict.Unrated(req.attribute, required = min)
            level == min -> Verdict.Satisfied(req.attribute)
            level > min -> Verdict.Surplus(req.attribute)
            else -> Verdict.Gap(req.attribute, delta = min - level, required = min, actual = level)
        }
    }

    /** 单 item 匹配:逐条判定 + 聚合(missing>0→红;有 gap/unrated→黄;否则绿)。 */
    fun matchItem(item: Item, member: Member): MatchResult {
        val verdicts = item.requirements.map { judge(it, member.capabilities) }
        val missing = verdicts.count { it is Verdict.Missing }
        val totalDelta = verdicts.sumOf { (it as? Verdict.Gap)?.delta ?: 0 }
        val worst = when {
            missing > 0 -> Signal.RED
            verdicts.any { it is Verdict.Gap || it is Verdict.Unrated } -> Signal.YELLOW
            else -> Signal.GREEN
        }
        return MatchResult(verdicts, missing, totalDelta, worst)
    }

    /** 项目聚合:逐 item 匹配,取最差信号。 */
    fun rollupProject(project: Project, member: Member): RollupResult {
        val severity = listOf(Signal.GREEN, Signal.YELLOW, Signal.RED)
        val results = project.items.map { it to matchItem(it, member) }
        val worst = results.maxOfOrNull { (_, r) -> severity.indexOf(r.worst) }?.let { severity[it] } ?: Signal.GREEN
        return RollupResult(results, worst)
    }

    /** 团队分配建议:缺门少者优先 → 总差距小者优先;无加权,稳定排序。 */
    fun rankCandidates(item: Item, members: List<Member>): List<Candidate> =
        members.map { Candidate(it, matchItem(item, it)) }
            .sortedWith(compareBy({ it.match.missingCount }, { it.match.totalDelta }))
}
