package yangzhou.api.business.capability

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import yangzhou.api.business.feasibility.FeasibilityService
import yangzhou.api.business.member.MemberService
import yangzhou.api.business.workspace.WorkspaceService
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.AttributeDefinition
import yangzhou.persistence.Capability
import yangzhou.persistence.Member
import yangzhou.persistence.Workspace
import yangzhou.persistence.repository.AttributeDefinitionRepository
import yangzhou.persistence.repository.CapabilityRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-87 单测补齐:CapabilityService(自评 upsert/delete,recompute 扇出)。 */
class CapabilityServiceUnitTest {
    private val capabilities = mockk<CapabilityRepository>(relaxed = true)
    private val definitions = mockk<AttributeDefinitionRepository>(relaxed = true)
    private val memberService = mockk<MemberService>(relaxed = true)
    private val workspaceService = mockk<WorkspaceService>(relaxed = true)
    private val feasibilityService = mockk<FeasibilityService>(relaxed = true)
    private val service = CapabilityService(capabilities, definitions, memberService, workspaceService, feasibilityService)

    private val wsId = UUID(0, 9)
    private val me =
        Member(id = 5L, objectId = UUID(0, 5), workspaceObjectId = wsId, username = null, passwordHash = "h", displayName = "me")
    private val kotlinDef = AttributeDefinition(id = 3L, objectId = UUID(0, 3), workspaceObjectId = wsId, name = "kotlin")

    @BeforeEach
    fun setup() {
        clearMocks(capabilities, definitions, memberService, workspaceService, feasibilityService)
        every { workspaceService.required() } returns Workspace(id = 1L, objectId = wsId)
        every { memberService.current() } returns me
        every { definitions.findByWorkspaceObjectIdAndName(wsId, "kotlin") } returns kotlinDef
    }

    @Nested
    inner class Upsert {
        @Test
        fun levelOutOfBoundsRejected() {
            for (bad in listOf(0, 5, -1)) {
                assertFailsWith<BadRequestException> { service.upsertFor(me, "kotlin", bad) }
            }
        }

        @Test
        fun unknownAttributeRejected() {
            every { definitions.findByWorkspaceObjectIdAndName(wsId, "rust") } returns null
            assertFailsWith<NotFoundException> { service.upsertFor(me, "rust", 2) }
        }

        @Test
        fun existingCapabilityLevelUpdated() {
            val existing =
                Capability(id = 1L, objectId = UUID(0, 1), memberObjectId = UUID(0, 5), attributeDefinitionObjectId = UUID(0, 3), level = 2)
            every { capabilities.findByMemberObjectIdAndAttributeDefinitionObjectId(UUID(0, 5), UUID(0, 3)) } returns existing
            every { capabilities.save(any()) } answers { firstArg() }
            val dto = service.upsertFor(me, "kotlin", 4)
            assertEquals(4, dto.level)
            assertEquals("kotlin", dto.attribute)
            verify { capabilities.save(withArg<Capability> { assertEquals(4, it.level) }) }
            verify { feasibilityService.recomputeWorkspace() } // V9-S1 冗余扇出
        }

        @Test
        fun newCapabilityCreatedWithNullLevel() {
            every { capabilities.findByMemberObjectIdAndAttributeDefinitionObjectId(UUID(0, 5), UUID(0, 3)) } returns null
            every { capabilities.save(any()) } answers { firstArg() }
            val dto = service.upsertFor(me, "kotlin", null) // presence-only
            assertEquals(null, dto.level)
        }
    }

    @Nested
    inner class DeleteCap {
        @Test
        fun unknownAttributeRejected() {
            every { definitions.findByWorkspaceObjectIdAndName(wsId, "rust") } returns null
            assertFailsWith<NotFoundException> { service.deleteFor(me, "rust") }
        }

        @Test
        fun deletesWhenPresent() {
            val existing =
                Capability(id = 1L, objectId = UUID(0, 1), memberObjectId = UUID(0, 5), attributeDefinitionObjectId = UUID(0, 3), level = 2)
            every { capabilities.findByMemberObjectIdAndAttributeDefinitionObjectId(UUID(0, 5), UUID(0, 3)) } returns existing
            service.deleteFor(me, "kotlin")
            verify { capabilities.delete(existing) }
            verify { feasibilityService.recomputeWorkspace() }
        }

        @Test
        fun absentIsNoop() {
            every { capabilities.findByMemberObjectIdAndAttributeDefinitionObjectId(UUID(0, 5), UUID(0, 3)) } returns null
            service.deleteFor(me, "kotlin")
            verify(exactly = 0) { capabilities.delete(any()) }
        }
    }

    @Nested
    inner class ListCaps {
        @Test
        fun namesResolvedOrQuestionMark() {
            val rustDef = AttributeDefinition(id = 4L, objectId = UUID(0, 4), workspaceObjectId = wsId, name = "rust")
            every { capabilities.findByMemberObjectId(UUID(0, 5)) } returns
                listOf(
                    Capability(
                        id = 1L,
                        objectId = UUID(0, 1),
                        memberObjectId = UUID(0, 5),
                        attributeDefinitionObjectId = UUID(0, 3),
                        level = 3,
                    ),
                    Capability(
                        id = 2L,
                        objectId = UUID(0, 2),
                        memberObjectId = UUID(0, 5),
                        attributeDefinitionObjectId = UUID(0, 99),
                        level = 1,
                    ),
                )
            every { definitions.findByWorkspaceObjectId(wsId) } returns listOf(kotlinDef, rustDef)
            val dtos = service.listFor(me)
            assertEquals(listOf("kotlin", "?"), dtos.map { it.attribute })
        }
    }
}
