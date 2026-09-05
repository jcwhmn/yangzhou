package yangzhou.api.projectmember

import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 项目成员池(V3.5-B):POST/GET/DELETE /api/projects/{key}/members。 */
@RestController
@RequestMapping("/api")
class ProjectMemberController(private val service: ProjectMemberService) {

    @PostMapping("/projects/{key}/members")
    @ResponseStatus(HttpStatus.CREATED)
    fun add(@PathVariable key: String, @Valid @RequestBody request: AddProjectMemberRequest): ProjectMemberService.MemberDto =
        service.add(key, request.memberId)

    @GetMapping("/projects/{key}/members")
    fun list(@PathVariable key: String): List<ProjectMemberService.MemberDto> = service.list(key)

    @DeleteMapping("/projects/{key}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(@PathVariable key: String, @PathVariable memberId: UUID) = service.remove(key, memberId)
}

data class AddProjectMemberRequest(@field:NotNull val memberId: UUID)
