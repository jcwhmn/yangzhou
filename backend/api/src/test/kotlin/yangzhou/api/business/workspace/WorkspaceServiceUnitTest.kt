package yangzhou.api.business.workspace

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import yangzhou.persistence.Workspace
import yangzhou.persistence.repository.WorkspaceRepository
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-87 单测补齐:WorkspaceService(单租户隐形,ADR-0001)。 */
class WorkspaceServiceUnitTest {

    private val workspaces = mockk<WorkspaceRepository>(relaxed = true)
    private val service = WorkspaceService(workspaces)

    @BeforeEach
    fun setup() = clearMocks(workspaces)

    @Test
    fun ensureDefaultReturnsExisting() {
        val ws = Workspace(id = 1L)
        every { workspaces.findAll() } returns listOf(ws)
        assertEquals(1L, service.ensureDefault().id)
    }

    @Test
    fun ensureDefaultCreatesWhenEmpty() {
        every { workspaces.findAll() } returns emptyList()
        every { workspaces.save(any()) } answers { firstArg<Workspace>().copy(id = 9L) }
        assertEquals(9L, service.ensureDefault().id)
    }

    @Test
    fun requiredFailsWhenMissing() {
        every { workspaces.findAll() } returns emptyList()
        assertFailsWith<IllegalStateException> { service.required() }
    }
}
