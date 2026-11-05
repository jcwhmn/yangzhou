package yangzhou.api.business.itemgroup

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.springframework.dao.DataIntegrityViolationException
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.ItemGroup
import yangzhou.persistence.ItemGroupMember
import yangzhou.persistence.ItemGroupType
import yangzhou.persistence.Project
import yangzhou.persistence.repository.ItemGroupMemberHistoryRepository
import yangzhou.persistence.repository.ItemGroupMemberRepository
import yangzhou.persistence.repository.ItemGroupRepository
import yangzhou.persistence.repository.ItemGroupTypeRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ProjectRepository
import java.time.Clock
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** spec 0097 S2 单测:统一组服务(类型 CRUD 守卫/组 CRUD/动作白名单/归属按类型正交)。原 GroupTypeServiceUnitTest 14 例语义迁移。 */
class ItemGroupServiceUnitTest {
    private val leaderGuard = mockk<yangzhou.api.guard.LeaderGuard>(relaxed = true)
    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val types = mockk<ItemGroupTypeRepository>(relaxed = true)
    private val workflow = mockk<GroupWorkflowService>(relaxed = true)
    private val groups = mockk<ItemGroupRepository>(relaxed = true)
    private val memberships = mockk<ItemGroupMemberRepository>(relaxed = true)
    private val itemRepo = mockk<ItemRepository>(relaxed = true)
    private val itemService = mockk<yangzhou.api.business.item.ItemService>(relaxed = true)
    private val memberHistory = mockk<ItemGroupMemberHistoryRepository>(relaxed = true)

    private val service =
        ItemGroupService(leaderGuard, projects, types, workflow, groups, memberships, itemRepo, itemService, memberHistory, Clock.systemUTC())

    private val project = Project(id = 1L, objectId = UUID(0, 1), workspaceObjectId = UUID(0, 9), key = "CHE", name = "CHE")
    private val sprintType = ItemGroupType(id = 1L, objectId = UUID(0, 2), projectObjectId = UUID(0, 1), typeKey = "sprint", name = "Sprint", builtin = true)
    private val phaseType = ItemGroupType(id = 2L, objectId = UUID(0, 3), projectObjectId = UUID(0, 1), typeKey = "phase", name = "阶段")

    private var groupSeq = 10L

    private fun group(
        name: String,
        type: String = "sprint",
        status: String = "planned",
    ): ItemGroup {
        groupSeq += 1
        return ItemGroup(id = 9L, objectId = UUID(0, groupSeq), projectObjectId = UUID(0, 1), type = type, name = name, status = status)
    }

    @BeforeEach
    fun setup() {
        clearMocks(leaderGuard, projects, types, workflow, groups, memberships, itemRepo, itemService, memberHistory)
        every { projects.findByKey("CHE") } returns project
        every { types.findByProjectObjectIdAndTypeKey(UUID(0, 1), "sprint") } returns sprintType
        every { types.findByProjectObjectIdAndTypeKey(UUID(0, 1), "phase") } returns phaseType
        every { types.findByProjectObjectIdOrderByCreatedAt(UUID(0, 1)) } returns listOf(sprintType, phaseType)
        every { groups.findByProjectObjectIdOrderByCreatedAt(UUID(0, 1)) } returns listOf(group("S1"), group("P1", "phase"))
        every { groups.findByProjectObjectIdAndObjectId(UUID(0, 1), any()) } answers { group("S1") }
        every { workflow.startStatusName(any()) } returns "planned"
        every { workflow.finalStatusNames(any()) } returns setOf("completed")
        every { itemService.get(any()) } returns mockk()
        // relaxed mock 的泛型 save 返回 Object 代理会 CCE,显式回传首参
        every { memberships.save(any<ItemGroupMember>()) } answers { firstArg<ItemGroupMember>() }
    }

    @Nested
    inner class TypeCrud {
        @Test
        fun createTypeRejectsBuiltinConflict() {
            assertFailsWith<BadRequestException> { service.createType("CHE", ItemGroupService.CreateGroupTypeRequest("sprint", "x")) }
        }

        @Test
        fun createTypeRejectsBadKeyAndDup() {
            assertFailsWith<BadRequestException> { service.createType("CHE", ItemGroupService.CreateGroupTypeRequest("X-上", "x")) }
            every { types.existsByProjectObjectIdAndTypeKey(UUID(0, 1), "phase2") } returns true
            assertFailsWith<ConflictException> { service.createType("CHE", ItemGroupService.CreateGroupTypeRequest("phase2", "x")) }
        }

        @Test
        fun createTypeSeedsWorkflow() {
            every { types.existsByProjectObjectIdAndTypeKey(UUID(0, 1), "phase2") } returns false
            every { types.save(any()) } answers { firstArg<ItemGroupType>().copy(id = 3L, objectId = UUID(0, 11)) }
            service.createType("CHE", ItemGroupService.CreateGroupTypeRequest("phase2", "二阶段"))
            verify { workflow.seedFor(any()) }
        }

        @Test
        fun deleteTypeRejectsBuiltinAndNonEmpty() {
            assertFailsWith<ConflictException> { service.deleteType("CHE", "sprint") }
            // phase 下有组 P1
            assertFailsWith<ConflictException> { service.deleteType("CHE", "phase") }
        }

        @Test
        fun deleteTypeUnknownNotFound() {
            every { types.findByProjectObjectIdAndTypeKey(UUID(0, 1), "ghost") } returns null // relaxed mock 默认给子 mock,须显式 null
            assertFailsWith<NotFoundException> { service.deleteType("CHE", "ghost") }
        }

        @Test
        fun createTypeAssignsLevelBottomUpAndTimed() {
            // 项目里尚无非 NULL level(如仅有 builtin release)→ 首个用户类型 = level 1;timed 透传
            every { types.findByProjectObjectIdOrderByCreatedAt(UUID(0, 1)) } returns listOf(sprintType.copy(level = null))
            every { types.save(any()) } answers { firstArg<ItemGroupType>().copy(id = 3L, objectId = UUID(0, 11)) }
            val dto = service.createType("CHE", ItemGroupService.CreateGroupTypeRequest("iteration", "迭代", timed = true))
            assertEquals(1, dto.level)
            assertEquals(true, dto.timed)
            // 已有 level-1 → 下一个 level 2;timed 缺省 false
            every { types.findByProjectObjectIdOrderByCreatedAt(UUID(0, 1)) } returns listOf(sprintType.copy(level = 1))
            val dto2 = service.createType("CHE", ItemGroupService.CreateGroupTypeRequest("epic", "Epic"))
            assertEquals(2, dto2.level)
            assertEquals(false, dto2.timed)
        }

        @Test
        fun createTypeMapsUniqueLevelRaceToConflict() {
            every { types.findByProjectObjectIdOrderByCreatedAt(UUID(0, 1)) } returns emptyList()
            every { types.save(any()) } throws DataIntegrityViolationException("ux_item_group_type_project_level")
            assertFailsWith<ConflictException> { service.createType("CHE", ItemGroupService.CreateGroupTypeRequest("iteration", "迭代")) }
        }
    }

    @Nested
    inner class GroupCrud {
        @Test
        fun createGroupStartsAtWorkflowStart() {
            every { groups.save(any()) } answers { firstArg<ItemGroup>().copy(id = 9L, objectId = UUID(0, 12)) }
            val dto = service.createGroup("CHE", ItemGroupService.CreateGroupRequest("phase", "P2"))
            assertEquals("planned", dto.status)
            verify { workflow.startStatusName(phaseType) }
        }

        @Test
        fun updateGroupIgnoresStatus() {
            val g = group("S1")
            every { groups.findByProjectObjectIdAndObjectId(UUID(0, 1), any()) } returns g
            every { groups.save(any()) } answers { firstArg<ItemGroup>() }
            val dto = service.updateGroup("CHE", g.objectId!!, ItemGroupService.UpdateGroupRequest(name = "S1 改"))
            assertEquals("S1 改", dto.name)
            assertEquals("planned", dto.status) // spec 0097:status 只走动作端点
        }

        @Test
        fun deleteSprintLikeGroupRejectsNonEmpty() {
            every { memberships.existsByGroupObjectId(any()) } returns true
            assertFailsWith<ConflictException> { service.deleteGroup("CHE", UUID(0, 10)) }
        }

        @Test
        fun deleteCustomGroupCascadesMembershipAndHistory() {
            val g = group("P1", "phase")
            every { groups.findByProjectObjectIdAndObjectId(UUID(0, 1), any()) } returns g
            every { memberships.existsByGroupObjectId(any()) } returns true
            val m = yangzhou.persistence.ItemGroupMember(groupObjectId = g.objectId!!, itemObjectId = UUID(0, 20))
            every { memberships.findByGroupObjectId(g.objectId!!) } returns listOf(m)
            every { memberHistory.findByGroupObjectId(g.objectId!!) } returns emptyList()
            service.deleteGroup("CHE", g.objectId!!)
            verify { memberships.delete(m) }
            verify { groups.delete(g) }
        }
    }

    @Nested
    inner class Actions {
        @Test
        fun actionAppliesWhitelist() {
            val g = group("S1", status = "active")
            every { groups.findByProjectObjectIdAndObjectId(UUID(0, 1), any()) } returns g
            every { workflow.applyAction(sprintType, "active", "complete") } returns "completed"
            every { groups.save(any()) } answers { firstArg<ItemGroup>() }
            assertEquals("completed", service.action("CHE", g.objectId!!, "complete").status)
        }

        @Test
        fun actionWithoutEdgeConflicts() {
            val g = group("S1")
            every { groups.findByProjectObjectIdAndObjectId(UUID(0, 1), any()) } returns g
            every { workflow.applyAction(sprintType, "planned", "explode") } throws ConflictException("当前状态无此动作:explode")
            assertFailsWith<ConflictException> { service.action("CHE", g.objectId!!, "explode") }
        }
    }

    @Nested
    inner class Membership {
        @Test
        fun replaceMembershipSwapsLiveRowOfSameType() {
            val item = yangzhou.persistence.Item(id = 1L, objectId = UUID(0, 20), projectObjectId = UUID(0, 1), number = 1, title = "x", statusObjectId = UUID(0, 99))
            every { itemRepo.findByObjectId(item.objectId!!) } returns item
            val oldSprint = group("S1", status = "active")
            val newSprint = group("S2", status = "active")
            every { groups.findByProjectObjectIdAndObjectId(item.projectObjectId, newSprint.objectId!!) } returns newSprint
            val rows =
                listOf(
                    yangzhou.persistence.ItemGroupMember(groupObjectId = oldSprint.objectId!!, itemObjectId = item.objectId!!),
                    yangzhou.persistence.ItemGroupMember(groupObjectId = UUID(0, 30), itemObjectId = item.objectId!!), // completed sprint(历史,保留)
                )
            every { memberships.findByItemObjectIdIn(any()) } returns rows
            val completed = group("S0", status = "completed")
            every { groups.findAllByObjectIdIn(any()) } returns listOf(oldSprint, completed)

            service.replaceMembership(item.objectId!!, ItemGroupService.MembershipRequest(groupId = newSprint.objectId, groupType = null))
            verify(exactly = 1) { memberships.delete(any()) } // 只删 live 旧行,历史行不动
            verify { memberHistory.recordRemove(oldSprint.objectId!!, item.objectId!!, any()) }
            verify { memberHistory.recordAdd(newSprint.objectId!!, item.objectId!!, any()) }
        }

        @Test
        fun membershipTargetFinalRejected() {
            val item = yangzhou.persistence.Item(id = 1L, objectId = UUID(0, 21), projectObjectId = UUID(0, 1), number = 2, title = "y", statusObjectId = UUID(0, 99))
            every { itemRepo.findByObjectId(item.objectId!!) } returns item
            val completed = group("S0", status = "completed")
            every { groups.findByProjectObjectIdAndObjectId(item.projectObjectId, completed.objectId!!) } returns completed
            every { memberships.findByItemObjectIdIn(any()) } returns emptyList()
            assertFailsWith<ConflictException> {
                service.replaceMembership(item.objectId!!, ItemGroupService.MembershipRequest(groupId = completed.objectId))
            }
        }

        @Test
        fun membershipRemoveRequiresType() {
            val item = yangzhou.persistence.Item(id = 1L, objectId = UUID(0, 22), projectObjectId = UUID(0, 1), number = 3, title = "z", statusObjectId = UUID(0, 99))
            every { itemRepo.findByObjectId(item.objectId!!) } returns item
            assertFailsWith<BadRequestException> {
                service.replaceMembership(item.objectId!!, ItemGroupService.MembershipRequest(groupId = null, groupType = null))
            }
            val live = group("S1", status = "active")
            every { memberships.findByItemObjectIdIn(any()) } returns
                listOf(yangzhou.persistence.ItemGroupMember(groupObjectId = live.objectId!!, itemObjectId = item.objectId!!))
            every { groups.findAllByObjectIdIn(any()) } returns listOf(live)
            service.replaceMembership(item.objectId!!, ItemGroupService.MembershipRequest(groupId = null, groupType = "sprint"))
            verify { memberHistory.recordRemove(live.objectId!!, item.objectId!!, any()) }
        }

        @Test
        fun membershipUnknownGroupOrTypeNotFound() {
            val item = yangzhou.persistence.Item(id = 1L, objectId = UUID(0, 23), projectObjectId = UUID(0, 1), number = 4, title = "w", statusObjectId = UUID(0, 99))
            every { itemRepo.findByObjectId(item.objectId!!) } returns item
            every { groups.findByProjectObjectIdAndObjectId(item.projectObjectId, any()) } returns null
            assertFailsWith<NotFoundException> {
                service.replaceMembership(item.objectId!!, ItemGroupService.MembershipRequest(groupId = UUID(0, 40)))
            }
            every { types.findByProjectObjectIdAndTypeKey(item.projectObjectId, "ghost") } returns null
            assertFailsWith<NotFoundException> {
                service.replaceMembership(item.objectId!!, ItemGroupService.MembershipRequest(groupId = null, groupType = "ghost"))
            }
        }
    }
}
