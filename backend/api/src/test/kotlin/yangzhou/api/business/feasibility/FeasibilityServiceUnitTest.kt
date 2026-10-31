package yangzhou.api.business.feasibility

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import yangzhou.api.business.member.MemberService
import yangzhou.api.business.workspace.WorkspaceService
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.AttributeDefinition
import yangzhou.persistence.Capability
import yangzhou.persistence.Item
import yangzhou.persistence.Member
import yangzhou.persistence.Project
import yangzhou.persistence.Requirement
import yangzhou.persistence.Workspace
import yangzhou.persistence.repository.AttributeDefinitionRepository
import yangzhou.persistence.repository.CapabilityRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.RequirementRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * YPJ-80 mockk 子票①:FeasibilityService 装配/聚合分支单测。
 * 引擎(MatchingEngine)真跑不 mock;repository 协作者 mockk(relaxed),不起 Spring 不碰 DB。
 */
class FeasibilityServiceUnitTest {
    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val itemRepo = mockk<ItemRepository>(relaxed = true)
    private val requirementRepo = mockk<RequirementRepository>(relaxed = true)
    private val definitions = mockk<AttributeDefinitionRepository>(relaxed = true)
    private val capabilities = mockk<CapabilityRepository>(relaxed = true)
    private val memberService = mockk<MemberService>(relaxed = true)
    private val workspaceService = mockk<WorkspaceService>(relaxed = true)

    private val service =
        FeasibilityService(
            projects,
            itemRepo,
            requirementRepo,
            definitions,
            capabilities,
            memberService,
            workspaceService,
        )

    private val ws = Workspace(id = 1L, objectId = UUID(0, 1))
    private val project = Project(id = 100L, objectId = UUID(0, 100), workspaceObjectId = UUID(0, 1), key = "YPJ", name = "yangzhou")
    private val me =
        Member(id = 5L, objectId = UUID(0, 5), workspaceObjectId = UUID(0, 1), username = "me", passwordHash = "x", displayName = "我")
    private val attrKotlin = AttributeDefinition(id = 10L, objectId = UUID(0, 10), workspaceObjectId = UUID(0, 1), name = "kotlin")
    private val attrReact = AttributeDefinition(id = 11L, objectId = UUID(0, 11), workspaceObjectId = UUID(0, 1), name = "react")

    @BeforeEach
    fun setup() {
        clearMocks(projects, itemRepo, requirementRepo, definitions, capabilities, memberService, workspaceService)
        every { workspaceService.required() } returns ws
        every { definitions.findByWorkspaceObjectId(UUID(0, 1)) } returns listOf(attrKotlin, attrReact)
        every { memberService.current() } returns me
        every { capabilities.findByMemberObjectId(UUID(0, 5)) } returns
            listOf(Capability(memberObjectId = UUID(0, 5), attributeDefinitionObjectId = UUID(0, 10), level = 3))
        every { projects.findAll() } returns listOf(project)
        // 泛型 save 显式回传实参:relaxed 对 <S> 返回占位对象,service 侧链式使用即 CCE
        every { projects.save(any()) } answers {
            val arg: Project = firstArg()
            arg
        }
        every { itemRepo.save(any()) } answers {
            val arg: Item = firstArg()
            arg
        }
    }

    private fun item(
        id: Long,
        number: Int,
        vararg reqs: Requirement,
    ): Item {
        val it =
            Item(
                id = id,
                objectId = UUID(0, id),
                projectObjectId = UUID(0, 100),
                number = number,
                title = "item $number",
                statusObjectId = UUID.randomUUID(),
            )
        every { requirementRepo.findByItemObjectId(UUID(0, id)) } returns reqs.toList()
        every { itemRepo.findByObjectId(it.objectId) } returns it
        return it
    }

    private fun req(
        attrId: UUID,
        min: Int?,
    ) = Requirement(itemObjectId = UUID(0, 999), attributeDefinitionObjectId = attrId, minLevel = min)

    private fun stubItems(
        items: List<Item>,
        projectObjectId: UUID = UUID(0, 100),
    ) {
        every { itemRepo.findByProjectObjectIdAndDeletedAtIsNullOrderByNumber(projectObjectId) } returns items
    }

    @Nested
    inner class ProjectRead {
        @Test
        fun `GREEN - 全满足,装配与聚合字段正确`() {
            every { projects.findByKey("YPJ") } returns project
            stubItems(listOf(item(1L, 1, req(UUID(0, 10), 3))))

            val result = service.project("YPJ")

            assertEquals("GREEN", result.signal)
            assertEquals(0, result.missingCount)
            assertEquals(0, result.totalDelta)
            val r = result.items.single()
            assertEquals("YPJ-1", r.number)
            assertEquals("item 1", r.title)
            assertEquals("satisfied", r.verdicts.single().kind)
        }

        @Test
        fun `RED - gap 与 missing 聚合,verdict 映射到 DTO`() {
            every { projects.findByKey("YPJ") } returns project
            stubItems(listOf(item(1L, 1, req(UUID(0, 10), 4), req(UUID(0, 11), 2))))

            val result = service.project("YPJ")

            assertEquals("RED", result.signal)
            assertEquals(1, result.missingCount) // react 无任何 capability → missing
            assertEquals(1, result.totalDelta) // kotlin 需 4 有 3 → delta 1
            val kinds =
                result.items
                    .single()
                    .verdicts
                    .associate { it.attribute to it.kind }
            assertEquals("gap", kinds["kotlin"])
            assertEquals("missing", kinds["react"])
        }

        @Test
        fun `项目不存在 - 404`() {
            every { projects.findByKey("NOPE") } returns null
            assertFailsWith<NotFoundException> { service.project("NOPE") }
        }

        @Test
        fun `item 不存在 - 404`() {
            every { itemRepo.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.item(UUID.randomUUID()) }
        }
    }

    @Nested
    inner class RecomputeProjectSignal {
        @Test
        fun `worst 聚合 - RED 胜过 GREEN,写回 project_feasSignal`() {
            every { projects.findByObjectId(any()) } returns (project)
            val both = listOf(item(1L, 1, req(UUID(0, 10), 1)), item(2L, 2, req(UUID(0, 11), 2)))
            stubItems(both)

            val saved = mutableListOf<Project>()
            every { projects.save(any()) } answers {
                val arg: Project = firstArg()
                saved.add(arg)
                arg
            }
            assertEquals("RED", service.recomputeProjectSignal(UUID(0, 100)))
            assertEquals("RED", saved.single().feasSignal)
        }

        @Test
        fun `YELLOW - capability 存在但未评级`() {
            every { projects.findByObjectId(any()) } returns (project)
            every { capabilities.findByMemberObjectId(UUID(0, 5)) } returns
                listOf(Capability(memberObjectId = UUID(0, 5), attributeDefinitionObjectId = UUID(0, 10), level = null))
            val yellow = item(1L, 1, req(UUID(0, 10), 2))
            stubItems(listOf(yellow))

            assertEquals("YELLOW", service.recomputeProjectSignal(UUID(0, 100)))
            val v = service.item(yellow.objectId).verdicts.single()
            assertEquals("unrated", v.kind)
            assertEquals(2, v.required)
            assertNull(v.delta)
        }

        @Test
        fun `无 item - null 且不写回`() {
            stubItems(emptyList())
            every { projects.findByObjectId(any()) } returns (project)

            assertNull(service.recomputeProjectSignal(UUID(0, 100)))
        }
    }

    @Nested
    inner class RecomputeWorkspace {
        @Test
        fun `按 id 升序重算(YPJ-26 锁序)- 乱序输入也归序`() {
            val p1 = project.copy(id = 1L, objectId = UUID(0, 1))
            val p2 = project.copy(id = 2L, objectId = UUID(0, 2))
            every { projects.findAll() } returns listOf(p2, p1) // 故意乱序
            stubItems(emptyList(), UUID(0, 1))
            stubItems(emptyList(), UUID(0, 2))
            every { projects.findByObjectId(UUID(0, 1)) } returns (p1)
            every { projects.findByObjectId(UUID(0, 2)) } returns (p2)
            val saved = mutableListOf<Project>()
            every { projects.save(any()) } answers {
                val arg: Project = firstArg() // 泛型 save 坑:显式声明实参类型,坑见 mockk 计划文档 P0
                saved.add(arg)
                arg
            }

            service.recomputeWorkspace()

            val ids = saved.map { p -> p.id }
            assertEquals(listOf(1L, 2L), ids)
        }
    }

    @Nested
    inner class WorkspaceShortfall {
        @Test
        fun `gap 与 missing 归类聚合,坏项目 404 被吞不炸`() {
            val i1 = item(1L, 1, req(UUID(0, 10), 4), req(UUID(0, 11), 2))
            every { projects.findAll() } returns listOf(project, project.copy(id = 200L, key = "BAD"))
            every { projects.findByKey("YPJ") } returns project
            every { projects.findByKey("BAD") } returns null // project() 抛 404 → runCatching 吞
            stubItems(listOf(i1))

            val shortfall = service.workspaceShortfall()

            val byAttr = shortfall.associate { it.attribute to it }
            assertEquals(1, byAttr["kotlin"]!!.deltaSum)
            assertEquals(1, byAttr["react"]!!.missingCount)
            assertEquals(0, byAttr["react"]!!.unratedCount) // missing ≠ unrated
            assertTrue(shortfall.all { s -> s.items.all { it.projectKey == "YPJ" } })
        }
    }
}
