package yangzhou.api.business.sprint

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import yangzhou.api.business.item.ItemService
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Item
import yangzhou.persistence.ItemGroup
import yangzhou.persistence.ItemGroupMember
import yangzhou.persistence.Project
import yangzhou.persistence.repository.ItemGroupMemberHistoryRepository
import yangzhou.persistence.repository.ItemGroupMemberRepository
import yangzhou.persistence.repository.ItemGroupRepository
import yangzhou.persistence.repository.ItemGroupTypeRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ProjectRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested

/**
 * YPJ-81 mockk 子票②:SprintService 指派/状态规则单测。
 * 不起 Spring;repository 协作者 mockk(relaxed);Clock.fixed 钉死留痕时刻。
 */
class SprintServiceUnitTest {

    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val groups = mockk<ItemGroupRepository>(relaxed = true)
    private val memberships = mockk<ItemGroupMemberRepository>(relaxed = true)
    private val itemRepo = mockk<ItemRepository>(relaxed = true)
    private val itemService = mockk<ItemService>(relaxed = true)
    private val memberHistory = mockk<ItemGroupMemberHistoryRepository>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC)
    private val groupTypes = mockk<ItemGroupTypeRepository>(relaxed = true)

    private val savedRows = mutableListOf<ItemGroupMember>()

    private val leaderGuard = mockk<yangzhou.api.guard.LeaderGuard>(relaxed = true)
    private val service = SprintService(
        leaderGuard,
        projects, groups, memberships, itemRepo, itemService, memberHistory, clock, groupTypes,
    )

    private val project = Project(id = 100L, workspaceId = 1L, key = "YPJ", name = "yangzhou")
    private val item = Item(id = 9L, objectId = UUID.randomUUID(), projectId = 100L, number = 7, title = "任务", statusObjectId = UUID.randomUUID())
    private val sprintOid = UUID.randomUUID()
    private val sprint = ItemGroup(id = 55L, objectId = sprintOid, projectId = 100L, name = "V23", status = "planned")
    private val otherOid = UUID.randomUUID()
    private val otherSprint = ItemGroup(id = 66L, objectId = otherOid, projectId = 100L, name = "V24", status = "planned")

    @BeforeEach
    fun setup() {
        clearMocks(projects, groups, memberships, itemRepo, itemService, memberHistory, groupTypes) // clock 是 fixed 真实例,非 mock,勿 clear
        every { projects.findByKey("YPJ") } returns project
        every { groups.findByProjectIdAndObjectId(100L, sprintOid) } returns sprint
        every { groups.findByProjectIdAndObjectId(100L, otherOid) } returns otherSprint
        every { itemRepo.findByObjectId(item.objectId) } returns item
        savedRows.clear()
        every { memberships.save(any()) } answers { val arg: ItemGroupMember = firstArg(); savedRows.add(arg); arg } // 泛型 save 坑(P0);记录落库行供断言
        every { itemService.get(item.objectId) } returns mockk()
    }

    private fun currentRow(groupId: Long = 55L) = ItemGroupMember(id = 77L, groupId = groupId, itemId = 9L, createdAt = Instant.EPOCH) // createdAt 钉死:data class 相等性依赖它

    private fun stubCurrent(row: ItemGroupMember?) {
        if (row == null) {
            every { memberships.findByItemIdIn(listOf(9L)) } returns emptyList()
        } else {
            every { memberships.findByItemIdIn(listOf(9L)) } returns listOf(row)
            every { groups.findAllById(setOf(row.groupId)) } returns
                listOf(if (row.groupId == 55L) sprint else otherSprint)
        }
    }

    @Nested
    inner class AssignItem {
        @Test
        fun `item 不存在 - 404`() {
            every { itemRepo.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.assignItem(UUID.randomUUID(), sprintOid) }
        }

        @Test
        fun `sprint 不存在 - 404`() {
            stubCurrent(null)
            every { groups.findByProjectIdAndObjectId(100L, any()) } returns null // relaxed 对未知 UUID 会造非空占位,显式压 null
            assertFailsWith<NotFoundException> { service.assignItem(item.objectId, UUID.randomUUID()) }
        }

        @Test
        fun `completed sprint - 409 拒绝指派`() {
            stubCurrent(null)
            every { groups.findByProjectIdAndObjectId(100L, sprintOid) } returns sprint.copy(status = "completed")
            assertFailsWith<ConflictException> { service.assignItem(item.objectId, sprintOid) }
        }

        @Test
        fun `sprintId 为 null - 移出当前组并留痕`() {
            stubCurrent(currentRow())

            service.assignItem(item.objectId, null)

            verify(exactly = 1) { memberships.delete(any()) }
            verify(exactly = 1) { memberHistory.recordRemove(55L, 9L, clock.instant()) }
            verify(exactly = 0) { memberships.save(any()) }
        }

        @Test
        fun `sprintId 为 null 且无当前组 - 直接返回不留痕`() {
            stubCurrent(null)

            service.assignItem(item.objectId, null)

            verify(exactly = 0) { memberships.delete(any()) }
            verify(exactly = 0) { memberHistory.recordRemove(any(), any(), any()) }
        }

        @Test
        fun `同 sprint 重复指派 - 幂等,不删不存不留痕`() {
            stubCurrent(currentRow())

            service.assignItem(item.objectId, sprintOid)

            verify(exactly = 0) { memberships.delete(any()) }
            verify(exactly = 0) { memberships.save(any()) }
            verify(exactly = 0) { memberHistory.recordAdd(any(), any(), any()) }
        }

        @Test
        fun `换 sprint - 删旧留痕,存新留痕`() {
            stubCurrent(currentRow())

            service.assignItem(item.objectId, otherOid)

            verify(exactly = 1) { memberships.delete(any()) } // delete 匹配用 any:row 的 objectId 每次随机
            verify(exactly = 1) { memberHistory.recordRemove(55L, 9L, clock.instant()) }
            verify(exactly = 1) { memberHistory.recordAdd(66L, 9L, clock.instant()) }
            assertEquals(66L, savedRows.single().groupId)
        }

        @Test
        fun `无当前组 - 直加新组留痕`() {
            stubCurrent(null)

            service.assignItem(item.objectId, sprintOid)

            verify(exactly = 0) { memberships.delete(any()) }
            verify(exactly = 1) { memberHistory.recordAdd(55L, 9L, clock.instant()) }
        }
    }

    @Nested
    inner class Update {
        @Test
        fun `status 非法 - 400`() {
            assertFailsWith<BadRequestException> {
                service.update("YPJ", sprintOid, SprintService.UpdateSprintRequest(status = "running"))
            }
        }

        @Test
        fun `name 空白 - 400`() {
            assertFailsWith<BadRequestException> {
                service.update("YPJ", sprintOid, SprintService.UpdateSprintRequest(name = "   "))
            }
        }

        @Test
        fun `合法变更 - status 落库,其余字段保留`() {
            every { groups.save(any()) } answers { val arg: ItemGroup = firstArg(); arg }

            val dto = service.update("YPJ", sprintOid, SprintService.UpdateSprintRequest(status = "active"))

            assertEquals("active", dto.status)
            assertEquals("V23", dto.name)
        }
    }
}
