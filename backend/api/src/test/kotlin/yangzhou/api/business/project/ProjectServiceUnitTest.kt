package yangzhou.api.business.project

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.Ordering
import io.mockk.mockk
import io.mockk.verify
import yangzhou.api.business.member.MemberService
import yangzhou.api.business.workspace.WorkspaceService
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Member
import yangzhou.persistence.Project
import yangzhou.persistence.Status
import yangzhou.persistence.Workspace
import yangzhou.persistence.repository.ItemGroupTypeRepository
import yangzhou.persistence.repository.ProjectDeletionRepository
import yangzhou.persistence.repository.ProjectMemberRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.ProjectWorkflowRuleRepository
import yangzhou.persistence.repository.StatusRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-87 单测补齐:ProjectService(建项种子/组装/删除)。规则种子细节见 DefaultRuleSeedsUnitTest,不重复。 */
class ProjectServiceUnitTest {

    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val statuses = mockk<StatusRepository>(relaxed = true)
    private val workflowRules = mockk<ProjectWorkflowRuleRepository>(relaxed = true)
    private val items = mockk<yangzhou.persistence.repository.ItemRepository>(relaxed = true)
    private val workspaceService = mockk<WorkspaceService>(relaxed = true)
    private val deletions = mockk<ProjectDeletionRepository>(relaxed = true)
    private val members = mockk<MemberService>(relaxed = true)
    private val projectMembers = mockk<ProjectMemberRepository>(relaxed = true)
    private val groupTypes = mockk<ItemGroupTypeRepository>(relaxed = true)
    private val service = ProjectService(
        projects, statuses, workflowRules, items, workspaceService,
        deletions, members, projectMembers, groupTypes,
    )

    private val wsId = 9L
    private val me = Member(id = 5L, workspaceId = wsId, username = "me", passwordHash = "h", displayName = "me")
    private val che = Project(id = 1L, workspaceId = wsId, key = "CHE", name = "CHE")

    @BeforeEach
    fun setup() {
        clearMocks(projects, statuses, workflowRules, items, workspaceService, deletions, members, projectMembers, groupTypes)
        every { workspaceService.required() } returns Workspace(id = wsId)
        every { members.current() } returns me
        every { projects.findByKey("CHE") } returns che
    }

    @Nested
    inner class Create {

        @Test
        fun duplicateKeyRejected() {
            every { projects.existsByKey("CHE") } returns true
            assertFailsWith<ConflictException> { service.create("CHE", "CHE") }
        }

        @Test
        fun seedsStatusesRulesGroupTypesAndCreatorPool() {
            val fresh = che.copy(id = null)
            every { projects.existsByKey("CHE") } returns false
            every { projects.save(any()) } returns fresh.copy(id = 1L)
            every { statuses.save(any<Status>()) } answers { firstArg<Status>().copy(id = (firstArg<Status>().position + 1).toLong()) }
            every { workflowRules.save(any<yangzhou.persistence.ProjectWorkflowRule>()) } answers { firstArg<yangzhou.persistence.ProjectWorkflowRule>() }
            every { projectMembers.save(any<yangzhou.persistence.ProjectMember>()) } answers { firstArg() }
            every { groupTypes.save(any<yangzhou.persistence.ItemGroupType>()) } answers { firstArg() }
            every { statuses.findByProjectIdOrderByPosition(1L) } returns defaultStatuses(1L)
            val saved = service.create("CHE", "CHE")
            assertEquals("CHE", saved.key)
            verify(exactly = 5) { statuses.save(any()) } // V7 五列
            verify(exactly = 2) { groupTypes.save(any()) } // sprint/release 内建
            verify { projectMembers.save(any()) } // 0047 创建者入池
        }
    }

    @Nested
    inner class GetAndList {

        @Test
        fun missingProjectRejected() {
            every { projects.findByKey("NOPE") } returns null
            assertFailsWith<NotFoundException> { service.get("NOPE") }
        }

        @Test
        fun statusDtosMapped() {
            val s = Status(id = 1L, projectId = 1L, name = "Done", isFinal = true, position = 4)
            every { statuses.findByProjectIdOrderByPosition(1L) } returns listOf(s)
            val dto = service.get("CHE")
            assertEquals(1, dto.statuses.size)
            assertEquals(true, dto.statuses[0].isFinal)
            assertEquals("Done", dto.statuses[0].name)
        }

        @Test
        fun listSkipsBrokenProjects() {
            every { projects.findAll() } returns listOf(che)
            every { statuses.findByProjectIdOrderByPosition(1L) } throws RuntimeException("boom")
            assertEquals(emptyList(), service.list()) // 组装失败跳过,不炸整列表
        }
    }

    @Nested
    inner class DeleteProject {

        @Test
        fun missingProjectRejected() {
            every { projects.findByKey("NOPE") } returns null
            assertFailsWith<NotFoundException> { service.delete("NOPE") }
        }

        @Test
        fun purgesInFkOrder() {
            service.delete("CHE")
            verify(ordering = Ordering.ORDERED) {
                deletions.purgeRequirements(1L)
                deletions.purgeItemGroupMembers(1L)
                deletions.purgeItems(1L)
                deletions.purgeItemGroups(1L)
                deletions.purgeItemGroupTypes(1L)
                deletions.purgeMilestones(1L)
                deletions.deleteProject(1L)
            }
        }
    }

    /** 镜像 ProjectService.defaultStatuses(V7 五列)供 get 组装;种子内容已由 DefaultRuleSeedsUnitTest 守护。 */
    private fun defaultStatuses(projectId: Long): List<Status> = listOf(
        Status(projectId = projectId, name = "To Do", position = 0),
        Status(projectId = projectId, name = "In Progress", position = 1),
        Status(projectId = projectId, name = "In Review", position = 2),
        Status(projectId = projectId, name = "QA", position = 3),
        Status(projectId = projectId, name = "Done", isFinal = true, position = 4),
    )
}
