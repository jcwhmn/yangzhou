package yangzhou.api.business.milestone

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Item
import yangzhou.persistence.Member
import yangzhou.persistence.Milestone
import yangzhou.persistence.MilestoneItem
import yangzhou.persistence.Project
import yangzhou.persistence.Status
import yangzhou.persistence.StatusType
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.MilestoneItemRepository
import yangzhou.persistence.repository.MilestoneRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.StatusRepository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** YPJ-89 单测补齐:MilestoneService(状态机:单 in_progress/终态不可变);YPJ-170 徽标/描述/负责人/终态删除守卫。 */
class MilestoneServiceUnitTest {
    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val milestones = mockk<MilestoneRepository>(relaxed = true)
    private val members = mockk<MemberRepository>(relaxed = true)
    private val milestoneItems = mockk<MilestoneItemRepository>(relaxed = true)
    private val items = mockk<ItemRepository>(relaxed = true)
    private val statuses = mockk<StatusRepository>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC)
    private val service = MilestoneService(projects, milestones, members, milestoneItems, items, statuses, clock)

    private val project = Project(id = 1L, objectId = UUID(0, 1), workspaceObjectId = UUID(0, 9), key = "CHE", name = "CHE")
    private val m = Milestone(id = 1L, objectId = UUID(0, 1), projectObjectId = UUID(0, 1), name = "M1")

    @BeforeEach
    fun setup() {
        clearMocks(projects, milestones, members, milestoneItems, items, statuses)
        every { projects.findByKey("CHE") } returns project
        every { milestones.findByProjectObjectIdAndObjectId(UUID(0, 1), m.objectId) } returns m
    }

    @Nested
    inner class CreateMilestone {
        @Test
        fun blankNameRejected() {
            assertFailsWith<BadRequestException> { service.create("CHE", CreateMilestoneRequest("  ")) }
        }

        @Test
        fun badDateRejected() {
            assertFailsWith<BadRequestException> {
                service.create("CHE", CreateMilestoneRequest("M2", targetDate = "2026/01/01"))
            }
        }

        @Test
        fun descriptionAndOwnerSaved() {
            every { milestones.save(any()) } answers { firstArg<Milestone>() }
            val ownerId = UUID(0, 7)
            every { members.findByObjectId(ownerId) } returns Member(id = 7L, objectId = ownerId, workspaceObjectId = UUID(0, 9), username = "amy", passwordHash = null, displayName = "Amy")
            val dto = service.create("CHE", CreateMilestoneRequest("M2", targetDate = "2026-12-01", description = "验收", owner = ownerId.toString()))
            assertEquals("验收", dto.description)
            assertEquals(ownerId, dto.owner)
        }

        @Test
        fun unknownOwnerRejected() {
            val oid = UUID(0, 8)
            every { members.findByObjectId(oid) } returns null
            assertFailsWith<NotFoundException> { service.create("CHE", CreateMilestoneRequest("M2", owner = oid.toString())) }
        }
    }

    @Nested
    inner class Badges {
        /** 固定时钟 today=2026-11-12;overdue=红(due<today 且未终态),dueSoon=黄([today, today+3)),互斥。 */
        private fun dtoFor(
            targetDate: String?,
            status: String = "planned",
        ): MilestoneService.MilestoneDto {
            every { milestones.findByProjectObjectIdOrderByCreatedAt(UUID(0, 1)) } returns
                listOf(m.copy(targetDate = targetDate?.let(java.time.LocalDate::parse), status = status))
            return service.list("CHE").single()
        }

        @Test
        fun overdueForPastNonTerminal() {
            assertEquals(true, dtoFor("2026-11-11").overdue)
            assertEquals(true, dtoFor("2026-11-11", status = "in_progress").overdue)
        }

        @Test
        fun noOverdueForTerminalNullFuture() {
            assertEquals(false, dtoFor("2026-11-11", status = "completed").overdue)
            assertEquals(false, dtoFor(null).overdue)
            assertEquals(false, dtoFor("2026-11-13").overdue)
        }

        @Test
        fun dueSoonWindow() {
            assertEquals(true, dtoFor("2026-11-12").dueSoon)
            assertEquals(true, dtoFor("2026-11-14").dueSoon)
            assertEquals(false, dtoFor("2026-11-15").dueSoon)
            assertEquals(false, dtoFor("2026-11-12", status = "completed").dueSoon)
        }

        @Test
        fun badgesMutuallyExclusive() {
            val d = dtoFor("2026-11-11")
            assertEquals(true, d.overdue)
            assertEquals(false, d.dueSoon)
        }
    }

    @Nested
    inner class UpdateMilestone {
        @Test
        fun invalidStatusRejected() {
            assertFailsWith<BadRequestException> {
                service.update("CHE", m.objectId, UpdateMilestoneRequest(status = "done"))
            }
        }

        @Test
        fun illegalTransitionRejected() {
            // planned → completed 跳两步
            assertFailsWith<ConflictException> {
                service.update("CHE", m.objectId, UpdateMilestoneRequest(status = "completed"))
            }
        }

        @Test
        fun terminalMilestoneImmutable() {
            val doneM = m.copy(status = "completed")
            every { milestones.findByProjectObjectIdAndObjectId(UUID(0, 1), doneM.objectId) } returns doneM
            assertFailsWith<ConflictException> {
                service.update("CHE", doneM.objectId, UpdateMilestoneRequest(name = "M1x"))
            }
            assertFailsWith<ConflictException> {
                service.update("CHE", doneM.objectId, UpdateMilestoneRequest(targetDate = "2026-11-01"))
            }
            assertFailsWith<ConflictException> {
                service.update("CHE", doneM.objectId, UpdateMilestoneRequest(description = "x"))
            }
            assertFailsWith<ConflictException> {
                service.update("CHE", doneM.objectId, UpdateMilestoneRequest(owner = UUID(0, 7).toString()))
            }
        }

        @Test
        fun descriptionNullKeepsEmptyClears() {
            val withDesc = m.copy(description = "旧描述")
            every { milestones.findByProjectObjectIdAndObjectId(UUID(0, 1), withDesc.objectId) } returns withDesc
            every { milestones.save(any()) } answers { firstArg<Milestone>() }

            // null = 不动
            assertEquals("旧描述", service.update("CHE", withDesc.objectId, UpdateMilestoneRequest(name = "M1")).description)
            // 空串 = 清空
            assertEquals(null, service.update("CHE", withDesc.objectId, UpdateMilestoneRequest(description = "")).description)
        }

        @Test
        fun ownerNullKeepsEmptyClears() {
            val ownerId = UUID(0, 7)
            val withOwner = m.copy(ownerObjectId = ownerId)
            every { members.findByObjectId(ownerId) } returns Member(id = 7L, objectId = ownerId, workspaceObjectId = UUID(0, 9), username = "amy", passwordHash = null, displayName = "Amy")
            every { milestones.findByProjectObjectIdAndObjectId(UUID(0, 1), withOwner.objectId) } returns withOwner
            every { milestones.save(any()) } answers { firstArg<Milestone>() }

            // null = 不动
            assertEquals(ownerId, service.update("CHE", withOwner.objectId, UpdateMilestoneRequest(name = "M1")).owner)
            // 空串 = 清空
            assertEquals(null, service.update("CHE", withOwner.objectId, UpdateMilestoneRequest(owner = "")).owner)
        }

        @Test
        fun secondInProgressRejected() {
            val other = Milestone(id = 2L, objectId = UUID(0, 2), projectObjectId = UUID(0, 1), name = "M0", status = "in_progress")
            every { milestones.findByProjectObjectIdAndStatus(UUID(0, 1), "in_progress") } returns listOf(other)
            assertFailsWith<ConflictException> {
                service.update("CHE", m.objectId, UpdateMilestoneRequest(status = "in_progress"))
            }
        }

        @Test
        fun legalTransitionSaved() {
            every { milestones.findByProjectObjectIdAndStatus(UUID(0, 1), "in_progress") } returns emptyList()
            every { milestones.save(any()) } answers { firstArg<Milestone>() }
            val dto = service.update("CHE", m.objectId, UpdateMilestoneRequest(status = "in_progress"))
            assertEquals("in_progress", dto.status)
            verify { milestones.save(withArg<Milestone> { assertEquals("in_progress", it.status) }) }
        }
    }

    @Nested
    inner class DeleteMilestone {
        @Test
        fun terminalDeleteRejected() {
            val doneM = m.copy(status = "completed")
            every { milestones.findByProjectObjectIdAndObjectId(UUID(0, 1), doneM.objectId) } returns doneM
            assertFailsWith<ConflictException> { service.delete("CHE", doneM.objectId) }
        }

        @Test
        fun nonTerminalDeleted() {
            service.delete("CHE", m.objectId)
            verify { milestones.delete(m) }
        }
    }

    @Nested
    inner class FindMilestone {
        @Test
        fun missingRejected() {
            every { milestones.findByProjectObjectIdAndObjectId(UUID(0, 1), any()) } returns null
            assertFailsWith<NotFoundException> { service.update("CHE", UUID.randomUUID(), UpdateMilestoneRequest(name = "x")) }
        }
    }

    @Nested
    inner class GateGuard {
        private val activeStatus = Status(id = 1, projectObjectId = UUID(0, 1), name = "进行中", statusType = StatusType.ACTIVE)
        private val finalStatus = Status(id = 2, projectObjectId = UUID(0, 1), name = "完成", statusType = StatusType.FINAL)

        private fun gateM() = m.copy(status = "in_progress")

        private fun stubGate(
            item: Item,
            status: Status,
        ) {
            every { milestones.findByProjectObjectIdAndObjectId(UUID(0, 1), gateM().objectId) } returns gateM()
            every { milestones.save(any<Milestone>()) } answers { firstArg<Milestone>() }
            every { milestoneItems.findByMilestoneObjectId(gateM().objectId) } returns
                listOf(MilestoneItem(milestoneObjectId = gateM().objectId, itemObjectId = item.objectId))
            every { items.findAllByObjectIdIn(any()) } returns listOf(item)
            every { statuses.findAllByObjectIdIn(any()) } returns listOf(status)
        }

        @Test
        fun notFinalGateBlocksCompletion() {
            val item = Item(id = 1, objectId = UUID(0, 2), projectObjectId = UUID(0, 1), number = 12, title = "支付联调", statusObjectId = activeStatus.objectId)
            stubGate(item, activeStatus)
            val e = assertFailsWith<ConflictException> { service.update("CHE", gateM().objectId, UpdateMilestoneRequest(status = "completed")) }
            kotlin.test.assertTrue(e.message!!.contains("CHE-12 支付联调"), e.message)
        }

        @Test
        fun allFinalGatesPass() {
            val item = Item(id = 1, objectId = UUID(0, 2), projectObjectId = UUID(0, 1), number = 12, title = "回归通过", statusObjectId = finalStatus.objectId)
            stubGate(item, finalStatus)
            val dto = service.update("CHE", gateM().objectId, UpdateMilestoneRequest(status = "completed"))
            assertEquals("completed", dto.status)
        }

        @Test
        fun zeroGatePasses() {
            every { milestones.findByProjectObjectIdAndObjectId(UUID(0, 1), gateM().objectId) } returns gateM()
            every { milestones.save(any<Milestone>()) } answers { firstArg<Milestone>() }
            every { milestoneItems.findByMilestoneObjectId(gateM().objectId) } returns emptyList()
            assertEquals("completed", service.update("CHE", gateM().objectId, UpdateMilestoneRequest(status = "completed")).status)
        }

        @Test
        fun readinessOnList() {
            val m2 = Milestone(id = 2, objectId = UUID(0, 3), projectObjectId = UUID(0, 1), name = "M2")
            val active = Item(id = 1, objectId = UUID(0, 2), projectObjectId = UUID(0, 1), number = 12, title = "A", statusObjectId = activeStatus.objectId)
            val done = Item(id = 2, objectId = UUID(0, 4), projectObjectId = UUID(0, 1), number = 13, title = "B", statusObjectId = finalStatus.objectId)
            every { milestones.findByProjectObjectIdOrderByCreatedAt(UUID(0, 1)) } returns listOf(gateM(), m2)
            every { milestoneItems.findByMilestoneObjectId(gateM().objectId) } returns
                listOf(
                    MilestoneItem(milestoneObjectId = gateM().objectId, itemObjectId = active.objectId),
                    MilestoneItem(milestoneObjectId = gateM().objectId, itemObjectId = done.objectId),
                )
            every { milestoneItems.findByMilestoneObjectId(m2.objectId) } returns emptyList()
            every { items.findAllByObjectIdIn(any()) } returns listOf(active, done)
            every { statuses.findAllByObjectIdIn(any()) } returns listOf(activeStatus, finalStatus)
            val dtos = service.list("CHE")
            assertEquals(1, dtos[0].gateDone)
            assertEquals(2, dtos[0].gateTotal)
            assertEquals(null, dtos[1].gateDone)
            assertEquals(null, dtos[1].gateTotal)
        }
    }

    @Nested
    inner class NextPlannedOf {
        private fun ms(
            name: String,
            targetDate: LocalDate?,
            createdAt: Instant,
        ) = Milestone(id = null, objectId = UUID.randomUUID(), projectObjectId = UUID(0, 1), name = name, targetDate = targetDate, createdAt = createdAt)

        @Test
        fun earliestTargetDateWins() {
            val a = ms("A", LocalDate.parse("2026-03-02"), Instant.parse("2026-01-01T00:00:00Z"))
            val b = ms("B", LocalDate.parse("2026-03-01"), Instant.parse("2026-01-02T00:00:00Z"))
            assertEquals("B", nextPlannedOf(listOf(a, b))?.name)
        }

        @Test
        fun nullDateRanksLast() {
            val a = ms("A", null, Instant.parse("2026-01-01T00:00:00Z"))
            val b = ms("B", LocalDate.parse("2026-03-01"), Instant.parse("2026-01-02T00:00:00Z"))
            assertEquals("B", nextPlannedOf(listOf(a, b))?.name)
            assertEquals("A", nextPlannedOf(listOf(a))?.name)
        }

        @Test
        fun tieTakesEarlierCreatedAt() {
            val a = ms("A", LocalDate.parse("2026-03-01"), Instant.parse("2026-01-01T00:00:00Z"))
            val b = ms("B", LocalDate.parse("2026-03-01"), Instant.parse("2026-01-02T00:00:00Z"))
            assertEquals("A", nextPlannedOf(listOf(b, a))?.name)
        }

        @Test
        fun emptyListNull() {
            assertNull(nextPlannedOf(emptyList()))
        }
    }
}
