package yangzhou.api.favorite

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.member.MemberService
import yangzhou.api.support.NotFoundException
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
        val meId = memberService.current().id!!
        val pid = project.id!!
        if (!favorites.existsByMemberIdAndProjectId(meId, pid)) {
            favorites.save(Favorite(memberId = meId, projectId = pid))
        }
        return FavoriteDto(project.objectId, project.key, project.name)
    }

    @Transactional
    fun remove(projectKey: String) {
        val meId = memberService.current().id!!
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        favorites.findByMemberIdAndProjectId(meId, project.id!!)?.let { favorites.delete(it) }
    }

    fun list(): List<FavoriteDto> {
        val meId = memberService.current().id!!
        return favorites.findByMemberIdOrderByCreatedAtAsc(meId).mapNotNull { f ->
            projects.findById(f.projectId).orElse(null)?.let { FavoriteDto(it.objectId, it.key, it.name) }
        }
    }
}
