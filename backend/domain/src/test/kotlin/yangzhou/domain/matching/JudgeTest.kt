package yangzhou.domain.matching

import yangzhou.domain.Attribute
import yangzhou.domain.Capability
import yangzhou.domain.Requirement
import kotlin.test.Test
import kotlin.test.assertEquals

class JudgeTest {

    @Test
    fun `presence requirement is satisfied when member has the capability`() {
        val req = Requirement(Attribute("Postgres"), minLevel = null)
        val caps = listOf(Capability(Attribute("Postgres"), level = null))
        assertEquals(Verdict.Satisfied(Attribute("Postgres")), MatchingEngine.judge(req, caps))
    }

    @Test
    fun `requirement with no matching capability is missing`() {
        val req = Requirement(Attribute("UI 设计"), minLevel = 3)
        assertEquals(Verdict.Missing(Attribute("UI 设计")), MatchingEngine.judge(req, emptyList()))
    }

    // ---- 以下期望值来自真实 fixture JCW-30(需 Java≥3/架构≥2;我 Java4/架构1)----

    @Test
    fun `leveled requirement met exactly is satisfied`() {
        val req = Requirement(Attribute("架构"), minLevel = 2)
        val caps = listOf(Capability(Attribute("架构"), level = 2))
        assertEquals(Verdict.Satisfied(Attribute("架构")), MatchingEngine.judge(req, caps))
    }

    @Test
    fun `capability above min-level is surplus`() {
        val req = Requirement(Attribute("Java"), minLevel = 3)
        val caps = listOf(Capability(Attribute("Java"), level = 4))
        assertEquals(Verdict.Surplus(Attribute("Java")), MatchingEngine.judge(req, caps))
    }

    @Test
    fun `unrated capability against leveled requirement is unknown not gap`() {
        val req = Requirement(Attribute("React"), minLevel = 2)
        val caps = listOf(Capability(Attribute("React"), level = null))
        assertEquals(Verdict.Unrated(Attribute("React"), required = 2), MatchingEngine.judge(req, caps))
    }

    @Test
    fun `capability below min-level is a gap of the difference`() {
        val req = Requirement(Attribute("架构"), minLevel = 2)
        val caps = listOf(Capability(Attribute("架构"), level = 1))
        assertEquals(Verdict.Gap(Attribute("架构"), delta = 1, required = 2, actual = 1), MatchingEngine.judge(req, caps))
    }
}
