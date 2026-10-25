package yangzhou.api.business.view

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import yangzhou.api.business.member.MemberService
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Member
import yangzhou.persistence.Project
import yangzhou.persistence.ProjectView
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.ProjectViewRepository
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import tools.jackson.databind.ObjectMapper

/** YPJ-89 单测补齐:ViewService(用户×项目私有视图,4KB/对象约束)。 */
class ViewServiceUnitTest {

    private val views = mockk<ProjectViewRepository>(relaxed = true)
    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val memberService = mockk<MemberService>(relaxed = true)
    private val mapper = ObjectMapper()
    private val service = ViewService(views, projects, memberService, mapper)

    private val project = Project(id = 1L, workspaceId = 9L, key = "CHE", name = "CHE")
    private val me = Member(id = 5L, workspaceId = 9L, username = "me", passwordHash = "h", displayName = "me")

    private fun row(name: String, creatorId: Long = 5L, projectId: Long = 1L) = ProjectView(
        id = 10L, projectId = projectId, creatorId = creatorId, name = name,
        filters = """{"status":"open"}""", columns = """{"cols":["a"]}""",
    )

    @BeforeEach
    fun setup() {
        clearMocks(views, projects, memberService)
        every { projects.findByKey("CHE") } returns project
        every { memberService.current() } returns me
    }

    @Nested
    inner class CreateView {

        @Test
        fun blankNameRejected() {
            assertFailsWith<BadRequestException> { service.create("CHE", ViewService.CreateViewRequest("  ")) }
        }

        @Test
        fun duplicateNameRejected() {
            every { views.existsByProjectIdAndCreatorIdAndName(1L, 5L, "mine") } returns true
            assertFailsWith<ConflictException> { service.create("CHE", ViewService.CreateViewRequest("mine")) }
        }

        @Test
        fun nonObjectJsonRejected() {
            assertFailsWith<BadRequestException> {
                service.create("CHE", ViewService.CreateViewRequest("v", filters = mapper.readTree("[1,2]")))
            }
        }

        @Test
        fun oversizedJsonRejected() {
            val big = mapper.readTree("""{"k":"${"x".repeat(5000)}"}""")
            assertFailsWith<BadRequestException> {
                service.create("CHE", ViewService.CreateViewRequest("v", columns = big))
            }
        }

        @Test
        fun savedWithNullsAsEmptyObjects() {
            every { views.existsByProjectIdAndCreatorIdAndName(1L, 5L, "mine") } returns false
            every { views.save(any()) } answers { firstArg<ProjectView>().copy(id = 10L) }
            val dto = service.create("CHE", ViewService.CreateViewRequest("mine"))
            assertEquals("mine", dto.name)
            assertEquals(0, dto.filters.size()) // null → {}
            verify { views.save(withArg<ProjectView> { assertEquals("{}", it.columns) }) }
        }
    }

    @Nested
    inner class UpdateAndDelete {

        private val mine = row("mine")

        @BeforeEach
        fun stub() {
            every { views.findByObjectIdAndCreatorId(mine.objectId, 5L) } returns mine
        }

        @Test
        fun otherUsersViewIs404() {
            every { views.findByObjectIdAndCreatorId(any(), 5L) } returns null
            assertFailsWith<NotFoundException> { service.update("CHE", mine.objectId, ViewService.UpdateViewRequest(name = "x")) }
            assertFailsWith<NotFoundException> { service.delete("CHE", mine.objectId) }
        }

        @Test
        fun crossProjectViewIs404() {
            val foreign = row("foreign", projectId = 2L)
            every { views.findByObjectIdAndCreatorId(foreign.objectId, 5L) } returns foreign
            assertFailsWith<NotFoundException> { service.update("CHE", foreign.objectId, ViewService.UpdateViewRequest(name = "x")) }
        }

        @Test
        fun renameConflictOnlyWhenNameChanged() {
            every { views.existsByProjectIdAndCreatorIdAndName(1L, 5L, "other") } returns true
            every { views.save(any()) } answers { firstArg<ProjectView>() }
            // 改名成别的 → 冲突
            assertFailsWith<ConflictException> { service.update("CHE", mine.objectId, ViewService.UpdateViewRequest(name = "other")) }
            // 同名原地更新(改名等于本名)→ 放行
            assertEquals("mine", service.update("CHE", mine.objectId, ViewService.UpdateViewRequest(name = "mine")).name)
        }
    }

    @Nested
    inner class ListView {

        @Test
        fun onlyMineInProject() {
            every { views.findByProjectIdAndCreatorIdOrderByCreatedAt(1L, 5L) } returns listOf(row("mine"))
            assertEquals(listOf("mine"), service.list("CHE").map { it.name })
        }
    }
}
