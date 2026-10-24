package yangzhou.api.business.time

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import yangzhou.api.business.github.GithubCommitService
import yangzhou.api.business.member.MemberService
import yangzhou.api.business.projectmember.ProjectMemberService
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Item
import yangzhou.persistence.Member
import yangzhou.persistence.TimeEntry
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.TimeEntryRepository
import java.time.Instant
import java.util.Optional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested

/**
 * YPJ-83 mockk 子票④:TimeEntryService 校验分支 + 建议纯函数单测。
 * 协作者 mockk(relaxed);泛型 save 显式 firstArg(P0);被测代码用 Instant.now(),断言不钉具体时刻。
 */
class TimeEntryServiceUnitTest {

    private val timeEntries = mockk<TimeEntryRepository>(relaxed = true)
    private val items = mockk<ItemRepository>(relaxed = true)
    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val members = mockk<MemberRepository>(relaxed = true)
    private val memberService = mockk<MemberService>(relaxed = true)
    private val projectMembers = mockk<ProjectMemberService>(relaxed = true)
    private val githubCommits = mockk<GithubCommitService>(relaxed = true)

    private val service = TimeEntryService(
        timeEntries, items, projects, members, memberService, projectMembers, githubCommits,
    )

    private val item = Item(id = 9L, objectId = UUID.randomUUID(), projectId = 100L, number = 7, title = "任务", statusObjectId = UUID.randomUUID())
    private val me = Member(id = 5L, workspaceId = 1L, username = "me", passwordHash = "x", displayName = "我")

    @BeforeEach
    fun setup() {
        clearMocks(timeEntries, items, projects, members, memberService, projectMembers, githubCommits)
        every { items.findByObjectId(item.objectId) } returns item
        every { memberService.current() } returns me
        every { projectMembers.assertAssignable(any(), any()) } returns Unit
        every { timeEntries.save(any()) } answers { val arg: TimeEntry = firstArg(); arg.copy(id = 1L) } // 泛型 save 坑(P0)
        every { items.findById(9L) } returns Optional.of(item)
    }

    @Nested
    inner class Create {
        @Test
        fun `记录与终止互斥 400`() {
            assertFailsWith<BadRequestException> {
                service.create(item.objectId, 30, "2026-10-07T14:00:00Z", null, null)
            }
        }

        @Test
        fun `endedAt 无 startedAt 400`() {
            assertFailsWith<BadRequestException> {
                service.create(item.objectId, null, null, "2026-10-07T15:00:00Z", null)
            }
        }

        @Test
        fun `endedAt 不晚于 startedAt 400`() {
            assertFailsWith<BadRequestException> {
                service.create(item.objectId, null, "2026-10-07T15:00:00Z", "2026-10-07T14:00:00Z", null)
            }
        }

        @Test
        fun `minutes 非正 400`() {
            assertFailsWith<BadRequestException> {
                service.create(item.objectId, 0, null, null, null)
            }
        }

        @Test
        fun `item 不存在 404`() {
            every { items.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.create(UUID.randomUUID(), 30, null, null, null) }
        }

        @Test
        fun `池外成员 409`() {
            every { projectMembers.assertAssignable(any(), any()) } throws ConflictException("该成员不在项目成员中")
            assertFailsWith<ConflictException> { service.create(item.objectId, 30, null, null, null) }
        }

        @Test
        fun `计时模式 先停表中未结束条目再开新表`() {
            val running = TimeEntry(id = 1L, itemId = 9L, memberId = 5L, startedAt = Instant.now().minusSeconds(3600))
            every { timeEntries.findByMemberIdAndEndedAtIsNull(5L) } returns listOf(running)

            val saved = mutableListOf<TimeEntry>()
            every { timeEntries.save(any()) } answers { val arg: TimeEntry = firstArg(); saved.add(arg); arg }
            val dto = service.create(item.objectId, null, "2026-10-07T14:00:00Z", "2026-10-07T15:00:00Z", null)

            assertEquals(60L, dto.minutes) // 终止记录:endedAt-startedAt 自动算时长
            val stoppedOld = saved.filter { it.endedAt != null && it.startedAt < Instant.now().minusSeconds(3000) }
            assertEquals(1, stoppedOld.size) // 旧表已自动停(旧行为:先停全部未结束)
        }

        @Test
        fun `记录模式 minutes 落库,回填 startedAt 与 endedAt`() {
            val dto = service.create(item.objectId, 45, null, null, "做完了")

            assertEquals(45L, dto.minutes)
            assertEquals("做完了", dto.note)
            assertTrue(dto.endedAt != null)
        }
    }

    @Nested
    inner class Stop {
        @Test
        fun `非本人记录 404 不暴露`() {
            every { memberService.current() } returns me.copy(id = 6L)
            every { timeEntries.findByObjectId(any()) } returns
                TimeEntry(id = 1L, itemId = 9L, memberId = 5L, startedAt = Instant.now())

            assertFailsWith<NotFoundException> { service.stop(UUID.randomUUID()) }
        }

        @Test
        fun `已结束 409`() {
            every { timeEntries.findByObjectId(any()) } returns
                TimeEntry(id = 1L, itemId = 9L, memberId = 5L, startedAt = Instant.now(), endedAt = Instant.now())

            assertFailsWith<ConflictException> { service.stop(UUID.randomUUID()) }
        }

        @Test
        fun `不存在 404`() {
            every { timeEntries.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.stop(UUID.randomUUID()) }
        }

        @Test
        fun `正常停 endedAt 落`() {
            val open = TimeEntry(id = 1L, itemId = 9L, memberId = 5L, startedAt = Instant.now().minusSeconds(600))
            every { timeEntries.findByObjectId(any()) } returns open
            every { members.findById(5L) } returns Optional.of(me)

            val dto = service.stop(UUID.randomUUID())

            assertTrue(dto.endedAt != null)
        }
    }

    @Nested
    inner class BuildSuggestions {
        private fun commit(date: String?) = GithubCommitService.CommitDto(
            sha = "abc", message = "m", author = "a", date = date, url = null,
        )

        @Test
        fun `少于 2 个有效 commit 剔除`() {
            val refs = listOf(
                GithubCommitService.RefCommitsDto("r", "YPJ-7-x", commits = listOf(commit("2026-10-07T14:00:00Z"))),
            )
            assertTrue(TimeEntryService.buildSuggestions(refs).isEmpty())
        }

        @Test
        fun `正常 first last 时间轴与 commitCount`() {
            val refs = listOf(
                GithubCommitService.RefCommitsDto(
                    "r", "YPJ-7-x",
                    commits = listOf(
                        commit("2026-10-07T16:00:00Z"),
                        commit("2026-10-07T14:00:00Z"),
                        commit("bad-date"),
                        commit(null),
                    ),
                ),
            )

            val suggestions = TimeEntryService.buildSuggestions(refs)

            assertEquals(1, suggestions.size)
            val s = suggestions.single()
            assertEquals("2026-10-07T14:00:00Z", s.startedAt)
            assertEquals("2026-10-07T16:00:00Z", s.endedAt)
            assertEquals(4, s.commitCount) // 计数含坏日期(时间轴只取可解析者)
        }
    }
}
