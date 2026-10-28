package yangzhou.api.business.project

import java.util.UUID
import yangzhou.api.business.github.VcsEvent
import yangzhou.persistence.Status
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** YPJ-40 方案 A(spec 0057):默认规则语义推导纯函数直测——标准 V7 五列 = 旧按名映射快照;非标 workflow 开箱有定义。 */
class DefaultRuleSeedsUnitTest {

    private fun col(name: String, position: Int, isStart: Boolean = false, isFinal: Boolean = false) =
        Status(projectObjectId = UUID(0, 0), name = name, position = position, isStart = isStart, isFinal = isFinal)

    @Test
    fun `标准 V7 五列 - 与旧按名映射一致`() {
        val saved = listOf(
            col("To Do", 0, isStart = true),
            col("In Progress", 1),
            col("In Review", 2),
            col("QA", 3),
            col("Done", 4, isFinal = true),
        )
        val seeds = ProjectService.defaultRuleSeeds(saved)
        assertEquals(
            listOf("branch_created", "pr_opened", "pr_merged"),
            seeds.map { it.first.wire },
        )
        assertEquals(listOf("In Progress", "In Review", "Done"), seeds.map { it.second.name })
        assertTrue(seeds.none { it.first == VcsEvent.PR_CLOSED_UNMERGED })
    }

    @Test
    fun `非标四列 - 按顺序推导`() {
        val saved = listOf(
            col("To Do", 0, isStart = true),
            col("Doing", 1),
            col("Review", 2),
            col("Done", 3, isFinal = true),
        )
        val seeds = ProjectService.defaultRuleSeeds(saved)
        assertEquals(listOf("branch_created", "pr_opened", "pr_merged"), seeds.map { it.first.wire })
        assertEquals(listOf("Doing", "Review", "Done"), seeds.map { it.second.name })
    }

    @Test
    fun `极简三列 - pr_open 落到次个非 start(final 不豁免)`() {
        val saved = listOf(col("To Do", 0, isStart = true), col("Doing", 1), col("Done", 2, isFinal = true))
        val seeds = ProjectService.defaultRuleSeeds(saved)
        assertEquals(listOf("branch_created", "pr_opened", "pr_merged"), seeds.map { it.first.wire })
        assertEquals(listOf("Doing", "Done", "Done"), seeds.map { it.second.name })
    }

    @Test
    fun `无 final 列 - pr_merged 不种`() {
        val saved = listOf(col("To Do", 0, isStart = true), col("Doing", 1))
        val seeds = ProjectService.defaultRuleSeeds(saved)
        assertEquals(listOf("branch_created" to "Doing"), seeds.map { it.first.wire to it.second.name })
    }

    @Test
    fun `单列 start 非 final - 全不种`() {
        val saved = listOf(col("Archive", 0, isStart = true))
        assertTrue(ProjectService.defaultRuleSeeds(saved).isEmpty())
    }
}
