package yangzhou.api.business.favorite

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.business.member.MemberService
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Favorite
import yangzhou.persistence.repository.FavoriteRepository
import yangzhou.persistence.repository.ProjectRepository
import java.util.UUID

/** V9-S2 收藏(服务端存储,跨浏览器/重装持久;V9-Q12)。 */
@Service
class FavoriteService(
    private val favorites: FavoriteRepository,
    private val projects: ProjectRepository,
    private val memberService: MemberService,
) {

    data class FavoriteDto(val projectId: UUID, val key: String, val name: String)

    @Transactional
    fun add(projectKey: String): FavoriteDto {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        val meId = memberService.current().objectId!!
        val pid = project.objectId!!
        if (!favorites.existsByMemberObjectIdAndProjectObjectId(meId, pid)) {
            favorites.save(Favorite(memberObjectId = meId, projectObjectId = pid))
        }
        return FavoriteDto(project.objectId, project.key, project.name)
    }

    @Transactional
    fun remove(projectKey: String) {
        val meId = memberService.current().objectId!!
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        favorites.findByMemberObjectIdAndProjectObjectId(meId, project.objectId!!)?.let { favorites.delete(it) }
    }

    fun list(): List<FavoriteDto> {
        val meId = memberService.current().objectId!!
        return favorites.findByMemberObjectIdOrderByCreatedAtAsc(meId).mapNotNull { f ->
            projects.findByObjectId(f.projectObjectId)?.let { FavoriteDto(it.objectId, it.key, it.name) }
        }
    }
}
