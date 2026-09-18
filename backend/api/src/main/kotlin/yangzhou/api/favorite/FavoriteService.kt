package yangzhou.api.favorite

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
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

@RestController
@RequestMapping("/api")
class FavoriteController(private val service: FavoriteService) {

    @GetMapping("/favorites")
    fun list(): List<FavoriteService.FavoriteDto> = service.list()

    @PutMapping("/projects/{key}/favorite")
    fun add(@PathVariable key: String): FavoriteService.FavoriteDto = service.add(key)

    @DeleteMapping("/projects/{key}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(@PathVariable key: String) = service.remove(key)
}
