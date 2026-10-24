package yangzhou.api.business.candidate

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import yangzhou.api.business.feasibility.FeasibilityService
import yangzhou.api.business.member.MemberService
import yangzhou.api.business.projectmember.ProjectMemberService
import yangzhou.api.business.workspace.WorkspaceService
import yangzhou.api.error.NotFoundException
import yangzhou.domain.Attribute
import yangzhou.domain.Capability as DomainCapability
import yangzhou.domain.Member as DomainMember
import yangzhou.domain.Item as DomainItem
import yangzhou.persistence.Item
import yangzhou.persistence.Member
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.MemberRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested

/**
 * YPJ-80 mockk 子票①:CandidateService 装配/分支单测。
 * 引擎真跑(rankCandidates);feasibility 域装配协作者 mockk(其本票①已单测)。
 */
class CandidateServiceUnitTest {

    private val items = mockk<ItemRepository>(relaxed = true)
    private val members = mockk<MemberRepository>(relaxed = true)
    private val memberService = mockk<MemberService>(relaxed = true)
    private val workspaceService = mockk<WorkspaceService>(relaxed = true)
    private val feasibility = mockk<FeasibilityService>(relaxed = true)
    private val projectMembers = mockk<ProjectMemberService>(relaxed = true)

    private val service = CandidateService(items, members, memberService, workspaceService, feasibility, projectMembers)

    private val item = Item(id = 1L, objectId = UUID.randomUUID(), projectId = 100L, number = 7, title = "任务", statusObjectId = UUID.randomUUID())
    private val domainItem = DomainItem("YPJ-7", "任务", listOf(yangzhou.domain.Requirement(Attribute("kotlin"), 2)))

    // 三成员:A 胜任(kotlin 3)、C 差距(kotlin 1)、B 池外被过滤
    private val alice = Member(id = 1L, objectId = UUID.randomUUID(), workspaceId = 1L, username = "alice", passwordHash = null, displayName = "Alice")
    private val carol = Member(id = 3L, objectId = UUID.randomUUID(), workspaceId = 1L, username = "carol", passwordHash = "h", displayName = "Carol")
    private val bob = Member(id = 2L, objectId = UUID.randomUUID(), workspaceId = 1L, username = "bob", passwordHash = "h", displayName = "Bob")

    private val aliceDomain = DomainMember("Alice", listOf(DomainCapability(Attribute("kotlin"), 3)))
    private val carolDomain = DomainMember("Carol", listOf(DomainCapability(Attribute("kotlin"), 1)))

    @BeforeEach
    fun setup() {
        clearMocks(items, members, memberService, workspaceService, feasibility, projectMembers)
        every { items.findByObjectId(item.objectId) } returns item
        every { feasibility.itemToDomain(item) } returns domainItem
        every { members.findByWorkspaceId(1L) } returns listOf(alice, bob, carol)
        every { workspaceService.required() } returns yangzhou.persistence.Workspace(id = 1L)
        every { projectMembers.restrictedPool(100L) } returns setOf(1L, 3L) // B(2L) 池外
        every { feasibility.domainMemberOf(alice) } returns aliceDomain
        every { feasibility.domainMemberOf(carol) } returns carolDomain
        every { feasibility.domainMemberOf(bob) } returns DomainMember("Bob", emptyList())
        every { memberService.current() } returns alice
    }

    @Nested
    inner class Candidates {
        @Test
        fun `排序与 rank 连续,Alice(GREEN)在 Carol(YELLOW)前`() {
            val result = service.candidates(item.objectId)

            assertEquals(listOf("Alice", "Carol"), result.map { it.displayName })
            assertEquals(listOf(1, 2), result.map { it.rank })
            assertEquals("GREEN", result[0].signal)
            assertEquals("YELLOW", result[1].signal)
        }

        @Test
        fun `池约束 - 池外成员不进候选`() {
            val result = service.candidates(item.objectId)

            assertTrue(result.none { it.displayName == "Bob" })
        }

        @Test
        fun `virtual 判定 - passwordHash 为空即虚拟`() {
            val result = service.candidates(item.objectId)

            assertTrue(result.first { it.displayName == "Alice" }.virtual)
            assertFalse(result.first { it.displayName == "Carol" }.virtual)
        }

        @Test
        fun `gap verdict 字段映射 - delta、required、actual 齐`() {
            val result = service.candidates(item.objectId)
            val gap = result.first { it.displayName == "Carol" }.verdicts.single()

            assertEquals("gap", gap.kind)
            assertEquals("kotlin", gap.attribute)
            assertEquals(1, gap.delta)
            assertEquals(2, gap.required)
            assertEquals(1, gap.actual)
        }

        @Test
        fun `missing 成员 - 无 capability 仍可入池(池只看成员集合)`() {
            every { projectMembers.restrictedPool(100L) } returns setOf(1L, 2L, 3L)
            every { feasibility.domainMemberOf(bob) } returns DomainMember("Bob", emptyList())

            val result = service.candidates(item.objectId)

            val bobRow = result.first { it.displayName == "Bob" }
            assertEquals("RED", bobRow.signal) // 全 missing
            assertEquals(1, bobRow.missingCount)
        }

        @Test
        fun `item 不存在 - 404`() {
            every { items.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.candidates(UUID.randomUUID()) }
        }
    }
}
