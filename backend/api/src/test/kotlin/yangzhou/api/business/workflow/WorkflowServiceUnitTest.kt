package yangzhou.api.business.workflow

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.springframework.dao.DataIntegrityViolationException
import yangzhou.api.error.ConflictException
import yangzhou.persistence.Project
import yangzhou.persistence.Status
import yangzhou.persistence.StatusTransition
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.StatusRepository
import yangzhou.persistence.repository.StatusTransitionRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-89 单测补齐:WorkflowService(状态增删/迁移表整表替换/自由迁移默认)。 */
class WorkflowServiceUnitTest {
    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val statuses = mockk<StatusRepository>(relaxed = true)
    private val transitions = mockk<StatusTransitionRepository>(relaxed = true)
    private val leaderGuard = mockk<yangzhou.api.guard.LeaderGuard>(relaxed = true)
    private val service = WorkflowService(leaderGuard, projects, statuses, transitions)

    private val project = Project(id = 1L, objectId = UUID(0, 1), workspaceObjectId = UUID(0, 9), key = "CHE", name = "CHE")
    private val todo = Status(id = 1L, objectId = UUID(0, 1), projectObjectId = UUID(0, 1), name = "To Do", position = 0)
    private val done = Status(id = 2L, objectId = UUID(0, 2), projectObjectId = UUID(0, 1), name = "Done", isFinal = true, position = 1)

    @BeforeEach
    fun setup() {
        clearMocks(projects, statuses, transitions)
        every { projects.findByKey("CHE") } returns project
        every { statuses.findByProjectObjectIdOrderByPosition(UUID(0, 1)) } returns listOf(todo, done)
    }

    @Nested
    inner class CreateStatus {
        @Test
        fun duplicateNameRejected() {
            assertFailsWith<ConflictException> { service.createStatus("CHE", "To Do", null, false, false, null) }
        }

        @Test
        fun positionAutoAppended() {
            every { statuses.save(any()) } answers { firstArg<Status>().copy(id = 3L, objectId = UUID(0, 3)) }
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
            every { transitions.deleteByProjectObjectId(UUID(0, 1)) } returns Unit
            every { transitions.save(any()) } answers { firstArg<StatusTransition>() }
            every { transitions.findByProjectObjectId(UUID(0, 1)) } returns
                listOf(
                    StatusTransition(
                        id = 9L,
                        objectId = UUID(0, 9),
                        projectObjectId = UUID(0, 1),
                        fromStatusObjectId = UUID(0, 1),
                        toStatusObjectId = UUID(0, 2),
                    ),
                )
            val result = service.replaceTransitions("CHE", listOf(WorkflowService.TransitionDto("To Do", "Done")))
            assertEquals(listOf("To Do" to "Done"), result.map { it.from to it.to })
            verify { transitions.deleteByProjectObjectId(UUID(0, 1)) }
            verify {
                transitions.save(
                    withArg<StatusTransition> {
                        assertEquals(UUID(0, 1), it.fromStatusObjectId)
                        assertEquals(UUID(0, 2), it.toStatusObjectId)
                    },
                )
            }
        }

        @Test
        fun emptyTableMeansFreeTransitions() {
            every { transitions.findByProjectObjectId(UUID(0, 1)) } returns emptyList()
            service.assertTransitionAllowed(UUID(0, 1), fromStatusObjectId = UUID(0, 1), toStatusObjectId = UUID(0, 2)) // 不抛即通过
        }

        @Test
        fun offTableTransitionRejected() {
            val other = Status(id = 3L, objectId = UUID(0, 3), projectObjectId = UUID(0, 1), name = "QA", position = 2)
            every { transitions.findByProjectObjectId(UUID(0, 1)) } returns
                listOf(
                    StatusTransition(
                        id = 1L,
                        objectId = UUID(0, 1),
                        projectObjectId = UUID(0, 1),
                        fromStatusObjectId = UUID(0, 1),
                        toStatusObjectId = UUID(0, 2),
                    ),
                )
            service.assertTransitionAllowed(UUID(0, 1), UUID(0, 1), UUID(0, 2)) // 表内放行
            service.assertTransitionAllowed(UUID(0, 1), UUID(0, 1), UUID(0, 1)) // 自迁恒放行
            assertFailsWith<ConflictException> { service.assertTransitionAllowed(UUID(0, 1), UUID(0, 1), other.objectId) }
        }
    }
}
