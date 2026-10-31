package yangzhou.api.business.favorite

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import yangzhou.api.business.member.MemberService
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Favorite
import yangzhou.persistence.Project
import yangzhou.persistence.repository.FavoriteRepository
import yangzhou.persistence.repository.ProjectRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-88 单测补齐:FavoriteService(幂等收藏/移除静默)。 */
class FavoriteServiceUnitTest {
    private val favorites = mockk<FavoriteRepository>(relaxed = true)
    private val projects = mockk<ProjectRepository>(relaxed = true)
    private val memberService = mockk<MemberService>(relaxed = true)
    private val service = FavoriteService(favorites, projects, memberService)

    private val meId = UUID(0, 5)
    private val project = Project(id = 1L, objectId = UUID(0, 1), workspaceObjectId = UUID(0, 9), key = "CHE", name = "CHE")

    @BeforeEach
    fun setup() {
        clearMocks(favorites, projects, memberService)
        every { memberService.current().objectId } returns meId
        every { projects.findByKey("CHE") } returns project
    }

    @Nested
    inner class Add {
        @Test
        fun missingProjectRejected() {
            every { projects.findByKey("NOPE") } returns null
            assertFailsWith<NotFoundException> { service.add("NOPE") }
        }

        @Test
        fun duplicateIsNoop() {
            every { favorites.existsByMemberObjectIdAndProjectObjectId(meId, UUID(0, 1)) } returns true
            val dto = service.add("CHE")
            io.mockk.verify(exactly = 0) { favorites.save(any()) }
            assertEquals("CHE", dto.key)
        }

        @Test
        fun savedWhenAbsent() {
            every { favorites.existsByMemberObjectIdAndProjectObjectId(meId, UUID(0, 1)) } returns false
            every { favorites.save(any()) } answers { firstArg() }
            service.add("CHE")
            io.mockk.verify { favorites.save(any()) }
        }
    }

    @Nested
    inner class Remove {
        @Test
        fun absentIsSilent() {
            every { favorites.findByMemberObjectIdAndProjectObjectId(meId, UUID(0, 1)) } returns null
            service.remove("CHE")
            io.mockk.verify(exactly = 0) { favorites.delete(any()) }
        }

        @Test
        fun rowDeleted() {
            val row = Favorite(id = 1L, objectId = UUID(0, 1), memberObjectId = meId, projectObjectId = UUID(0, 1))
            every { favorites.findByMemberObjectIdAndProjectObjectId(meId, UUID(0, 1)) } returns row
            service.remove("CHE")
            verify { favorites.delete(row) }
        }
    }

    @Nested
    inner class ListFavorites {
        @Test
        fun deletedProjectsSkipped() {
            every { favorites.findByMemberObjectIdOrderByCreatedAtAsc(meId) } returns
                listOf(
                    Favorite(id = 1L, objectId = UUID(0, 1), memberObjectId = meId, projectObjectId = UUID(0, 1)),
                    Favorite(id = 2L, objectId = UUID(0, 2), memberObjectId = meId, projectObjectId = UUID(0, 99)), // 项目已删
                )
            every { projects.findByObjectId(UUID(0, 1)) } returns (project)
            every { projects.findByObjectId(UUID(0, 99)) } returns null
            assertEquals(listOf("CHE"), service.list().map { it.key })
        }
    }
}
