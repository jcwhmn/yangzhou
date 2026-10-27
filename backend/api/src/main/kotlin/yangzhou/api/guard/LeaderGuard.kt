package yangzhou.api.guard

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import yangzhou.api.error.ForbiddenException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.ProjectMember
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.ProjectMemberRepository
import yangzhou.persistence.repository.ProjectRepository
import java.util.UUID

/**
 * 项目管理门槛(YPJ-102,spec 0081)。
 * R1「创建即管」:建项目者自动入池且为 leader(落在 ProjectService.create)。
 * R2「无 leader 兼容」:项目无 leader 行时放行(与历史「登录即可管」一致,保单人/存量零感知);
 * 有 leader 时仅 leader 可管理。身份取 JWT principal(member objectId)——
 * 不取 MemberService.current()(那是「唯一凭据者」,多登录者时会身份错认)。
 */
@Service
class LeaderGuard(
    private val projects: ProjectRepository,
    private val projectMembers: ProjectMemberRepository,
    private val members: MemberRepository,
) {

    /** 项目 key 入口(NotFound 先于 403)。 */
    fun requireLeader(projectKey: String) {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        requireLeader(project.id!!)
    }

    fun requireLeader(projectId: Long) {
        val rows = projectMembers.findByProjectId(projectId)
        if (rows.none { it.role == ProjectMember.ROLE_LEADER }) return // R2
        if (rows.none { it.role == ProjectMember.ROLE_LEADER && it.memberId == currentMemberId() })
            throw ForbiddenException("需要项目 leader 权限")
    }

    private fun currentMemberId(): Long {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? String
            ?: throw ForbiddenException("需要项目 leader 权限")
        val objectId = runCatching { UUID.fromString(principal) }.getOrNull()
            ?: throw ForbiddenException("需要项目 leader 权限")
        return members.findByObjectId(objectId)?.id
            ?: throw ForbiddenException("需要项目 leader 权限")
    }
}
