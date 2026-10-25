package yangzhou.api.business.workflow

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.dao.DataIntegrityViolationException
import yangzhou.api.error.ConflictException
import yangzhou.persistence.Project
import yangzhou.persistence.Status
import yangzhou.persistence.StatusTransition
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.StatusRepository
import yangzhou.persistence.repository.StatusTransitionRepository
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-89 单测补齐:WorkflowService(状态增删/迁移表整表替换/自由迁移默认)。 */
class WorkflowServiceUnitTest {

    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val statuses = mockk<StatusRepository>(relaxed = true)
    private val transitions = mockk<StatusTransitionRepository>(relaxed = true)
    private val service = WorkflowService(projects, statuses, transitions)

    private val project = Project(id = 1L, workspaceId = 9L, key = "CHE", name = "CHE")
    private val todo = Status(id = 1L, projectId = 1L, name = "To Do", position = 0)
    private val done = Status(id = 2L, projectId = 1L, name = "Done", isFinal = true, position = 1)

    @BeforeEach
    fun setup() {
        clearMocks(projects, statuses, transitions)
        every { projects.findByKey("CHE") } returns project
        every { statuses.findByProjectIdOrderByPosition(1L) } returns listOf(todo, done)
    }

    @Nested
    inner class CreateStatus {

        @Test
        fun duplicateNameRejected() {
            assertFailsWith<ConflictException> { service.createStatus("CHE", "To Do", null, false, false, null) }
        }

        @Test
        fun positionAutoAppended() {
            every { statuses.save(any()) } answers { firstArg<Status>().copy(id = 3L) }
            assertEquals(2, service.createStatus("CHE", "QA", null, false, false, null).position) // max+1
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
        fun deletedWithCascadingTransitions() {
            every { statuses.findByObjectId(todo.objectId) } returns todo
            service.deleteStatus(todo.objectId)
            verify { statuses.delete(todo) }
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
        fun replaceDeletesThenInserts() {
            every { transitions.deleteByProjectId(1L) } returns Unit
            every { transitions.save(any()) } answers { firstArg<StatusTransition>() }
            every { transitions.findByProjectId(1L) } returns listOf(
                StatusTransition(id = 9L, projectId = 1L, fromStatusId = 1L, toStatusId = 2L),
            )
            val result = service.replaceTransitions("CHE", listOf(WorkflowService.TransitionDto("To Do", "Done")))
            assertEquals(listOf("To Do" to "Done"), result.map { it.from to it.to })
            verify { transitions.deleteByProjectId(1L) }
            verify { transitions.save(withArg<StatusTransition> { assertEquals(1L, it.fromStatusId); assertEquals(2L, it.toStatusId) }) }
        }

        @Test
        fun emptyTableMeansFreeTransitions() {
            every { transitions.findByProjectId(1L) } returns emptyList()
            service.assertTransitionAllowed(1L, fromStatusId = 1L, toStatusId = 2L) // 不抛即通过
        }

        @Test
        fun offTableTransitionRejected() {
            val other = Status(id = 3L, projectId = 1L, name = "QA", position = 2)
            every { transitions.findByProjectId(1L) } returns listOf(
                StatusTransition(id = 1L, projectId = 1L, fromStatusId = 1L, toStatusId = 2L),
            )
            service.assertTransitionAllowed(1L, 1L, 2L) // 表内放行
            service.assertTransitionAllowed(1L, 1L, 1L) // 自迁恒放行
            assertFailsWith<ConflictException> { service.assertTransitionAllowed(1L, 1L, other.id!!) }
        }
    }
}
