package yangzhou.api.business.dependency

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Item
import yangzhou.persistence.ItemDependency
import yangzhou.persistence.Project
import yangzhou.persistence.Status
import yangzhou.persistence.repository.ItemDependencyRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.StatusRepository
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** YPJ-86 单测补齐:DependencyService 全分支(含成环 BFS 与 blocked 聚合)。 */
class DependencyServiceUnitTest {

    private val dependencies = mockk<ItemDependencyRepository>(relaxed = true)
    private val items = mockk<ItemRepository>(relaxed = true)
    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val statuses = mockk<StatusRepository>(relaxed = true)
    private val service = DependencyService(dependencies, items, projects, statuses)

    private val projA = 1L
    private val projB = 2L
    private val projectA = Project(id = projA, workspaceId = 9L, key = "CHE", name = "CHE")

    private fun item(id: Long?, number: Int, project: Long = projA, status: Status) = Item(
        id = id,
        projectId = project,
        number = number,
        title = "item-$number",
        statusObjectId = status.objectId,
    )

    private fun status(name: String, final: Boolean = false, position: Int = 0) = Status(
        projectId = projA, name = name, isFinal = final, position = position,
    )

    @BeforeEach
    fun setup() {
        clearMocks(dependencies, items, projects, statuses)
        every { projects.findById(any()) } returns java.util.Optional.of(projectA)
    }

    @Nested
    inner class Add {

        @Test
        fun selfDependencyRejected() {
            val id = UUID.randomUUID()
            assertFailsWith<BadRequestException> { service.add(id, id) }
        }

        @Test
        fun missingItemRejected() {
            every { items.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.add(UUID.randomUUID(), UUID.randomUUID()) }
        }

        @Test
        fun missingTargetRejected() {
            val a = item(1L, 1, status = status("To Do"))
            every { items.findByObjectId(any()) } returns null
            every { items.findByObjectId(a.objectId) } returns a
            assertFailsWith<BadRequestException> { service.add(a.objectId, UUID.randomUUID()) }
        }

        @Test
        fun crossProjectRejected() {
            val a = item(1L, 1, status = status("To Do"))
            val b = item(2L, 2, project = projB, status = status("To Do"))
            every { items.findByObjectId(a.objectId) } returns a
            every { items.findByObjectId(b.objectId) } returns b
            assertFailsWith<BadRequestException> { service.add(a.objectId, b.objectId) }
        }

        @Test
        fun duplicateRejected() {
            val a = item(1L, 1, status = status("To Do"))
            val b = item(2L, 2, status = status("To Do"))
            every { items.findByObjectId(a.objectId) } returns a
            every { items.findByObjectId(b.objectId) } returns b
            every { dependencies.existsByItemIdAndDependsOnItemId(1L, 2L) } returns true
            assertFailsWith<ConflictException> { service.add(a.objectId, b.objectId) }
        }

        @Test
        fun cycleRejected() {
            // 已有 b→a;再加 a→b 成环
            val a = item(1L, 1, status = status("To Do"))
            val b = item(2L, 2, status = status("To Do"))
            every { items.findByObjectId(a.objectId) } returns a
            every { items.findByObjectId(b.objectId) } returns b
            every { dependencies.existsByItemIdAndDependsOnItemId(1L, 2L) } returns false
            every { dependencies.findByDependsOnItemId(1L) } returns listOf(ItemDependency(itemId = 2L, dependsOnItemId = 1L))
            assertFailsWith<BadRequestException> { service.add(a.objectId, b.objectId) }
        }

        @Test
        fun acyclicSavedWithTargetFields() {
            val open = status("To Do")
            val a = item(1L, 1, status = open)
            val b = item(2L, 2, status = open)
            every { items.findByObjectId(a.objectId) } returns a
            every { items.findByObjectId(b.objectId) } returns b
            every { dependencies.existsByItemIdAndDependsOnItemId(1L, 2L) } returns false
            every { dependencies.findByDependsOnItemId(1L) } returns emptyList()
            every { dependencies.save(any()) } answers { firstArg() }
            every { statuses.findByProjectIdOrderByPosition(projA) } returns listOf(open)
            val dto = service.add(a.objectId, b.objectId)
            assertEquals("CHE-2", dto.number)
            assertEquals("To Do", dto.statusName)
            assertFalse(dto.final)
        }
    }

    @Nested
    inner class ListRefs {

        @Test
        fun blockedAggregatesUnfinishedDeps() {
            val open = status("To Do")
            val done = status("Done", final = true)
            val a = item(1L, 1, status = open)
            every { items.findByObjectId(a.objectId) } returns a
            every { statuses.findByProjectIdOrderByPosition(projA) } returns listOf(open, done)
            every { dependencies.findByItemId(1L) } returns listOf(
                ItemDependency(itemId = 1L, dependsOnItemId = 2L),   // 未终态 → 阻塞
                ItemDependency(itemId = 1L, dependsOnItemId = 3L),   // 终态 → 不阻塞
            )
            every { items.findById(2L) } returns java.util.Optional.of(item(2L, 2, status = open))
            every { items.findById(3L) } returns java.util.Optional.of(item(3L, 3, status = done))
            val result = service.list(a.objectId)
            assertTrue(result.blocked)
            assertEquals(2, result.dependencies.size)
            assertEquals(listOf(false, true), result.dependencies.map { it.final })
        }

        @Test
        fun missingItemRejected() {
            every { items.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.list(UUID.randomUUID()) }
        }
    }

    @Nested
    inner class Remove {

        @Test
        fun missingRowRejected() {
            every { dependencies.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.remove(UUID.randomUUID()) }
        }

        @Test
        fun rowDeleted() {
            val row = ItemDependency(itemId = 1L, dependsOnItemId = 2L)
            every { dependencies.findByObjectId(row.objectId) } returns row
            service.remove(row.objectId)
            io.mockk.verify { dependencies.delete(row) }
        }
    }

    @Nested
    inner class BlockedItemIds {

        @Test
        fun unfinishedTargetBlocksDependent() {
            val open = status("To Do")
            val done = status("Done", final = true)
            val a = item(1L, 1, status = open)
            val b = item(2L, 2, status = done)
            val c = item(3L, 3, status = open)
            every { items.findByProjectIdAndDeletedAtIsNullOrderByNumber(projA) } returns listOf(a, b, c)
            every { statuses.findByProjectIdOrderByPosition(projA) } returns listOf(open, done)
            // a 依赖未终态 c → a 阻塞;b 依赖未终态 target 但 target 不在项目 → 不计;a 依赖 b(终态)→ 不阻塞
            every { dependencies.findByItemIdIn(listOf(1L, 2L, 3L)) } returns listOf(
                ItemDependency(itemId = 1L, dependsOnItemId = 3L),
                ItemDependency(itemId = 2L, dependsOnItemId = 99L),
                ItemDependency(itemId = 1L, dependsOnItemId = 2L),
            )
            assertEquals(setOf(1L), service.blockedItemIds(projA))
        }
    }
}
