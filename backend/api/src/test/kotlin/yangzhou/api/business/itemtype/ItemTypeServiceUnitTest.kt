package yangzhou.api.business.itemtype

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.ForbiddenException
import yangzhou.api.error.NotFoundException
import yangzhou.api.guard.LeaderGuard
import yangzhou.persistence.ItemType
import yangzhou.persistence.Project
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ItemTypeRepository
import yangzhou.persistence.repository.ProjectRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-177(spec 0123):ItemTypeService 单测——slug 校验/重复 409/builtin 删 409/在用删 409/patch 仅 name/leader 门。 */
class ItemTypeServiceUnitTest {
    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val itemTypes = mockk<ItemTypeRepository>(relaxed = true)
    private val items = mockk<ItemRepository>(relaxed = true)
    private val leaderGuard = mockk<LeaderGuard>(relaxed = true)
    private val service = ItemTypeService(projects, itemTypes, items, leaderGuard)

    private val pid = UUID(0, 1)
    private val project = Project(id = 1, objectId = pid, workspaceObjectId = UUID(0, 9), key = "CHE", name = "CHE")

    @BeforeEach
    fun setup() {
        every { projects.findByKey("CHE") } returns project
    }

    private fun t(key: String) = ItemType(id = null, objectId = UUID(0, 2), projectObjectId = pid, typeKey = key, name = key)

    @Nested
    inner class CreateItemType {
        @Test
        fun blankKeyRejected() {
            assertFailsWith<BadRequestException> { service.create("CHE", ItemTypeService.CreateItemTypeRequest("  ", "X")) }
        }

        @Test
        fun badSlugRejected() {
            assertFailsWith<BadRequestException> { service.create("CHE", ItemTypeService.CreateItemTypeRequest("EPIC", "X")) }
            assertFailsWith<BadRequestException> { service.create("CHE", ItemTypeService.CreateItemTypeRequest("1feat", "X")) }
            assertFailsWith<BadRequestException> { service.create("CHE", ItemTypeService.CreateItemTypeRequest("中文", "X")) }
        }

        @Test
        fun blankNameRejected() {
            assertFailsWith<BadRequestException> { service.create("CHE", ItemTypeService.CreateItemTypeRequest("feat", " ")) }
        }

        @Test
        fun duplicateRejected() {
            every { itemTypes.existsByProjectObjectIdAndTypeKey(pid, "feat") } returns true
            val e = assertFailsWith<ConflictException> { service.create("CHE", ItemTypeService.CreateItemTypeRequest("feat", "Feature")) }
            assertEquals("item 类型已存在:feat", e.message)
        }

        @Test
        fun savedAndReturned() {
            every { itemTypes.save(any()) } answers { firstArg<ItemType>() }
            val dto = service.create("CHE", ItemTypeService.CreateItemTypeRequest("feat", " Feature "))
            assertEquals("feat", dto.typeKey)
            assertEquals("Feature", dto.name)
        }

        @Test
        fun nonLeaderRejected() {
            every { leaderGuard.requireLeader("CHE") } throws ForbiddenException("需要项目 leader 权限")
            assertFailsWith<ForbiddenException> { service.create("CHE", ItemTypeService.CreateItemTypeRequest("feat", "Feature")) }
        }
    }

    @Nested
    inner class UpdateItemType {
        @Test
        fun missingRejected() {
            every { itemTypes.findByProjectObjectIdAndTypeKey(pid, "feat") } returns null
            assertFailsWith<NotFoundException> { service.update("CHE", "feat", ItemTypeService.UpdateItemTypeRequest("Feature")) }
        }

        @Test
        fun renamesOnly() {
            every { itemTypes.findByProjectObjectIdAndTypeKey(pid, "feat") } returns t("feat")
            every { itemTypes.save(any()) } answers { firstArg<ItemType>() }
            val dto = service.update("CHE", "feat", ItemTypeService.UpdateItemTypeRequest("特性"))
            assertEquals("特性", dto.name)
            assertEquals("feat", dto.typeKey)
        }
    }

    @Nested
    inner class DeleteItemType {
        @Test
        fun inUseRejected() {
            every { itemTypes.findByProjectObjectIdAndTypeKey(pid, "feat") } returns t("feat")
            every { items.countByProjectObjectIdAndType(pid, "feat") } returns 3
            val e = assertFailsWith<ConflictException> { service.delete("CHE", "feat") }
            assertEquals("类型下仍有 3 个 item,请先迁移:feat", e.message)
        }

        @Test
        fun emptyDeleted_includingSeedPlaceholder() {
            // 修订(2026-11-14):builtin 概念移除——种子只是初始化占位,空即可删
            val del = t("goal")
            every { itemTypes.findByProjectObjectIdAndTypeKey(pid, "goal") } returns del
            every { items.countByProjectObjectIdAndType(pid, "goal") } returns 0
            service.delete("CHE", "goal")
            verify { itemTypes.delete(del) }
        }
    }
}
