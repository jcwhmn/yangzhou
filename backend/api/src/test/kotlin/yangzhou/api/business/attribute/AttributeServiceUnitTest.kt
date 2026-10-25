package yangzhou.api.business.attribute

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.dao.DataIntegrityViolationException
import yangzhou.api.business.workspace.WorkspaceService
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.AttributeDefinition
import yangzhou.persistence.Workspace
import yangzhou.persistence.repository.AttributeDefinitionRepository
import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-86 单测补齐:AttributeService 全分支(nested class 按方法分组)。 */
class AttributeServiceUnitTest {

    private val definitions = mockk<AttributeDefinitionRepository>(relaxed = true)
    private val workspaceService = mockk<WorkspaceService>(relaxed = true)
    private val service = AttributeService(definitions, workspaceService)

    private val wsId = 7L
    private fun def(
        id: Long?,
        name: String,
        kind: String = "skill",
        parentId: Long? = null,
        objectId: UUID = UUID.randomUUID(),
    ) = AttributeDefinition(id = id, objectId = objectId, workspaceId = wsId, name = name, kind = kind, parentId = parentId)

    @BeforeEach
    fun setup() {
        clearMocks(definitions, workspaceService)
        every { workspaceService.required() } returns Workspace(id = wsId, name = "ws")
    }

    @Nested
    inner class Create {

        @Test
        fun invalidKindRejected() {
            for (kind in listOf("skillx", "", "CATEGORY")) {
                assertFailsWith<BadRequestException> { service.create("n", kind, false, null) }
            }
        }

        @Test
        fun duplicateNameRejected() {
            every { definitions.existsByWorkspaceIdAndName(wsId, "kotlin") } returns true
            assertFailsWith<ConflictException> { service.create("kotlin", "skill", false, null) }
        }

        @Test
        fun categoryCannotHaveParent() {
            assertFailsWith<BadRequestException> { service.create("c", "category", false, UUID.randomUUID()) }
        }

        @Test
        fun parentMustExist() {
            every { definitions.findByWorkspaceIdAndObjectId(wsId, any()) } returns null
            assertFailsWith<NotFoundException> { service.create("leaf", "skill", false, UUID.randomUUID()) }
        }

        @Test
        fun parentMustBeCategory() {
            val parent = def(1L, "leaf-parent", kind = "skill")
            every { definitions.findByWorkspaceIdAndObjectId(wsId, parent.objectId) } returns parent
            assertFailsWith<BadRequestException> { service.create("leaf", "skill", false, parent.objectId) }
        }

        @Test
        fun leafUnderCategorySaved() {
            val cat = def(1L, "lang", kind = "category")
            every { definitions.existsByWorkspaceIdAndName(wsId, "kotlin") } returns false
            every { definitions.findByWorkspaceIdAndObjectId(wsId, cat.objectId) } returns cat
            every { definitions.save(any()) } answers { firstArg() }
            var savedParent: Long? = null
            every { definitions.save(any()) } answers { savedParent = firstArg<AttributeDefinition>().parentId; firstArg() }
            val dto = service.create("kotlin", "skill", true, cat.objectId)
            assertEquals(1L, savedParent)
        }
    }

    @Nested
    inner class Update {

        @Test
        fun missingAttributeRejected() {
            every { definitions.findByWorkspaceIdAndObjectId(wsId, any()) } returns null
            assertFailsWith<NotFoundException> { service.update(UUID.randomUUID(), null, null, null, false) }
        }

        @Test
        fun categoryKindIsImmutable() {
            val cat = def(1L, "c", kind = "category")
            every { definitions.findByWorkspaceIdAndObjectId(wsId, cat.objectId) } returns cat
            assertFailsWith<BadRequestException> { service.update(cat.objectId, "skill", null, null, false) }
        }

        @Test
        fun categoryCannotBeReparented() {
            val cat = def(1L, "c", kind = "category")
            every { definitions.findByWorkspaceIdAndObjectId(wsId, cat.objectId) } returns cat
            every { definitions.save(any()) } answers { firstArg() }
            val dto = service.update(cat.objectId, null, true, UUID.randomUUID(), false)
            assertEquals(null, dto.parentId) // category 忽略 parent 入参
        }

        @Test
        fun leafUnassignClearsParent() {
            val leaf = def(2L, "kotlin", parentId = 1L)
            every { definitions.findByWorkspaceIdAndObjectId(wsId, leaf.objectId) } returns leaf
            every { definitions.save(any()) } answers { firstArg() }
            assertEquals(null, service.update(leaf.objectId, null, null, null, unassign = true).parentId)
        }

        @Test
        fun integrityViolationBecomesConflict() {
            val leaf = def(2L, "kotlin")
            every { definitions.findByWorkspaceIdAndObjectId(wsId, leaf.objectId) } returns leaf
            every { definitions.save(any()) } throws DataIntegrityViolationException("dup")
            assertFailsWith<ConflictException> { service.update(leaf.objectId, null, true, null, false) }
        }
    }

    @Nested
    inner class Delete {

        @Test
        fun missingAttributeRejected() {
            every { definitions.findByWorkspaceIdAndObjectId(wsId, any()) } returns null
            assertFailsWith<NotFoundException> { service.delete(UUID.randomUUID()) }
        }

        @Test
        fun categoryWithChildrenRejected() {
            val cat = def(1L, "lang", kind = "category")
            every { definitions.findByWorkspaceIdAndObjectId(wsId, cat.objectId) } returns cat
            every { definitions.findByWorkspaceId(wsId) } returns listOf(cat, def(2L, "kotlin", parentId = 1L))
            assertFailsWith<ConflictException> { service.delete(cat.objectId) }
        }

        @Test
        fun referencedAttributeRejected() {
            val leaf = def(2L, "kotlin")
            every { definitions.findByWorkspaceIdAndObjectId(wsId, leaf.objectId) } returns leaf
            every { definitions.findByWorkspaceId(wsId) } returns listOf(leaf)
            every { definitions.delete(leaf) } throws DataIntegrityViolationException("fk")
            assertFailsWith<ConflictException> { service.delete(leaf.objectId) }
        }

        @Test
        fun emptyCategoryDeleted() {
            val cat = def(1L, "lang", kind = "category")
            every { definitions.findByWorkspaceIdAndObjectId(wsId, cat.objectId) } returns cat
            every { definitions.findByWorkspaceId(wsId) } returns listOf(cat)
            service.delete(cat.objectId)
            verify { definitions.delete(cat) }
        }
    }

    @Nested
    inner class List {

        @Test
        fun leavesCarryCategoryName() {
            val cat = def(1L, "lang", kind = "category")
            val leaf = def(2L, "kotlin", parentId = 1L)
            val orphan = def(3L, "misc")
            every { definitions.findByWorkspaceId(wsId) } returns listOf(cat, leaf, orphan)
            val dtos = service.list().associateBy { it.name }
            assertEquals("lang", dtos["kotlin"]?.categoryName)
            assertEquals(null, dtos["misc"]?.categoryName)
        }
    }
}
