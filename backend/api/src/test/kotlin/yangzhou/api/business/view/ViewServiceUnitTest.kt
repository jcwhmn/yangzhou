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

    private val project = Project(id = 1L, objectId = UUID(0, 1), workspaceObjectId = UUID(0, 9), key = "CHE", name = "CHE")
    private val me = Member(id = 5L, objectId = UUID(0, 5), workspaceObjectId = UUID(0, 9), username = "me", passwordHash = "h", displayName = "me")

    private fun row(name: String, creatorObjectId: UUID = UUID(0, 5), projectObjectId: UUID = UUID(0, 1)) = ProjectView(
        id = 10L, objectId = UUID(0, 10), projectObjectId = projectObjectId, creatorObjectId = creatorObjectId, name = name,
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
            every { views.existsByProjectObjectIdAndCreatorObjectIdAndName(UUID(0, 1), UUID(0, 5), "mine") } returns true
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
            every { views.existsByProjectObjectIdAndCreatorObjectIdAndName(UUID(0, 1), UUID(0, 5), "mine") } returns false
            every { views.save(any()) } answers { firstArg<ProjectView>().copy(id = 10L, objectId = UUID(0, 10)) }
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
            every { views.findByObjectIdAndCreatorObjectId(mine.objectId, UUID(0, 5)) } returns mine
        }

        @Test
        fun otherUsersViewIs404() {
            every { views.findByObjectIdAndCreatorObjectId(any(), UUID(0, 5)) } returns null
            assertFailsWith<NotFoundException> { service.update("CHE", mine.objectId, ViewService.UpdateViewRequest(name = "x")) }
            assertFailsWith<NotFoundException> { service.delete("CHE", mine.objectId) }
        }

        @Test
        fun crossProjectViewIs404() {
            val foreign = row("foreign", projectObjectId = UUID(0, 2))
            every { views.findByObjectIdAndCreatorObjectId(foreign.objectId, UUID(0, 5)) } returns foreign
            assertFailsWith<NotFoundException> { service.update("CHE", foreign.objectId, ViewService.UpdateViewRequest(name = "x")) }
        }

        @Test
        fun renameConflictOnlyWhenNameChanged() {
            every { views.existsByProjectObjectIdAndCreatorObjectIdAndName(UUID(0, 1), UUID(0, 5), "other") } returns true
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
            every { views.findByProjectObjectIdAndCreatorObjectIdOrderByCreatedAt(UUID(0, 1), UUID(0, 5)) } returns listOf(row("mine"))
            assertEquals(listOf("mine"), service.list("CHE").map { it.name })
        }
    }
}
