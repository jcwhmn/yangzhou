package yangzhou.api.business.capability

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
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

    private val wsId = 9L
    private val me = Member(id = 5L, workspaceId = wsId, username = null, passwordHash = "h", displayName = "me")
    private val kotlinDef = AttributeDefinition(id = 3L, workspaceId = wsId, name = "kotlin")

    @BeforeEach
    fun setup() {
        clearMocks(capabilities, definitions, memberService, workspaceService, feasibilityService)
        every { workspaceService.required() } returns Workspace(id = wsId)
        every { memberService.current() } returns me
        every { definitions.findByWorkspaceIdAndName(wsId, "kotlin") } returns kotlinDef
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
            every { definitions.findByWorkspaceIdAndName(wsId, "rust") } returns null
            assertFailsWith<NotFoundException> { service.upsertFor(me, "rust", 2) }
        }

        @Test
        fun existingCapabilityLevelUpdated() {
            val existing = Capability(id = 1L, memberId = 5L, attributeDefinitionId = 3L, level = 2)
            every { capabilities.findByMemberIdAndAttributeDefinitionId(5L, 3L) } returns existing
            every { capabilities.save(any()) } answers { firstArg() }
            val dto = service.upsertFor(me, "kotlin", 4)
            assertEquals(4, dto.level)
            assertEquals("kotlin", dto.attribute)
            verify { capabilities.save(withArg<Capability> { assertEquals(4, it.level) }) }
            verify { feasibilityService.recomputeWorkspace() } // V9-S1 冗余扇出
        }

        @Test
        fun newCapabilityCreatedWithNullLevel() {
            every { capabilities.findByMemberIdAndAttributeDefinitionId(5L, 3L) } returns null
            every { capabilities.save(any()) } answers { firstArg() }
            val dto = service.upsertFor(me, "kotlin", null) // presence-only
            assertEquals(null, dto.level)
        }
    }

    @Nested
    inner class DeleteCap {

        @Test
        fun unknownAttributeRejected() {
            every { definitions.findByWorkspaceIdAndName(wsId, "rust") } returns null
            assertFailsWith<NotFoundException> { service.deleteFor(me, "rust") }
        }

        @Test
        fun deletesWhenPresent() {
            val existing = Capability(id = 1L, memberId = 5L, attributeDefinitionId = 3L, level = 2)
            every { capabilities.findByMemberIdAndAttributeDefinitionId(5L, 3L) } returns existing
            service.deleteFor(me, "kotlin")
            verify { capabilities.delete(existing) }
            verify { feasibilityService.recomputeWorkspace() }
        }

        @Test
        fun absentIsNoop() {
            every { capabilities.findByMemberIdAndAttributeDefinitionId(5L, 3L) } returns null
            service.deleteFor(me, "kotlin")
            verify(exactly = 0) { capabilities.delete(any()) }
        }
    }

    @Nested
    inner class ListCaps {

        @Test
        fun namesResolvedOrQuestionMark() {
            val rustDef = AttributeDefinition(id = 4L, workspaceId = wsId, name = "rust")
            every { capabilities.findByMemberId(5L) } returns listOf(
                Capability(id = 1L, memberId = 5L, attributeDefinitionId = 3L, level = 3),
                Capability(id = 2L, memberId = 5L, attributeDefinitionId = 99L, level = 1),
            )
            every { definitions.findByWorkspaceId(wsId) } returns listOf(kotlinDef, rustDef)
            val dtos = service.listFor(me)
            assertEquals(listOf("kotlin", "?"), dtos.map { it.attribute })
        }
    }
}
