package yangzhou.api.business.time

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import tools.jackson.databind.ObjectMapper
import yangzhou.api.business.github.GithubCommitService
import yangzhou.api.business.member.MemberService
import yangzhou.api.business.projectmember.ProjectMemberService
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Item
import yangzhou.persistence.Member
import yangzhou.persistence.TimeEntry
import yangzhou.persistence.repository.ItemCommitSnapshotRepository
import yangzhou.persistence.repository.ItemGroupMemberRepository
import yangzhou.persistence.repository.ItemGroupRepository
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ItemTypeRepository
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.TimeEntryRepository
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    private val commitSnapshots = mockk<ItemCommitSnapshotRepository>(relaxed = true)
    private val objectMapper = ObjectMapper()
    private val groups = mockk<ItemGroupRepository>(relaxed = true)
    private val groupMembers = mockk<ItemGroupMemberRepository>(relaxed = true)
    private val itemTypes = mockk<ItemTypeRepository>(relaxed = true)

    private val service =
        TimeEntryService(
            timeEntries,
            items,
            projects,
            members,
            memberService,
            projectMembers,
            githubCommits,
            commitSnapshots,
            objectMapper,
            groups,
            groupMembers,
            itemTypes,
        )

    private val item =
        Item(id = 9L, objectId = UUID(0, 9), projectObjectId = UUID(0, 100), number = 7, title = "任务", statusObjectId = UUID.randomUUID())
    private val me =
        Member(id = 5L, objectId = UUID(0, 5), workspaceObjectId = UUID(0, 1), username = "me", passwordHash = "x", displayName = "我")

    @BeforeEach
    fun setup() {
        clearMocks(timeEntries, items, projects, members, memberService, projectMembers, githubCommits)
        every { items.findByObjectId(item.objectId) } returns item
        every { memberService.current() } returns me
        every { projectMembers.assertAssignable(any(), any()) } returns Unit
        every { timeEntries.save(any()) } answers {
            val arg: TimeEntry = firstArg()
            arg.copy(id = 1L, objectId = UUID(0, 1))
        } // 泛型 save 坑(P0)
        every { items.findByObjectId(UUID(0, 9)) } returns (item)
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
            val running =
                TimeEntry(
                    id = 1L,
                    objectId = UUID(0, 1),
                    itemObjectId = UUID(0, 9),
                    memberObjectId = UUID(0, 5),
                    startedAt = Instant.now().minusSeconds(3600),
                )
            every { timeEntries.findByMemberObjectIdAndEndedAtIsNull(UUID(0, 5)) } returns listOf(running)

            val saved = mutableListOf<TimeEntry>()
            every { timeEntries.save(any()) } answers {
                val arg: TimeEntry = firstArg()
                saved.add(arg)
                arg
            }
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
            every { memberService.current() } returns me.copy(id = 6L, objectId = UUID(0, 6))
            every { timeEntries.findByObjectId(any()) } returns
                TimeEntry(id = 1L, objectId = UUID(0, 1), itemObjectId = UUID(0, 9), memberObjectId = UUID(0, 5), startedAt = Instant.now())

            assertFailsWith<NotFoundException> { service.stop(UUID.randomUUID()) }
        }

        @Test
        fun `已结束 409`() {
            every { timeEntries.findByObjectId(any()) } returns
                TimeEntry(
                    id = 1L,
                    objectId = UUID(0, 1),
                    itemObjectId = UUID(0, 9),
                    memberObjectId = UUID(0, 5),
                    startedAt = Instant.now(),
                    endedAt = Instant.now(),
                )

            assertFailsWith<ConflictException> { service.stop(UUID.randomUUID()) }
        }

        @Test
        fun `不存在 404`() {
            every { timeEntries.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.stop(UUID.randomUUID()) }
        }

        @Test
        fun `正常停 endedAt 落`() {
            val open =
                TimeEntry(
                    id = 1L,
                    objectId = UUID(0, 1),
                    itemObjectId = UUID(0, 9),
                    memberObjectId = UUID(0, 5),
                    startedAt = Instant.now().minusSeconds(600),
                )
            every { timeEntries.findByObjectId(any()) } returns open
            every { members.findByObjectId(UUID(0, 5)) } returns (me)

            val dto = service.stop(UUID.randomUUID())

            assertTrue(dto.endedAt != null)
        }
    }

    @Nested
    inner class BuildSuggestions {
        private fun commit(date: String?) = GithubCommitService.CommitDto(
            sha = "abc",
            message = "m",
            author = "a",
            date = date,
            url = null,
        )

        @Test
        fun `少于 2 个有效 commit 剔除`() {
            val refs =
                listOf(
                    GithubCommitService.RefCommitsDto("r", "YPJ-7-x", commits = listOf(commit("2026-10-07T14:00:00Z"))),
                )
            assertTrue(TimeEntryService.buildSuggestions(refs).isEmpty())
        }

        @Test
        fun `正常 first last 时间轴与 commitCount`() {
            val refs =
                listOf(
                    GithubCommitService.RefCommitsDto(
                        "r",
                        "YPJ-7-x",
                        commits =
                        listOf(
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

    @Nested
    inner class Report {
        @Test
        fun `from 晚于 to 400`() {
            assertFailsWith<BadRequestException> {
                service.report("CHE", "2026-11-14T00:00:00Z", "2026-11-01T00:00:00Z", null)
            }
        }

        @Test
        fun `from 非法格式 400`() {
            assertFailsWith<BadRequestException> { service.report("CHE", "2026-11-14", null, null) }
            assertFailsWith<BadRequestException> { service.report("CHE", null, "yesterday", null) }
        }

        @Test
        fun `项目不存在 404`() {
            every { projects.findByKey("GHOST") } returns null // relaxed 默认回子 mock 非非 null,必须显式 null
            assertFailsWith<NotFoundException> { service.report("GHOST", null, null, null) }
        }

        @Test
        fun `dayBuckets 首末日之间补零`() {
            val now = Instant.parse("2026-11-14T10:00:00Z")
            val rows =
                listOf(
                    TimeEntry(id = 1L, objectId = UUID(0, 1), itemObjectId = UUID(0, 9), memberObjectId = UUID(0, 5), startedAt = Instant.parse("2026-11-10T08:00:00Z"), minutes = 90),
                    TimeEntry(id = 2L, objectId = UUID(0, 2), itemObjectId = UUID(0, 9), memberObjectId = UUID(0, 5), startedAt = Instant.parse("2026-11-12T08:00:00Z"), minutes = 30),
                )

            val buckets = TimeEntryService.dayBuckets(rows, now)

            assertEquals(3, buckets.size) // 11-10 / 11-11 / 11-12
            assertEquals("2026-11-10", buckets[0].date)
            assertEquals(90L, buckets[0].minutes)
            assertEquals(0L, buckets[1].minutes)
            assertEquals(30L, buckets[2].minutes)
            assertEquals(emptyList(), TimeEntryService.dayBuckets(emptyList(), now))
        }

        @Test
        fun `dayBuckets 进行中条目折算到 now`() {
            val now = Instant.parse("2026-11-14T10:00:00Z")
            val rows =
                listOf(
                    TimeEntry(id = 1L, objectId = UUID(0, 1), itemObjectId = UUID(0, 9), memberObjectId = UUID(0, 5), startedAt = Instant.parse("2026-11-14T08:00:00Z")), // 无 minutes 无 endedAt
                )

            val buckets = TimeEntryService.dayBuckets(rows, now)

            assertEquals(120L, buckets.single().minutes)
        }

        @Test
        fun `typeBuckets 聚合与定义名缺失回退 key`() {
            val now = Instant.parse("2026-11-14T10:00:00Z")
            val i9 = UUID(0, 9)
            val i10 = UUID(0, 10)
            val rows =
                listOf(
                    TimeEntry(id = 1L, objectId = UUID(0, 1), itemObjectId = i9, memberObjectId = UUID(0, 5), startedAt = Instant.parse("2026-11-10T08:00:00Z"), minutes = 90),
                    TimeEntry(id = 2L, objectId = UUID(0, 2), itemObjectId = i9, memberObjectId = UUID(0, 5), startedAt = Instant.parse("2026-11-10T09:00:00Z"), minutes = 30),
                    TimeEntry(id = 3L, objectId = UUID(0, 3), itemObjectId = i10, memberObjectId = UUID(0, 5), startedAt = Instant.parse("2026-11-12T08:00:00Z"), minutes = 15),
                )
            val itemTypes = mapOf(i9 to "task", i10 to "bug")

            val buckets =
                TimeEntryService.typeBuckets(rows, itemTypes, mapOf("task" to "杂活"), now)

            assertEquals(2, buckets.size)
            assertEquals("bug", buckets[0].typeKey) // sortedBy typeKey 字典序 bug 在前
            assertEquals("bug", buckets[0].typeName) // 无定义回退 key
            assertEquals(15L, buckets[0].minutes)
            assertEquals("task", buckets[1].typeKey)
            assertEquals("杂活", buckets[1].typeName)
            assertEquals(120L, buckets[1].minutes)
        }
    }

    @Nested
    inner class Snapshots {
        private fun snapshotRef(
            repo: String = "octo/r",
            ref: String = "YPJ-7-x",
            capturedBy: String,
            dates: List<String?>,
        ) = TimeEntryService.SnapshotRef(
            repo = repo,
            ref = ref,
            capturedAt = Instant.parse("2026-11-14T12:00:00Z"),
            capturedBy = capturedBy,
            commits = dates.map { d -> GithubCommitService.CommitDto("abc", "m", "a", d, null) },
        )

        @Test
        fun `单 commit 加 FINAL 锚成段——commit 到 captured_at`() {
            val out =
                TimeEntryService.buildSuggestionsFromSnapshots(
                    emptySet(),
                    listOf(snapshotRef(capturedBy = "finalize", dates = listOf("2026-11-13T09:00:00Z"))),
                )

            assertEquals(1, out.size)
            assertEquals("2026-11-13T09:00:00Z", out[0].startedAt)
            assertEquals("2026-11-14T12:00:00Z", out[0].endedAt) // = captured_at
            assertEquals(1, out[0].commitCount)
            assertEquals("分支 YPJ-7-x · 单提交(commit→完成时刻)", out[0].note)
        }

        @Test
        fun `快照两个以上 dated 同现有口径 first last`() {
            val out =
                TimeEntryService.buildSuggestionsFromSnapshots(
                    emptySet(),
                    listOf(
                        snapshotRef(
                            capturedBy = "finalize",
                            dates = listOf("2026-11-10T09:00:00Z", "2026-11-13T18:00:00Z", null),
                        ),
                    ),
                )

            assertEquals(1, out.size)
            assertEquals("2026-11-10T09:00:00Z", out[0].startedAt)
            assertEquals("2026-11-13T18:00:00Z", out[0].endedAt)
            assertEquals(3, out[0].commitCount) // commitCount 用快照全部 commits 数
        }

        @Test
        fun `单 commit 无 FINAL 锚或零 dated 不出`() {
            val out =
                TimeEntryService.buildSuggestionsFromSnapshots(
                    emptySet(),
                    listOf(
                        snapshotRef(ref = "r-read", capturedBy = "read", dates = listOf("2026-11-13T09:00:00Z")),
                        snapshotRef(ref = "r-nodate", capturedBy = "finalize", dates = listOf(null)),
                    ),
                )

            assertTrue(out.isEmpty())
        }

        @Test
        fun `live 已出段的 ref 跳过,快照补 live 不足的 ref`() {
            val out =
                TimeEntryService.buildSuggestionsFromSnapshots(
                    setOf("octo/r" to "r-done"),
                    listOf(
                        snapshotRef(repo = "octo/r", ref = "r-done", capturedBy = "finalize", dates = listOf("2026-11-10T09:00:00Z", "2026-11-11T09:00:00Z")),
                        snapshotRef(repo = "octo/r", ref = "r-open", capturedBy = "finalize", dates = listOf("2026-11-12T09:00:00Z")),
                    ),
                )

            assertEquals(1, out.size)
            assertEquals("r-open", out[0].ref)
        }
    }
}
