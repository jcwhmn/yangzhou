package yangzhou.api.business.grouptype

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.ItemGroup
import yangzhou.persistence.ItemGroupType
import yangzhou.persistence.Project
import yangzhou.persistence.repository.ItemGroupMemberHistoryRepository
import yangzhou.persistence.repository.ItemGroupMemberRepository
import yangzhou.persistence.repository.ItemGroupRepository
import yangzhou.persistence.repository.ItemGroupTypeRepository
import yangzhou.persistence.repository.ProjectRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * YPJ-48 GroupTypeService 单测(spec 0056):直实例化 + mockk(relaxed),不起 Spring 不碰 DB。
 * 覆盖:typeKey 校验、内建冲突、唯一 409、builtin 保护、类型下有组拒删、通用组校验与内建封闭。
 */
class GroupTypeServiceUnitTest {
    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val types = mockk<ItemGroupTypeRepository>(relaxed = true)
    private val groups = mockk<ItemGroupRepository>(relaxed = true)
    private val memberships = mockk<ItemGroupMemberRepository>(relaxed = true)
    private val memberHistory = mockk<ItemGroupMemberHistoryRepository>(relaxed = true)

    private val leaderGuard = mockk<yangzhou.api.guard.LeaderGuard>(relaxed = true)
    private val service = GroupTypeService(leaderGuard, projects, types, groups, memberships, memberHistory)

    private val project = Project(workspaceObjectId = UUID(0, 1), key = "P", name = "P").let { it.copy(id = 7L, objectId = UUID(0, 7)) }

    private fun type(
        key: String,
        builtin: Boolean = false,
    ) = ItemGroupType(projectObjectId = UUID(0, 7), typeKey = key, name = key, builtin = builtin).let {
        it.copy(id = 100L, objectId = UUID(0, 100))
    }

    private fun stubProject() {
        every { projects.findByKey("P") } returns project
    }

    // ---------- 类型 CRUD ----------

    @Test
    fun `typeKey 非法 - 大写或超长或坏字符 400`() {
        stubProject()
        for (bad in listOf("Q1", "A-z", "-lead", "x", "a".repeat(21), "有中文")) {
            assertFailsWith<BadRequestException> {
                service.createType("P", GroupTypeService.CreateGroupTypeRequest(bad, "名"))
            }
        }
    }

    @Test
    fun `typeKey 与内建冲突 400`() {
        stubProject()
        assertFailsWith<BadRequestException> {
            service.createType("P", GroupTypeService.CreateGroupTypeRequest("sprint", "Sprint"))
        }
    }

    @Test
    fun `重复 typeKey 409`() {
        stubProject()
        every { types.existsByProjectObjectIdAndTypeKey(UUID(0, 7), "quarter") } returns true
        assertFailsWith<ConflictException> {
            service.createType("P", GroupTypeService.CreateGroupTypeRequest("quarter", "季度"))
        }
    }

    @Test
    fun `建类型成功 - 仓库收到正确实体`() {
        stubProject()
        every { types.existsByProjectObjectIdAndTypeKey(UUID(0, 7), "quarter") } returns false
        every { types.save(any<ItemGroupType>()) } answers { firstArg() }
        val dto = service.createType("P", GroupTypeService.CreateGroupTypeRequest("quarter", "季度"))
        assertEquals("quarter", dto.typeKey)
        assertEquals("季度", dto.name)
        assertEquals(false, dto.builtin)
    }

    @Test
    fun `改名 - builtin 也可改`() {
        stubProject()
        val sprint = type("sprint", builtin = true)
        every { types.findByProjectObjectIdAndTypeKey(UUID(0, 7), "sprint") } returns sprint
        every { types.save(any<ItemGroupType>()) } answers { firstArg() }
        val dto = service.updateType("P", "sprint", GroupTypeService.UpdateGroupTypeRequest("冲刺"))
        assertEquals("冲刺", dto.name)
    }

    @Test
    fun `删类型 - builtin 409`() {
        stubProject()
        every { types.findByProjectObjectIdAndTypeKey(UUID(0, 7), "sprint") } returns type("sprint", builtin = true)
        assertFailsWith<ConflictException> { service.deleteType("P", "sprint") }
    }

    @Test
    fun `删类型 - 类型下有组 409 且不落删`() {
        stubProject()
        every { types.findByProjectObjectIdAndTypeKey(UUID(0, 7), "quarter") } returns type("quarter")
        every { groups.findByProjectObjectIdOrderByCreatedAt(UUID(0, 7)) } returns
            listOf(
                ItemGroup(projectObjectId = UUID(0, 7), type = "quarter", name = "Q1"),
            )
        assertFailsWith<ConflictException> { service.deleteType("P", "quarter") }
        verify(exactly = 0) { types.delete(any<ItemGroupType>()) }
    }

    @Test
    fun `删类型 - 空类型成功`() {
        stubProject()
        val quarter = type("quarter")
        every { types.findByProjectObjectIdAndTypeKey(UUID(0, 7), "quarter") } returns quarter
        every { groups.findByProjectObjectIdOrderByCreatedAt(UUID(0, 7)) } returns emptyList()
        service.deleteType("P", "quarter")
        verify(exactly = 1) { types.delete(quarter) }
    }

    @Test
    fun `类型不存在 404`() {
        stubProject()
        every { types.findByProjectObjectIdAndTypeKey(UUID(0, 7), "nope") } returns null
        assertFailsWith<NotFoundException> { service.deleteType("P", "nope") }
    }

    // ---------- 通用组 ----------

    @Test
    fun `建组 - 内建类型封闭 400`() {
        stubProject()
        every { types.findByProjectObjectIdAndTypeKey(UUID(0, 7), "sprint") } returns type("sprint", builtin = true)
        assertFailsWith<BadRequestException> {
            service.createGroup("P", GroupTypeService.CreateGroupRequest("sprint", "绕道"))
        }
    }

    @Test
    fun `建组 - status 非法或日期格式坏 400`() {
        stubProject()
        every { types.findByProjectObjectIdAndTypeKey(UUID(0, 7), "quarter") } returns type("quarter")
        assertFailsWith<BadRequestException> {
            service.createGroup("P", GroupTypeService.CreateGroupRequest("quarter", "Q1", status = "released"))
        }
        assertFailsWith<BadRequestException> {
            service.createGroup("P", GroupTypeService.CreateGroupRequest("quarter", "Q1", startDate = "2026/01/01"))
        }
    }

    @Test
    fun `建组成功 - 缺省 planned,日期可选`() {
        stubProject()
        every { types.findByProjectObjectIdAndTypeKey(UUID(0, 7), "quarter") } returns type("quarter")
        every { groups.save(any<ItemGroup>()) } answers { firstArg<ItemGroup>().copy(id = 1L, objectId = UUID(0, 1)) }
        val dto =
            service.createGroup(
                "P",
                GroupTypeService.CreateGroupRequest("quarter", "2026-Q1", startDate = "2026-01-01", endDate = "2026-03-31"),
            )
        assertEquals("planned", dto.status)
        assertEquals("2026-01-01", dto.startDate)
        assertEquals("quarter", dto.typeKey)
    }

    @Test
    fun `改组删组 - 内建类型封闭 400`() {
        stubProject()
        val oid = UUID(0, 5)
        val sprint = ItemGroup(projectObjectId = UUID(0, 7), type = "sprint", name = "S1").let { it.copy(objectId = oid, id = 5L) }
        every { groups.findByProjectObjectIdAndObjectId(UUID(0, 7), oid) } returns sprint
        assertFailsWith<BadRequestException> {
            service.updateGroup("P", oid, GroupTypeService.UpdateGroupRequest(name = "x"))
        }
        assertFailsWith<BadRequestException> { service.deleteGroup("P", oid) }
    }

    @Test
    fun `删组 - 级联清 membership 与留痕行`() {
        stubProject()
        val oid = UUID(0, 5)
        val quarter = ItemGroup(projectObjectId = UUID(0, 7), type = "quarter", name = "Q1").let { it.copy(objectId = oid, id = 5L) }
        every { groups.findByProjectObjectIdAndObjectId(UUID(0, 7), oid) } returns quarter
        val m = yangzhou.persistence.ItemGroupMember(groupObjectId = UUID(0, 5), itemObjectId = UUID(0, 9))
        every { memberships.findByGroupObjectId(UUID(0, 5)) } returns listOf(m)
        every { memberHistory.findByGroupObjectId(UUID(0, 5)) } returns emptyList()
        service.deleteGroup("P", oid)
        verify(exactly = 1) { memberships.delete(m) }
        verify(exactly = 1) { groups.delete(quarter) }
    }
}
