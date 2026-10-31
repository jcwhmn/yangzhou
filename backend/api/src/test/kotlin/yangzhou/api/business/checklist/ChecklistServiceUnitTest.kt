package yangzhou.api.business.checklist

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.ChecklistItem
import yangzhou.persistence.Item
import yangzhou.persistence.repository.ChecklistItemRepository
import yangzhou.persistence.repository.ItemRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-86 单测补齐:ChecklistService 全分支(position/trim/done 聚合)。 */
class ChecklistServiceUnitTest {
    private val checklists = mockk<ChecklistItemRepository>(relaxed = true)
    private val items = mockk<ItemRepository>(relaxed = true)
    private val service = ChecklistService(checklists, items)

    private val item =
        Item(
            id = 1L,
            objectId = UUID(0, 1),
            projectObjectId = UUID(0, 1),
            number = 1,
            title = "t",
            statusObjectId = UUID.randomUUID(),
        )

    private fun entry(
        id: Long?,
        text: String,
        done: Boolean = false,
        position: Int = 0,
    ) = ChecklistItem(id = id, itemObjectId = UUID(0, 1), text = text, done = done, position = position)

    @BeforeEach
    fun setup() {
        clearMocks(checklists, items)
        every { items.findByObjectId(item.objectId) } returns item
    }

    @Nested
    inner class Get {
        @Test
        fun countsDoneAndTotal() {
            every { checklists.findByItemObjectIdOrderByPositionAscIdAsc(UUID(0, 1)) } returns
                listOf(
                    entry(1L, "a", done = true),
                    entry(2L, "b"),
                )
            val dto = service.list(item.objectId)
            assertEquals(1, dto.doneCount)
            assertEquals(2, dto.totalCount)
        }

        @Test
        fun missingItemRejected() {
            every { items.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.list(UUID.randomUUID()) }
        }
    }

    @Nested
    inner class Add {
        @Test
        fun appendsAtNextPositionAndTrims() {
            every { checklists.findByItemObjectIdOrderByPositionAscIdAsc(UUID(0, 1)) } returns listOf(entry(1L, "a", position = 0))
            every { checklists.save(any()) } answers { firstArg() }
            val dto = service.add(item.objectId, "  新条目  ")
            assertEquals("新条目", dto.text)
            io.mockk.verify { checklists.save(withArg { assertEquals(1, it.position) }) }
        }

        @Test
        fun firstEntryAtPositionZero() {
            every { checklists.findByItemObjectIdOrderByPositionAscIdAsc(UUID(0, 1)) } returns emptyList()
            every { checklists.save(any()) } answers { firstArg() }
            service.add(item.objectId, "x")
            io.mockk.verify { checklists.save(withArg { assertEquals(0, it.position) }) }
        }
    }

    @Nested
    inner class Patch {
        @Test
        fun toggleDoneKeepsText() {
            val e = entry(1L, "a")
            every { checklists.findByObjectId(e.objectId) } returns e
            every { checklists.save(any()) } answers { firstArg() }
            val dto = service.update(e.objectId, text = null, done = true)
            assertEquals("a", dto.text)
            assertEquals(true, dto.done)
        }

        @Test
        fun blankTextRejected() {
            val e = entry(1L, "a")
            every { checklists.findByObjectId(e.objectId) } returns e
            assertFailsWith<BadRequestException> { service.update(e.objectId, text = "   ", done = null) }
        }

        @Test
        fun missingEntryRejected() {
            every { checklists.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.update(UUID.randomUUID(), "x", null) }
        }
    }

    @Nested
    inner class Remove {
        @Test
        fun missingEntryRejected() {
            every { checklists.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.remove(UUID.randomUUID()) }
        }

        @Test
        fun entryDeleted() {
            val e = entry(1L, "a")
            every { checklists.findByObjectId(e.objectId) } returns e
            service.remove(e.objectId)
            verify { checklists.delete(e) }
        }
    }
}
