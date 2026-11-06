package yangzhou.api.business.itemgroup

import io.mockk.every
import io.mockk.mockk
import tools.jackson.databind.ObjectMapper
import yangzhou.persistence.GroupStatus
import yangzhou.persistence.ItemGroupType
import yangzhou.persistence.StatusType
import yangzhou.persistence.repository.GroupStatusRepository
import yangzhou.persistence.repository.ItemGroupTypeRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** spec 0097 S2 单测:GroupWorkflowService(种子模板/START 态/FINAL 名集/动作白名单 409)。 */
class GroupWorkflowServiceUnitTest {
    private val types = mockk<ItemGroupTypeRepository>(relaxed = true)
    private val groupStatuses = mockk<GroupStatusRepository>(relaxed = true)
    private val mapper = ObjectMapper()
    private val service = GroupWorkflowService(types, groupStatuses, mapper)

    private val sprintType = ItemGroupType(id = 1L, objectId = UUID(0, 1), projectObjectId = UUID(0, 9), typeKey = "sprint", name = "Sprint", builtin = true, timed = true)

    private fun row(
        name: String,
        statusType: StatusType,
        transitions: String = "[]",
    ) = GroupStatus(id = name.length.toLong(), objectId = UUID(0, 2), groupTypeId = 1L, name = name, statusType = statusType, transitions = transitions)

    @Test
    fun seedWritesEdgesAsJson() {
        val saved = mutableListOf<GroupStatus>()
        every { groupStatuses.deleteByGroupTypeId(any()) } returns Unit
        every { groupStatuses.save(any()) } answers {
            saved.add(firstArg())
            firstArg()
        }
        service.seedFor(sprintType)
        assertEquals(3, saved.size)
        assertEquals("planned", saved[0].name)
        assertEquals(StatusType.START, saved[0].statusType)
        val edges = mapper.readTree(saved[0].transitions)
        assertEquals("start", edges[0]["function"].asString())
        assertEquals("active", edges[0]["target"].asString())
        // 0108:completed 加 reopen→active(重开=撤销内部收尾判断)
        val reopenEdges = mapper.readTree(saved[2].transitions)
        assertEquals(1, reopenEdges.size())
        assertEquals("reopen", reopenEdges[0]["function"].asString())
        assertEquals("active", reopenEdges[0]["target"].asString())
    }

    @Test
    fun seedCustomTypeAllConnectedSwitch() {
        val saved = mutableListOf<GroupStatus>()
        every { groupStatuses.deleteByGroupTypeId(any()) } returns Unit
        every { groupStatuses.save(any()) } answers {
            saved.add(firstArg())
            firstArg()
        }
        val phase = ItemGroupType(id = 2L, objectId = UUID(0, 3), projectObjectId = UUID(0, 9), typeKey = "phase", name = "阶段")
        service.seedFor(phase)
        assertEquals(3, saved.size)
        saved.forEach { assertEquals("switch", mapper.readTree(it.transitions)[0]["function"].asString()) }
        assertEquals(1, saved[1].transitions.let { mapper.readTree(it).size() }) // 每态单出边(三态循环)
    }

    @Test
    fun startStatusAndFinalNames() {
        every { groupStatuses.findByGroupTypeIdOrderByPosition(1L) } returns
            listOf(
                row("planned", StatusType.START),
                row("active", StatusType.ACTIVE),
                row("completed", StatusType.FINAL),
            )
        assertEquals("planned", service.startStatusName(sprintType))
        assertEquals(setOf("completed"), service.finalStatusNames(sprintType))
    }

    @Test
    fun applyActionFollowsEdgesAndRejectsOtherwise() {
        every { groupStatuses.findByGroupTypeIdOrderByPosition(1L) } returns
            listOf(
                row("planned", StatusType.START, """[{"function":"start","target":"active"}]"""),
                row("active", StatusType.ACTIVE, """[{"function":"complete","target":"completed"},{"function":"backward","target":"planned"}]"""),
                row("completed", StatusType.FINAL),
            )
        // 三个状态行全部先 stub(目标行查询需要真行,relaxed 子 mock 会在意图校验处炸)
        every { groupStatuses.findByGroupTypeIdAndName(1L, "planned") } returns row("planned", StatusType.START, """[{"function":"start","target":"active"}]""")
        every { groupStatuses.findByGroupTypeIdAndName(1L, "active") } returns row("active", StatusType.ACTIVE, """[{"function":"complete","target":"completed"},{"function":"backward","target":"planned"}]""")
        every { groupStatuses.findByGroupTypeIdAndName(1L, "completed") } returns row("completed", StatusType.FINAL)
        assertEquals("active", service.applyAction(sprintType, "planned", "start"))
        assertFailsWith<yangzhou.api.error.ConflictException> { service.applyAction(sprintType, "planned", "complete") }

        assertEquals("completed", service.applyAction(sprintType, "active", "complete"))

        assertFailsWith<yangzhou.api.error.ConflictException> { service.applyAction(sprintType, "completed", "start") } // 终态护栏
    }

    @Test
    fun actionRegistryValidatesIntent() {
        // spec 0097 §3.6:已知函数意图校验;未知函数数据驱动放行
        every { groupStatuses.findByGroupTypeIdOrderByPosition(1L) } returns
            listOf(
                row("planned", StatusType.START),
                row("active", StatusType.ACTIVE),
                row("completed", StatusType.FINAL),
            )
        // complete 出边指向非 FINAL → 409(意图违例)
        val bad = row("active", StatusType.ACTIVE, """[{"function":"complete","target":"active"}]""")
        every { groupStatuses.findByGroupTypeIdAndName(1L, "active") } returns bad
        assertFailsWith<yangzhou.api.error.ConflictException> { service.applyAction(sprintType, "active", "complete") }

        // 预留 reopen:FINAL→ACTIVE 合法
        val done = row("completed", StatusType.FINAL, """[{"function":"reopen","target":"active"}]""")
        every { groupStatuses.findByGroupTypeIdAndName(1L, "completed") } returns done
        assertEquals("active", service.applyAction(sprintType, "completed", "reopen"))

        // 预留 reopen 从非 FINAL 触发 → 409(source 意图违例)
        val activeGood = row("active", StatusType.ACTIVE, """[{"function":"reopen","target":"completed"}]""")
        every { groupStatuses.findByGroupTypeIdAndName(1L, "active") } returns activeGood
        assertFailsWith<yangzhou.api.error.ConflictException> { service.applyAction(sprintType, "active", "reopen") }

        // 未知函数(postpone):出边存在即迁移,注册表不拦
        val postpone = row("planned", StatusType.START, """[{"function":"postpone","target":"active"}]""")
        every { groupStatuses.findByGroupTypeIdAndName(1L, "planned") } returns postpone
        assertEquals("active", service.applyAction(sprintType, "planned", "postpone"))
    }
}
