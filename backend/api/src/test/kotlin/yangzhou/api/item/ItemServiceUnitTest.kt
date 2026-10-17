package yangzhou.api.item

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import yangzhou.api.support.BadRequestException
import yangzhou.api.support.ConflictException
import yangzhou.api.support.NotFoundException
import yangzhou.persistence.Item
import yangzhou.persistence.ItemActivity
import yangzhou.persistence.ItemGroup
import yangzhou.persistence.ItemGroupMember
import yangzhou.persistence.ItemStatusHistory
import yangzhou.persistence.Member
import yangzhou.persistence.Project
import yangzhou.persistence.Status
import yangzhou.persistence.repository.AttributeDefinitionRepository
import yangzhou.persistence.repository.ItemGitRefRepository
import yangzhou.persistence.repository.ItemNumberRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ItemTreeRepository
import yangzhou.persistence.repository.ItemActivityRepository
import yangzhou.persistence.repository.ItemStatusHistoryRepository
import yangzhou.persistence.repository.ItemGroupRepository
import yangzhou.persistence.repository.ItemGroupMemberRepository
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.RequirementRepository
import yangzhou.persistence.repository.StatusRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * YPJ-43 mockk 样板:ItemService 全方法单测(nested class 按方法分组)。
 * 直实例化 + 协作者 mockk(relaxed),不起 Spring 不碰 DB。
 * @BeforeEach clearMocks 后重铺公共 stub,用例间零残留;泛型 save 坑见 mockk 计划文档 P0 记录。
 */
class ItemServiceUnitTest {

    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val itemRepo = mockk<ItemRepository>(relaxed = true)
    private val statuses = mockk<StatusRepository>(relaxed = true)
    private val numbering = mockk<ItemNumberRepository>(relaxed = true)
    private val memberService = mockk<yangzhou.api.member.MemberService>(relaxed = true)
    private val historyRepo = mockk<ItemStatusHistoryRepository>(relaxed = true)
    private val activityRepo = mockk<ItemActivityRepository>(relaxed = true)
    private val workflow = mockk<yangzhou.api.workflow.WorkflowService>(relaxed = true)
    private val projectMembers = mockk<yangzhou.api.projectmember.ProjectMemberService>(relaxed = true)
    private val notificationService = mockk<yangzhou.api.notification.NotificationService>(relaxed = true)
    private val feasibilityService = mockk<yangzhou.api.feasibility.FeasibilityService>(relaxed = true)
    private val itemTree = mockk<ItemTreeRepository>(relaxed = true)
    private val requirementRepo = mockk<RequirementRepository>(relaxed = true)
    private val definitions = mockk<AttributeDefinitionRepository>(relaxed = true)
    private val membersRepo = mockk<MemberRepository>(relaxed = true)
    private val groupRepo = mockk<ItemGroupRepository>(relaxed = true)
    private val groupMembers = mockk<ItemGroupMemberRepository>(relaxed = true)

    private val allMocks = arrayOf(
        projects, itemRepo, statuses, numbering, memberService, historyRepo, activityRepo,
        workflow, projectMembers, notificationService, feasibilityService, itemTree,
        requirementRepo, definitions, membersRepo, groupRepo, groupMembers,
    )

    private val service = ItemService(
        projects = projects,
        itemRepo = itemRepo,
        statuses = statuses,
        requirementRepo = requirementRepo,
        definitions = definitions,
        numbering = numbering,
        itemTree = itemTree,
        workflow = workflow,
        members = membersRepo,
        memberService = memberService,
        projectMembers = projectMembers,
        activityRepo = activityRepo,
        historyRepo = historyRepo,
        gitRefs = mockk(relaxed = true),
        feasibilityService = feasibilityService,
        dependencyService = mockk(relaxed = true),
        groups = groupRepo,
        memberships = groupMembers,
        notificationService = notificationService,
        groupRepo = groupRepo,
        groupMembers = groupMembers,
        memberHistory = mockk(relaxed = true),
        clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
    )

    private val projectId = 1L
    private val workspaceId = 10L
    private val project = Project(id = projectId, workspaceId = workspaceId, key = "CHE", name = "CHE 项目")
    private val startStatus = Status(id = 100, projectId = projectId, name = "To Do", isStart = true)
    private val doneStatus = Status(id = 200, projectId = projectId, name = "Done", isFinal = true, position = 9)
    private val me = Member(id = 5, workspaceId = workspaceId, username = "me", passwordHash = null, displayName = "me")
    private val itemOid = UUID.randomUUID()

    /** 单行「数据库」:save 原地更新,findByObjectId / 列表查询都读它 */
    private var row: Item = newItem()

    private fun newItem() = Item(
        id = 999,
        objectId = itemOid,
        projectId = projectId,
        number = 37,
        title = "新 item",
        type = "task",
        statusObjectId = startStatus.objectId,
        createdBy = me.id,
    )

    private fun create(
        title: String = "新 item",
        type: String = "task",
        parentItemId: UUID? = null,
        statusItemId: UUID? = null,
        requirements: List<ItemService.RequirementDto> = emptyList(),
    ) = service.create("CHE", title, null, type, parentItemId, statusItemId, requirements)

    @BeforeEach
    fun reset() {
        allMocks.forEach { clearMocks(it, answers = true, recordedCalls = true, childMocks = true) }
        row = newItem()
        // 公共 stub:单行「数据库」读写一致 + 项目/成员解析 + 泛型 save 回传实参并补 id(真库由 Spring Data 生成;坑见计划文档)
        every { itemRepo.save(any()) } answers {
            val arg: Item = firstArg()
            row = arg.copy(id = row.id ?: 999, objectId = row.objectId) // 保留行身份:objectId/id 由「库」持有,不随 save 实参变
            row
        }
        every { itemRepo.findByObjectId(itemOid) } answers { row }
        every { itemRepo.findByProjectIdAndDeletedAtIsNullOrderByNumber(projectId) } answers {
            if (row.deletedAt == null) listOf(row) else emptyList()
        }
        every { projects.findByKey("CHE") } returns project
        every { projects.findAll() } returns listOf(project)
        every { statuses.findByProjectIdOrderByPosition(projectId) } returns listOf(startStatus, doneStatus)
        every { memberService.current() } returns me
        every { activityRepo.save(any()) } answers { firstArg() }
        every { historyRepo.save(any()) } answers { firstArg() }
        every { requirementRepo.save(any()) } answers { firstArg() }
        every { requirementRepo.findByItemIdIn(any()) } returns emptyList()
        every { groupMembers.findByItemIdIn(any()) } returns emptyList()
    }

    // ---------- create ----------

    @Nested
    inner class Create {

        @Test
        fun `主路径——取号进起始态写状态历史 source=created`() {
            every { numbering.nextNumber(projectId) } returns 37

            val dto = create()

            assertEquals("CHE-37", dto.number)
            assertEquals("To Do", dto.status)
            assertEquals("task", dto.type)

            val history = slot<ItemStatusHistory>()
            verify { historyRepo.save(capture(history)) }
            assertEquals("created", history.captured.source)
            assertEquals(startStatus.id, history.captured.toStatusId)
            assertEquals(me.id, history.captured.actorMemberId)
        }

        @Test
        fun `type 非法——400`() {
            val e = assertFailsWith<BadRequestException> { create(type = "epic") }
            assertEquals("type 非法:epic", e.message)
        }

        @Test
        fun `项目不存在——404`() {
            every { projects.findByKey("NOPE") } returns null
            assertFailsWith<NotFoundException> { service.create("NOPE", "x", null, "task", null, null, emptyList()) }
        }

        @Test
        fun `statusItemId 不属于该项目——400`() {
            every { statuses.findByProjectIdAndObjectId(projectId, any()) } returns null
            val e = assertFailsWith<BadRequestException> { create(statusItemId = UUID.randomUUID()) }
            assertEquals("状态不属于该项目", e.message)
        }

        @Test
        fun `父 item 不属于该项目——400`() {
            val other = newItem().copy(objectId = UUID.randomUUID(), projectId = 2)
            every { itemRepo.findByObjectId(other.objectId) } returns other
            val e = assertFailsWith<BadRequestException> { create(parentItemId = other.objectId) }
            assertEquals("父 item 不属于该项目", e.message)
        }

        @Test
        fun `父 item 不存在——400`() {
            every { itemRepo.findByObjectId(any()) } returns null
            val e = assertFailsWith<BadRequestException> { create(parentItemId = UUID.randomUUID()) }
            assertEquals("父 item 不存在", e.message)
        }

        @Test
        fun `需求属性不在词表——400`() {
            every { definitions.findByWorkspaceId(workspaceId) } returns emptyList()
            val e = assertFailsWith<BadRequestException> {
                create(requirements = listOf(ItemService.RequirementDto("Kotlin", 2)))
            }
            assertTrue(e.message!!.startsWith("词表中没有属性:Kotlin"))
        }
    }

    // ---------- list ----------

    @Nested
    inner class ListItems {

        private fun stubRows(vararg items: Item) {
            every { itemRepo.findByProjectIdAndDeletedAtIsNullOrderByNumber(projectId) } returns items.toList()
        }

        @Test
        fun `全量——含终态,组装编号与状态名`() {
            val finalItem = newItem().copy(id = 998, objectId = UUID.randomUUID(), number = 36, statusObjectId = doneStatus.objectId)
            stubRows(row, finalItem)

            val dtos = service.list("CHE")

            assertEquals(2, dtos.size)
            assertEquals("CHE-37", dtos[0].number)
            assertEquals("To Do", dtos[0].status)
            assertEquals("Done", dtos[1].status)
        }

        @Test
        fun `backlogOnly——滤终态,滤已入 active sprint 的 item`() {
            val finalItem = newItem().copy(id = 998, objectId = UUID.randomUUID(), number = 36, statusObjectId = doneStatus.objectId)
            val inSprint = newItem().copy(id = 997, objectId = UUID.randomUUID(), number = 38)
            val freeItem = newItem().copy(id = 996, objectId = UUID.randomUUID(), number = 39, title = "空闲")
            stubRows(row, finalItem, inSprint, freeItem)
            val sprint = ItemGroup(id = 7, projectId = projectId, name = "Sprint 42", type = "sprint", status = "active")
            every { groupMembers.findByItemIdIn(any()) } returns listOf(ItemGroupMember(groupId = 7, itemId = 997))
            every { groupRepo.findAllById(setOf(7L)) } returns listOf(sprint)

            val dtos = service.list("CHE", backlogOnly = true)

            assertEquals(listOf("CHE-37", "CHE-39"), dtos.map { it.number }) // 终态 + in-sprint 均被滤
        }

        @Test
        fun `项目不存在——404`() {
            every { projects.findByKey("NOPE") } returns null
            assertFailsWith<NotFoundException> { service.list("NOPE") }
        }
    }

    // ---------- get ----------

    @Nested
    inner class Get {

        @Test
        fun `在册 item——走 list 组装,gitRefs 附加`() {
            val dto = service.get(itemOid)

            assertEquals("CHE-37", dto.number)
            assertEquals("To Do", dto.status)
        }

        @Test
        fun `软删 item——列表不可见,状态前缀「已删除」`() {
            row = row.copy(deletedAt = Instant.parse("2026-10-15T00:00:00Z"))

            val dto = service.get(itemOid)

            assertTrue(dto.status.startsWith("[已删除]"))
            assertEquals("CHE-37", dto.number)
        }

        @Test
        fun `不存在——404`() {
            every { itemRepo.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.get(UUID.randomUUID()) }
        }
    }

    // ---------- update ----------

    @Nested
    inner class Update {

        private val targetStatus = Status(id = 150, projectId = projectId, name = "In Progress", position = 2)

        @Test
        fun `改 title——save 新值并留 title_changed 活动`() {
            service.update(itemOid, title = "改名", description = null, type = null, statusItemId = null, parentItemId = null)

            assertEquals("改名", row.title)
            val act = slot<ItemActivity>()
            verify { activityRepo.save(capture(act)) }
            assertEquals("title_changed", act.captured.kind)
        }

        @Test
        fun `状态迁移——过迁移守卫,写 manual 历史 + 通知`() {
            every { statuses.findByProjectIdAndObjectId(projectId, targetStatus.objectId) } returns targetStatus
            every { statuses.findByProjectIdOrderByPosition(projectId) } returns listOf(startStatus, targetStatus, doneStatus)
            row = row.copy(assigneeObjectId = me.objectId) // 已有主,过开工守卫

            val dto = service.update(itemOid, title = null, description = null, type = null, statusItemId = targetStatus.objectId, parentItemId = null)

            assertEquals("In Progress", dto.status)
            verify { workflow.assertTransitionAllowed(projectId, startStatus.id!!, targetStatus.id!!) }
            val history = slot<ItemStatusHistory>()
            verify { historyRepo.save(capture(history)) }
            assertEquals("manual", history.captured.source)
            assertEquals(startStatus.id, history.captured.fromStatusId)
            verify { notificationService.notifyStatusChange(any(), me.id!!, "To Do", "In Progress") }
        }

        @Test
        fun `开工须有主——未指派离开起点态 409`() {
            every { statuses.findByProjectIdAndObjectId(projectId, targetStatus.objectId) } returns targetStatus

            val e = assertFailsWith<ConflictException> {
                service.update(itemOid, title = null, description = null, type = null, statusItemId = targetStatus.objectId, parentItemId = null)
            }
            assertEquals("开工前请先指派负责人", e.message)
        }

        @Test
        fun `WIP 已满——409`() {
            val wipStatus = targetStatus.copy(wipLimit = 1)
            every { statuses.findByProjectIdAndObjectId(projectId, wipStatus.objectId) } returns wipStatus
            row = row.copy(assigneeObjectId = me.objectId)
            every { itemRepo.findByProjectIdAndStatusObjectId(projectId, wipStatus.objectId) } returns listOf(row, row.copy(id = 1)) // 已有 2 ≥ 1

            val e = assertFailsWith<ConflictException> {
                service.update(itemOid, title = null, description = null, type = null, statusItemId = wipStatus.objectId, parentItemId = null)
            }
            assertTrue(e.message!!.startsWith("该列 WIP 已满"))
        }

        @Test
        fun `父 item 不属于该项目——400`() {
            val other = newItem().copy(objectId = UUID.randomUUID(), projectId = 2)
            every { itemRepo.findByObjectId(other.objectId) } returns other

            val e = assertFailsWith<BadRequestException> {
                service.update(itemOid, title = null, description = null, type = null, statusItemId = null, parentItemId = other.objectId)
            }
            assertEquals("父 item 不属于该项目", e.message)
        }

        @Test
        fun `type 非法——400`() {
            val e = assertFailsWith<BadRequestException> {
                service.update(itemOid, title = null, description = null, type = "epic", statusItemId = null, parentItemId = null)
            }
            assertEquals("type 非法:epic", e.message)
        }

        @Test
        fun `priority 非法——400`() {
            val e = assertFailsWith<BadRequestException> {
                service.update(itemOid, title = null, description = null, type = null, statusItemId = null, parentItemId = null, priorityText = "P9")
            }
            assertTrue(e.message!!.startsWith("priority 只能是 P0–P3"))
        }

        @Test
        fun `开始晚于截止——400`() {
            val e = assertFailsWith<BadRequestException> {
                service.update(
                    itemOid, title = null, description = null, type = null, statusItemId = null, parentItemId = null,
                    startDateText = "2026-11-01", dueDateText = "2026-10-01",
                )
            }
            assertEquals("开始日期不能晚于截止日期", e.message)
        }

        @Test
        fun `item 不存在——404`() {
            every { itemRepo.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> {
                service.update(UUID.randomUUID(), title = null, description = null, type = null, statusItemId = null, parentItemId = null)
            }
        }
    }

    // ---------- replaceRequirements ----------

    @Nested
    inner class ReplaceRequirements {

        @Test
        fun `整表替换——先删后建,留 requirement_changed 活动,触发重算`() {
            val def = yangzhou.persistence.AttributeDefinition(
                id = 55, objectId = UUID.randomUUID(), workspaceId = workspaceId, name = "Kotlin", kind = "skill",
            )
            every { definitions.findByWorkspaceId(workspaceId) } returns listOf(def)

            val dto = service.replaceRequirements(itemOid, listOf(ItemService.RequirementDto("Kotlin", 2)))

            verify { requirementRepo.deleteByItemId(999) }
            verify { requirementRepo.save(match { it.attributeDefinitionId == 55L && it.minLevel == 2 }) }
            val act = slot<ItemActivity>()
            verify { activityRepo.save(capture(act)) }
            assertEquals("requirement_changed", act.captured.kind)
            assertEquals("Kotlin>=2", act.captured.newValue)
            verify { feasibilityService.recomputeProjectSignal(projectId) }
            assertEquals("CHE-37", dto.number)
        }

        @Test
        fun `清空——活动 newValue 为(清空)`() {
            every { definitions.findByWorkspaceId(workspaceId) } returns emptyList()

            service.replaceRequirements(itemOid, emptyList())

            val act = slot<ItemActivity>()
            verify { activityRepo.save(capture(act)) }
            assertEquals("(清空)", act.captured.newValue)
        }

        @Test
        fun `属性不在词表——400`() {
            every { definitions.findByWorkspaceId(workspaceId) } returns emptyList()
            val e = assertFailsWith<BadRequestException> {
                service.replaceRequirements(itemOid, listOf(ItemService.RequirementDto("Go", 1)))
            }
            assertTrue(e.message!!.startsWith("词表中没有属性:Go"))
        }
    }

    // ---------- assign ----------

    @Nested
    inner class Assign {

        private val assignee = Member(id = 7, workspaceId = workspaceId, username = null, passwordHash = null, displayName = "小王")

        @Test
        fun `指派——写 assigned 活动并通知`() {
            every { membersRepo.findByObjectId(assignee.objectId) } returns assignee

            service.assign(itemOid, assignee.objectId)

            assertEquals(assignee.objectId, row.assigneeObjectId)
            val act = slot<ItemActivity>()
            verify { activityRepo.save(capture(act)) }
            assertEquals("assigned", act.captured.kind)
            assertEquals("小王", act.captured.newValue)
            verify { notificationService.notifyAssigned(any(), me.id!!, 7L, "小王") }
        }

        @Test
        fun `取消指派——unassigned 活动`() {
            row = row.copy(assigneeObjectId = assignee.objectId)
            every { membersRepo.findByObjectId(assignee.objectId) } returns assignee

            service.assign(itemOid, null)

            assertEquals(null, row.assigneeObjectId)
            val act = slot<ItemActivity>()
            verify { activityRepo.save(capture(act)) }
            assertEquals("unassigned", act.captured.kind)
            assertEquals("小王", act.captured.oldValue)
        }

        @Test
        fun `成员不存在——404`() {
            every { membersRepo.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.assign(itemOid, UUID.randomUUID()) }
        }

        @Test
        fun `池外指派——透传 409`() {
            every { membersRepo.findByObjectId(assignee.objectId) } returns assignee
            every { projectMembers.assertAssignable(projectId, 7L) } throws ConflictException("不在指派池")

            assertFailsWith<ConflictException> { service.assign(itemOid, assignee.objectId) }
        }
    }

    // ---------- delete / restore / purge / recycleBin ----------

    @Nested
    inner class Delete {

        @Test
        fun `软删——子树 CTE 更新 + 信号重算`() {
            service.delete(itemOid)

            verify { feasibilityService.recomputeProjectSignal(projectId) }
            val rootId = slot<Long>()
            val at = slot<Instant?>()
            verify { itemTree.updateSubtreeDeletedAt(capture(rootId), captureNullable(at)) }
            assertEquals(row.id, rootId.captured)
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"), at.captured) // 固定 Clock
        }

        @Test
        fun `item 不存在——404`() {
            every { itemRepo.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.delete(UUID.randomUUID()) }
        }
    }

    @Nested
    inner class Restore {

        @Test
        fun `恢复——子树置 deleted_at 为 null`() {
            row = row.copy(deletedAt = Instant.parse("2026-10-15T00:00:00Z"))

            service.restore(itemOid)

            val rootId = slot<Long>()
            val at = slot<Instant?>()
            verify { itemTree.updateSubtreeDeletedAt(capture(rootId), captureNullable(at)) }
            assertEquals(row.id, rootId.captured)
            assertNull(at.captured)
        }

        @Test
        fun `不在回收站——409`() {
            val e = assertFailsWith<ConflictException> { service.restore(itemOid) }
            assertEquals("该 item 不在回收站", e.message)
        }
    }

    @Nested
    inner class Purge {

        @Test
        fun `物理删——需求与 item 子树两条 DELETE + 信号重算`() {
            row = row.copy(deletedAt = Instant.parse("2026-10-15T00:00:00Z"))

            service.purge(itemOid)

            verify(exactly = 1) { itemTree.purgeRequirementsInSubtree(row.id!!) }
            verify(exactly = 1) { itemTree.purgeItemsInSubtree(row.id!!) }
            verify { feasibilityService.recomputeProjectSignal(projectId) }
        }

        @Test
        fun `不在回收站——409`() {
            val e = assertFailsWith<ConflictException> { service.purge(itemOid) }
            assertEquals("该 item 不在回收站", e.message)
        }
    }

    @Nested
    inner class RecycleBin {

        @Test
        fun `只列子树根——父已删的子条目不单列,组装项目名`() {
            val root = newItem().copy(deletedAt = Instant.parse("2026-10-15T00:00:00Z"))
            val child = newItem().copy(id = 998, objectId = UUID.randomUUID(), number = 38, parentObjectId = root.objectId, deletedAt = root.deletedAt)
            val orphan = newItem().copy(id = 997, objectId = UUID.randomUUID(), number = 39, parentObjectId = null, deletedAt = root.deletedAt)
            every { itemRepo.findByObjectId(itemOid) } returns root // child 的父(已删)
            every { itemRepo.findByDeletedAtIsNotNullOrderByDeletedAtDesc() } returns listOf(root, child, orphan)

            val dtos = service.recycleBin()

            // root(parent=null)与 orphan(parent=null)是根;child 父已删 → 随父不单列
            assertEquals(2, dtos.size)
            assertEquals("CHE-37", dtos[0].number)
            assertEquals("CHE-39", dtos[1].number)
            assertEquals("CHE 项目", dtos[0].projectName)
        }
    }

    // ---------- activity ----------

    @Nested
    inner class Activity {

        @Test
        fun `倒序返回活动`() {
            val acts = listOf(
                ItemActivity(itemId = 999, kind = "created", actorMemberId = 5),
                ItemActivity(itemId = 999, kind = "title_changed", actorMemberId = 5),
            )
            every { activityRepo.findByItemIdOrderByCreatedAtDesc(999) } returns acts

            assertEquals(2, service.activity(itemOid).size)
            verify { activityRepo.findByItemIdOrderByCreatedAtDesc(999) }
        }

        @Test
        fun `item 不存在——404`() {
            every { itemRepo.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.activity(UUID.randomUUID()) }
        }
    }
}
