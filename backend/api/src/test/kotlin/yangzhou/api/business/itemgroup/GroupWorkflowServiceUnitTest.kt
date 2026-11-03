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

    private val sprintType = ItemGroupType(id = 1L, objectId = UUID(0, 1), projectObjectId = UUID(0, 9), typeKey = "sprint", name = "Sprint", builtin = true)

    private fun row(
        name: String,
        statusType: StatusType,
        transitions: String = "[]",
    ) = GroupStatus(id = name.length.toLong(), objectId = UUID(0, 2), groupTypeId = 1L, name = name, statusType = statusType, transitions = transitions)

    @Test
    fun seedWritesEdgesAsJson() {
        val saved = mutableListOf<GroupStatus>()
        every { groupStatuses.deleteByGroupTypeId(any()) } returns Unit
        every { groupStatuses.save(any()) } answers { saved.add(firstArg()); firstArg() }
        service.seedFor(sprintType)
        assertEquals(3, saved.size)
        assertEquals("planned", saved[0].name)
        assertEquals(StatusType.START, saved[0].statusType)
        val edges = mapper.readTree(saved[0].transitions)
        assertEquals("start", edges[0]["function"].asString())
        assertEquals("active", edges[0]["target"].asString())
        assertEquals("[]", saved[2].transitions) // completed 终态护栏
    }

    @Test
    fun seedCustomTypeAllConnectedSwitch() {
        val saved = mutableListOf<GroupStatus>()
        every { groupStatuses.deleteByGroupTypeId(any()) } returns Unit
        every { groupStatuses.save(any()) } answers { saved.add(firstArg()); firstArg() }
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
        every { groupStatuses.findByGroupTypeIdAndName(1L, "planned") } returns row("planned", StatusType.START, """[{"function":"start","target":"active"}]""")
        assertEquals("active", service.applyAction(sprintType, "planned", "start"))
        assertFailsWith<yangzhou.api.error.ConflictException> { service.applyAction(sprintType, "planned", "complete") }

        val active = row("active", StatusType.ACTIVE, """[{"function":"complete","target":"completed"},{"function":"backward","target":"planned"}]""")
        every { groupStatuses.findByGroupTypeIdAndName(1L, "active") } returns active
        assertEquals("completed", service.applyAction(sprintType, "active", "complete"))

        val completed = row("completed", StatusType.FINAL)
        every { groupStatuses.findByGroupTypeIdAndName(1L, "completed") } returns completed
        assertFailsWith<yangzhou.api.error.ConflictException> { service.applyAction(sprintType, "completed", "start") } // 终态护栏
    }
}
