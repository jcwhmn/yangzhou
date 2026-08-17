package yangzhou.domain

import yangzhou.domain.matching.Candidate
import yangzhou.domain.matching.MatchResult
import yangzhou.domain.matching.MatchingEngine
import yangzhou.domain.matching.Verdict

/**
 * 可运行入口:打印真实 fixture 的判定,肉眼可比对 prototype
 * (分支 prototype/matching-engine-output 的 S1–S7)。
 * 运行:./gradlew :domain:run
 */
fun main() {
    val me = Member(
        "我",
        listOf(Capability("Java", 4), Capability("架构", 1), Capability("前端", 2), Capability("React", 1)),
    )
    val team = listOf(
        Member("小李", listOf(Capability("Java", 3), Capability("架构", 2))),
        Member("小王", listOf(Capability("会计准则", 3), Capability("UI 设计", 2), Capability("React", 3))),
        Member("小张", listOf(Capability("架构", 3), Capability("Java", 2))),
    )
    val chess = Project(
        "chess",
        listOf(
            Item("JCW-30", "Apply architecture decisions to Auth/PlayerConfig baseline",
                listOf(Requirement("Java", 3), Requirement("架构", 2))),
            Item("JCW-44", "Backend: draw offer/accept/reject via WebSocket", listOf(Requirement("Java", 3))),
            Item("JCW-46", "Frontend: wire real backend calls + update tests",
                listOf(Requirement("前端", 1), Requirement("React", 2))),
        ),
    )
    val bookkeeping = Project(
        "bookkeeping",
        listOf(
            Item("BOO-1", "复式记账核心模块", listOf(Requirement("会计准则", 2), Requirement("Java", 2))),
            Item("BOO-UI", "UI 设计", listOf(Requirement("UI 设计", 3))),
        ),
    )

    for (project in listOf(chess, bookkeeping)) {
        val rollup = MatchingEngine.rollupProject(project, me)
        println("== ${project.name} → ${rollup.worst}")
        rollup.results.forEach { (item, r) -> printItem(item, r) }
    }

    println("== 团队建议:谁接 JCW-30")
    MatchingEngine.rankCandidates(chess.items[0], team + me).forEachIndexed { i, c ->
        val (name, m) = c.member.name to c.match
        println("  #${i + 1} $name  缺门${m.missingCount} 差${m.totalDelta}级 [${m.worst}]")
    }
}

private fun printItem(item: Item, r: MatchResult) {
    println("  ${item.id} ${item.title} → ${r.worst} (缺门${r.missingCount} · 总差距${r.totalDelta}级)")
    r.verdicts.forEach { v -> println("    ${describe(v)}") }
}

private fun describe(v: Verdict): String = when (v) {
    is Verdict.Satisfied -> "✓ 满足(${v.attribute})"
    is Verdict.Surplus -> "✓ 有余(${v.attribute})"
    is Verdict.Gap -> "△ 差 ${v.delta} 级(${v.attribute}:需≥${v.required},有 ${v.actual})"
    is Verdict.Unrated -> "△ 有但未评级(${v.attribute},需≥${v.required})——差距未知"
    is Verdict.Missing -> "✗ 缺能力(${v.attribute})"
}
