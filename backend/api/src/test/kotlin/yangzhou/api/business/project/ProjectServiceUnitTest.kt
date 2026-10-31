package yangzhou.api.business.project

import io.mockk.Ordering
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
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
import java.util.UUID
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
    private val leaderGuard = mockk<yangzhou.api.guard.LeaderGuard>(relaxed = true)
    private val service =
        ProjectService(
            leaderGuard,
            projects,
            statuses,
            workflowRules,
            items,
            workspaceService,
            deletions,
            members,
            projectMembers,
            groupTypes,
        )

    private val wsId = UUID(0, 9)
    private val me =
        Member(id = 5L, objectId = UUID(0, 5), workspaceObjectId = wsId, username = "me", passwordHash = "h", displayName = "me")
    private val che = Project(id = 1L, objectId = UUID(0, 1), workspaceObjectId = wsId, key = "CHE", name = "CHE")

    @BeforeEach
    fun setup() {
        clearMocks(projects, statuses, workflowRules, items, workspaceService, deletions, members, projectMembers, groupTypes)
        every { workspaceService.required() } returns Workspace(id = 1L, objectId = wsId)
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
            every { projects.save(any()) } returns fresh.copy(id = 1L, objectId = UUID(0, 1))
            every { statuses.save(any<Status>()) } answers { firstArg<Status>().copy(id = (firstArg<Status>().position + 1).toLong()) }
            every { workflowRules.save(any<yangzhou.persistence.ProjectWorkflowRule>()) } answers
                { firstArg<yangzhou.persistence.ProjectWorkflowRule>() }
            every { projectMembers.save(any<yangzhou.persistence.ProjectMember>()) } answers { firstArg() }
            every { groupTypes.save(any<yangzhou.persistence.ItemGroupType>()) } answers { firstArg() }
            every { statuses.findByProjectObjectIdOrderByPosition(UUID(0, 1)) } returns defaultStatuses(UUID(0, 1))
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
            val s = Status(id = 1L, objectId = UUID(0, 1), projectObjectId = UUID(0, 1), name = "Done", isFinal = true, position = 4)
            every { statuses.findByProjectObjectIdOrderByPosition(UUID(0, 1)) } returns listOf(s)
            val dto = service.get("CHE")
            assertEquals(1, dto.statuses.size)
            assertEquals(true, dto.statuses[0].isFinal)
            assertEquals("Done", dto.statuses[0].name)
        }

        @Test
        fun listSkipsBrokenProjects() {
            every { projects.findAll() } returns listOf(che)
            every { statuses.findByProjectObjectIdOrderByPosition(UUID(0, 1)) } throws RuntimeException("boom")
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
                deletions.purgeRequirements(UUID(0, 1))
                deletions.purgeItemGroupMembers(UUID(0, 1))
                deletions.purgeItems(UUID(0, 1))
                deletions.purgeItemGroups(UUID(0, 1))
                deletions.purgeItemGroupTypes(UUID(0, 1))
                deletions.purgeMilestones(UUID(0, 1))
                deletions.deleteProject(UUID(0, 1))
            }
        }
    }

    /** 镜像 ProjectService.defaultStatuses(V7 五列)供 get 组装;种子内容已由 DefaultRuleSeedsUnitTest 守护。 */
    private fun defaultStatuses(projectObjectId: UUID): List<Status> = listOf(
        Status(projectObjectId = projectObjectId, name = "To Do", position = 0),
        Status(projectObjectId = projectObjectId, name = "In Progress", position = 1),
        Status(projectObjectId = projectObjectId, name = "In Review", position = 2),
        Status(projectObjectId = projectObjectId, name = "QA", position = 3),
        Status(projectObjectId = projectObjectId, name = "Done", isFinal = true, position = 4),
    )
}
