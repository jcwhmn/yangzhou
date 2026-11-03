package yangzhou.api.business.workflow

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.springframework.dao.DataIntegrityViolationException
import tools.jackson.databind.ObjectMapper
import yangzhou.api.error.ConflictException
import yangzhou.persistence.Project
import yangzhou.persistence.Status
import yangzhou.persistence.StatusType
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.StatusRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-89 单测补齐;YPJ-138(spec 0097 S1)适配:出边内嵌 status.transitions JSON,status_transition 表删除。 */
class WorkflowServiceUnitTest {
    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val statuses = mockk<StatusRepository>(relaxed = true)
    private val leaderGuard = mockk<yangzhou.api.guard.LeaderGuard>(relaxed = true)
    private val mapper = ObjectMapper()
    private val service = WorkflowService(leaderGuard, projects, statuses, mapper)

    private val project = Project(id = 1L, objectId = UUID(0, 1), workspaceObjectId = UUID(0, 9), key = "CHE", name = "CHE")
    private val todo = Status(id = 1L, objectId = UUID(0, 1), projectObjectId = UUID(0, 1), name = "To Do", statusType = StatusType.START, position = 0)
    private val done = Status(id = 2L, objectId = UUID(0, 2), projectObjectId = UUID(0, 1), name = "Done", statusType = StatusType.FINAL, position = 1)

    @BeforeEach
    fun setup() {
        clearMocks(projects, statuses)
        every { projects.findByKey("CHE") } returns project
        every { statuses.findByProjectObjectIdOrderByPosition(UUID(0, 1)) } returns listOf(todo, done)
    }

    @Nested
    inner class CreateStatus {
        @Test
        fun duplicateNameRejected() {
            assertFailsWith<ConflictException> { service.createStatus("CHE", "To Do", null, StatusType.ACTIVE, null) }
        }

        @Test
        fun positionAutoAppended() {
            every { statuses.save(any()) } answers { firstArg<Status>().copy(id = 3L, objectId = UUID(0, 3)) }
            assertEquals(2, service.createStatus("CHE", "QA", null, StatusType.ACTIVE, null).position) // max+1
        }
    }

    @Nested
    inner class UpdateStatus {
        @Test
        fun integrityViolationBecomesConflict() {
            every { statuses.findByObjectId(done.objectId) } returns done
            every { statuses.save(any()) } throws DataIntegrityViolationException("uk")
            assertFailsWith<ConflictException> { service.updateStatus(done.objectId, "Renamed", null, null, null) }
        }

        @Test
        fun droppingLastStartRejected() {
            val onlyStart = todo
            every { statuses.findByObjectId(onlyStart.objectId) } returns onlyStart
            every { statuses.save(any()) } answers { firstArg<Status>().copy(statusType = StatusType.ACTIVE) }
            // 保存后重载:START 归零 → 护栏 409(事务内回滚)
            every { statuses.findByProjectObjectIdOrderByPosition(UUID(0, 1)) } returns
                listOf(onlyStart.copy(statusType = StatusType.ACTIVE), done)
            assertFailsWith<ConflictException> { service.updateStatus(onlyStart.objectId, null, null, StatusType.ACTIVE, null) }
        }
    }

    @Nested
    inner class DeleteStatus {
        @Test
        fun integrityViolationBecomesConflict() {
            every { statuses.findByObjectId(todo.objectId) } returns todo
            every { statuses.delete(todo) } throws DataIntegrityViolationException("fk")
            assertFailsWith<ConflictException> { service.deleteStatus(todo.objectId) }
        }

        @Test
        fun deletedKeepsStartAndFinal() {
            every { statuses.findByObjectId(todo.objectId) } returns todo
            service.deleteStatus(todo.objectId)
            verify { statuses.delete(todo) }
        }

        @Test
        fun deletedLastFinalRejected() {
            every { statuses.findByObjectId(done.objectId) } returns done
            // 删除后重载:FINAL 归零 → 护栏 409
            every { statuses.findByProjectObjectIdOrderByPosition(UUID(0, 1)) } returns listOf(todo)
            assertFailsWith<ConflictException> { service.deleteStatus(done.objectId) }
        }
    }

    @Nested
    inner class Transitions {
        @Test
        fun replaceRejectsUnknownStatusName() {
            assertFailsWith<yangzhou.api.error.BadRequestException> {
                service.replaceTransitions("CHE", listOf(WorkflowService.TransitionDto("To Do", "QA")))
            }
        }

        @Test
        fun replaceRejectsUnknownFunction() {
            assertFailsWith<yangzhou.api.error.BadRequestException> {
                service.replaceTransitions("CHE", listOf(WorkflowService.TransitionDto("To Do", "Done", "explode")))
            }
        }

        @Test
        fun replaceWritesEdgesIntoStatusJson() {
            every { statuses.save(any()) } answers { firstArg<Status>() }
            service.replaceTransitions("CHE", listOf(WorkflowService.TransitionDto("To Do", "Done", "start")))
            verify {
                statuses.save(
                    withArg<Status> {
                        assertEquals("To Do", it.name)
                        val edges = mapper.readTree(it.transitions)
                        assertEquals(1, edges.size())
                        assertEquals("start", edges[0]["function"].asString())
                        assertEquals("Done", edges[0]["target"].asString())
                    },
                )
            }
            // 无出边的状态整表替换后清空
            verify {
                statuses.save(
                    withArg<Status> {
                        if (it.name == "Done") assertEquals("[]", it.transitions)
                    },
                )
            }
        }
    }

    @Nested
    inner class AssertTransitionAllowed {
        @Test
        fun emptyEdgesMeansFreeTransitions() {
            every { statuses.findByObjectId(todo.objectId) } returns todo // transitions 默认 "[]"
            service.assertTransitionAllowed(UUID(0, 1), fromStatusObjectId = UUID(0, 1), toStatusObjectId = UUID(0, 2)) // 不抛即通过
        }

        @Test
        fun edgesEnforced() {
            val withEdge = todo.copy(transitions = """[{"function":"switch","target":"Done"}]""")
            every { statuses.findByObjectId(todo.objectId) } returns withEdge
            every { statuses.findByObjectId(done.objectId) } returns done
            val qa = Status(id = 3L, objectId = UUID(0, 3), projectObjectId = UUID(0, 1), name = "QA", position = 2)
            every { statuses.findByObjectId(qa.objectId) } returns qa
            service.assertTransitionAllowed(UUID(0, 1), UUID(0, 1), done.objectId) // 出边内放行
            service.assertTransitionAllowed(UUID(0, 1), UUID(0, 1), UUID(0, 1)) // 自迁恒放行
            assertFailsWith<ConflictException> { service.assertTransitionAllowed(UUID(0, 1), UUID(0, 1), qa.objectId) }
            assertFailsWith<ConflictException> { service.assertTransitionAllowed(UUID(0, 1), UUID(0, 1), UUID(9, 9)) } // 未知目标
        }
    }
}
