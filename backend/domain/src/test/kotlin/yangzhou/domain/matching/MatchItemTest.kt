package yangzhou.domain.matching

import yangzhou.domain.Attribute
import yangzhou.domain.Capability
import yangzhou.domain.Item
import yangzhou.domain.Member
import yangzhou.domain.Requirement
import kotlin.test.Test
import kotlin.test.assertEquals

class MatchItemTest {

    private val me = Member(
        name = "我",
        capabilities = listOf(
            Capability(Attribute("Java"), 4),
            Capability(Attribute("架构"), 1),
        ),
    )

    @Test
    fun `JCW-30 surplus plus gap yields yellow`() {
        val item = Item(
            id = "JCW-30",
            title = "Apply architecture decisions to Auth/PlayerConfig baseline",
            requirements = listOf(Requirement(Attribute("Java"), 3), Requirement(Attribute("架构"), 2)),
        )
        val r = MatchingEngine.matchItem(item, me)
        assertEquals(2, r.verdicts.size)
        assertEquals(0, r.missingCount)
        assertEquals(1, r.totalDelta)
        assertEquals(Signal.YELLOW, r.worst)
    }

    @Test
    fun `JCW-44 surplus only yields green`() {
        val item = Item("JCW-44", "Backend: draw offer/accept/reject via WebSocket", listOf(Requirement(Attribute("Java"), 3)))
        assertEquals(Signal.GREEN, MatchingEngine.matchItem(item, me).worst)
    }

    @Test
    fun `BOO-UI absent capability yields red`() {
        val item = Item("BOO-UI", "UI 设计", listOf(Requirement(Attribute("UI 设计"), 3)))
        val r = MatchingEngine.matchItem(item, me)
        assertEquals(1, r.missingCount)
        assertEquals(Signal.RED, r.worst)
    }

    @Test
    fun `item without requirements is trivially green`() {
        val r = MatchingEngine.matchItem(Item("JCW-1", "空需求"), me)
        assertEquals(Signal.GREEN, r.worst)
        assertEquals(0, r.verdicts.size)
    }
}
