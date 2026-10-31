package yangzhou.api.business.attribute

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.springframework.dao.DataIntegrityViolationException
import yangzhou.api.business.workspace.WorkspaceService
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.AttributeDefinition
import yangzhou.persistence.Workspace
import yangzhou.persistence.repository.AttributeDefinitionRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-86 单测补齐:AttributeService 全分支(nested class 按方法分组)。 */
class AttributeServiceUnitTest {
    private val definitions = mockk<AttributeDefinitionRepository>(relaxed = true)
    private val workspaceService = mockk<WorkspaceService>(relaxed = true)
    private val service = AttributeService(definitions, workspaceService)

    private val wsId = UUID(0, 7)

    private fun def(
        id: Long?,
        name: String,
        kind: String = "skill",
        parentId: UUID? = null,
        objectId: UUID = id?.let { UUID(0, it) } ?: UUID.randomUUID(),
    ) = AttributeDefinition(id = id, objectId = objectId, workspaceObjectId = wsId, name = name, kind = kind, parentObjectId = parentId)

    @BeforeEach
    fun setup() {
        clearMocks(definitions, workspaceService)
        every { workspaceService.required() } returns Workspace(id = 1, objectId = wsId, name = "ws")
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
            every { definitions.existsByWorkspaceObjectIdAndName(wsId, "kotlin") } returns true
            assertFailsWith<ConflictException> { service.create("kotlin", "skill", false, null) }
        }

        @Test
        fun categoryCannotHaveParent() {
            assertFailsWith<BadRequestException> { service.create("c", "category", false, UUID.randomUUID()) }
        }

        @Test
        fun parentMustExist() {
            every { definitions.findByWorkspaceObjectIdAndObjectId(wsId, any()) } returns null
            assertFailsWith<NotFoundException> { service.create("leaf", "skill", false, UUID.randomUUID()) }
        }

        @Test
        fun parentMustBeCategory() {
            val parent = def(1L, "leaf-parent", kind = "skill")
            every { definitions.findByWorkspaceObjectIdAndObjectId(wsId, parent.objectId) } returns parent
            assertFailsWith<BadRequestException> { service.create("leaf", "skill", false, parent.objectId) }
        }

        @Test
        fun leafUnderCategorySaved() {
            val cat = def(1L, "lang", kind = "category")
            every { definitions.existsByWorkspaceObjectIdAndName(wsId, "kotlin") } returns false
            every { definitions.findByWorkspaceObjectIdAndObjectId(wsId, cat.objectId) } returns cat
            every { definitions.save(any()) } answers { firstArg() }
            var savedParent: UUID? = null
            every { definitions.save(any()) } answers {
                savedParent = firstArg<AttributeDefinition>().parentObjectId
                firstArg()
            }
            val dto = service.create("kotlin", "skill", true, cat.objectId)
            assertEquals(UUID(0, 1), savedParent)
        }
    }

    @Nested
    inner class Update {
        @Test
        fun missingAttributeRejected() {
            every { definitions.findByWorkspaceObjectIdAndObjectId(wsId, any()) } returns null
            assertFailsWith<NotFoundException> { service.update(UUID.randomUUID(), null, null, null, false) }
        }

        @Test
        fun categoryKindIsImmutable() {
            val cat = def(1L, "c", kind = "category")
            every { definitions.findByWorkspaceObjectIdAndObjectId(wsId, cat.objectId) } returns cat
            assertFailsWith<BadRequestException> { service.update(cat.objectId, "skill", null, null, false) }
        }

        @Test
        fun categoryCannotBeReparented() {
            val cat = def(1L, "c", kind = "category")
            every { definitions.findByWorkspaceObjectIdAndObjectId(wsId, cat.objectId) } returns cat
            every { definitions.save(any()) } answers { firstArg() }
            val dto = service.update(cat.objectId, null, true, UUID.randomUUID(), false)
            assertEquals(null, dto.parentObjectId) // category 忽略 parent 入参
        }

        @Test
        fun leafUnassignClearsParent() {
            val leaf = def(2L, "kotlin", parentId = UUID(0, 1))
            every { definitions.findByWorkspaceObjectIdAndObjectId(wsId, leaf.objectId) } returns leaf
            every { definitions.save(any()) } answers { firstArg() }
            assertEquals(null, service.update(leaf.objectId, null, null, null, unassign = true).parentObjectId)
        }

        @Test
        fun integrityViolationBecomesConflict() {
            val leaf = def(2L, "kotlin")
            every { definitions.findByWorkspaceObjectIdAndObjectId(wsId, leaf.objectId) } returns leaf
            every { definitions.save(any()) } throws DataIntegrityViolationException("dup")
            assertFailsWith<ConflictException> { service.update(leaf.objectId, null, true, null, false) }
        }
    }

    @Nested
    inner class Delete {
        @Test
        fun missingAttributeRejected() {
            every { definitions.findByWorkspaceObjectIdAndObjectId(wsId, any()) } returns null
            assertFailsWith<NotFoundException> { service.delete(UUID.randomUUID()) }
        }

        @Test
        fun categoryWithChildrenRejected() {
            val cat = def(1L, "lang", kind = "category")
            every { definitions.findByWorkspaceObjectIdAndObjectId(wsId, cat.objectId) } returns cat
            every { definitions.findByWorkspaceObjectId(wsId) } returns listOf(cat, def(2L, "kotlin", parentId = UUID(0, 1)))
            assertFailsWith<ConflictException> { service.delete(cat.objectId) }
        }

        @Test
        fun referencedAttributeRejected() {
            val leaf = def(2L, "kotlin")
            every { definitions.findByWorkspaceObjectIdAndObjectId(wsId, leaf.objectId) } returns leaf
            every { definitions.findByWorkspaceObjectId(wsId) } returns listOf(leaf)
            every { definitions.delete(leaf) } throws DataIntegrityViolationException("fk")
            assertFailsWith<ConflictException> { service.delete(leaf.objectId) }
        }

        @Test
        fun emptyCategoryDeleted() {
            val cat = def(1L, "lang", kind = "category")
            every { definitions.findByWorkspaceObjectIdAndObjectId(wsId, cat.objectId) } returns cat
            every { definitions.findByWorkspaceObjectId(wsId) } returns listOf(cat)
            service.delete(cat.objectId)
            verify { definitions.delete(cat) }
        }
    }

    @Nested
    inner class List {
        @Test
        fun leavesCarryCategoryName() {
            val cat = def(1L, "lang", kind = "category")
            val leaf = def(2L, "kotlin", parentId = UUID(0, 1))
            val orphan = def(3L, "misc")
            every { definitions.findByWorkspaceObjectId(wsId) } returns listOf(cat, leaf, orphan)
            val dtos = service.list().associateBy { it.name }
            assertEquals("lang", dtos["kotlin"]?.categoryName)
            assertEquals(null, dtos["misc"]?.categoryName)
        }
    }
}
