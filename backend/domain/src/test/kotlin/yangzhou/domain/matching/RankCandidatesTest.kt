package yangzhou.domain.matching

import yangzhou.domain.Attribute
import yangzhou.domain.Capability
import yangzhou.domain.Item
import yangzhou.domain.Member
import yangzhou.domain.Requirement
import kotlin.test.Test
import kotlin.test.assertEquals

class RankCandidatesTest {

    private val jcw30 = Item(
        id = "JCW-30",
        title = "auth baseline",
        requirements = listOf(Requirement(Attribute("Java"), 3), Requirement(Attribute("架构"), 2)),
    )

    @Test
    fun `fewer missing first then smaller total delta (S6)`() {
        val 小李 = Member("小李", listOf(Capability(Attribute("Java"), 3), Capability(Attribute("架构"), 2)))   // green, 0/0
        val 小张 = Member("小张", listOf(Capability(Attribute("Java"), 2), Capability(Attribute("架构"), 3)))   // yellow, 0/1
        val me = Member("我", listOf(Capability(Attribute("Java"), 4), Capability(Attribute("架构"), 1)))      // yellow, 0/1

        val ranked = MatchingEngine.rankCandidates(jcw30, listOf(小李, 小张, me))

        assertEquals(listOf("小李", "小张", "我"), ranked.map { it.member.name })
    }

    @Test
    fun `candidate with a gap outranks one missing the skill entirely (S7)`() {
        val me = Member("我", listOf(Capability(Attribute("Java"), 4)))                              // missing UI 设计
        val 小王 = Member("小王", listOf(Capability(Attribute("UI 设计"), 2)))                        // gap 1
        val booUi = Item("BOO-UI", "UI 设计", listOf(Requirement(Attribute("UI 设计"), 3)))

        val ranked = MatchingEngine.rankCandidates(booUi, listOf(me, 小王))

        assertEquals(listOf("小王", "我"), ranked.map { it.member.name })
    }
}
