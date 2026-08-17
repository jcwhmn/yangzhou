package yangzhou.domain.matching

import yangzhou.domain.Attribute
import yangzhou.domain.Capability
import yangzhou.domain.Item
import yangzhou.domain.Member
import yangzhou.domain.Project
import yangzhou.domain.Requirement
import kotlin.test.Test
import kotlin.test.assertEquals

class RollupProjectTest {

    private val me = Member(
        name = "我",
        capabilities = listOf(Capability(Attribute("Java"), 4), Capability(Attribute("架构"), 1)),
    )

    @Test
    fun `rollup takes the worst signal across items`() {
        val project = Project(
            name = "chess",
            items = listOf(
                Item("JCW-44", "draw offer/accept/reject", listOf(Requirement(Attribute("Java"), 3))),      // green
                Item("JCW-30", "auth baseline", listOf(Requirement(Attribute("Java"), 3), Requirement(Attribute("架构"), 2))), // yellow
            ),
        )
        assertEquals(Signal.YELLOW, MatchingEngine.rollupProject(project, me).worst)
    }

    @Test
    fun `any missing capability turns the project red`() {
        val project = Project(
            name = "bookkeeping",
            items = listOf(
                Item("BOO-UI", "UI 设计", listOf(Requirement(Attribute("UI 设计"), 3))),                     // red
                Item("BOO-2", "报表导出", listOf(Requirement(Attribute("Java"), 2))),                        // green
            ),
        )
        val rollup = MatchingEngine.rollupProject(project, me)
        assertEquals(Signal.RED, rollup.worst)
        assertEquals(2, rollup.results.size)
    }

    @Test
    fun `empty project is green`() {
        assertEquals(Signal.GREEN, MatchingEngine.rollupProject(Project("empty"), me).worst)
    }
}
