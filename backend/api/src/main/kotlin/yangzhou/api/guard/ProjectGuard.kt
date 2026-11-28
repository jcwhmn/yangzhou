package yangzhou.api.guard

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import yangzhou.api.error.ForbiddenException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Member
import yangzhou.persistence.MemberRole
import yangzhou.persistence.Project
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.MemberRoleRepository
import yangzhou.persistence.repository.ProjectMemberRepository
import yangzhou.persistence.repository.ProjectRepository
import java.util.UUID

/**
 * 项目级授权门(spec 0177 §5 三级守卫之二,YPJ-299 新立):
 * - requireMember = 池行∪(editor/viewer)角色行∪admin,item 级操作与项目直访的门(行 7/8;item 级全量接线归 S4);
 * - requireEditor = (me, editor, project) 行∪admin,管理门(行 16-22);
 * - requireEditorAnywhere = admin∨≥1 条 editor 行,全局目录/建项目门(行 3b/25);
 * - requirePoolEditor = admin∨我与目标池行项目的 editor 交集,他人能力档案编辑门(行 5,C1)。
 * admin 兜底单缝纪律:一律走 AdminGuard.isAdmin(),业务代码禁止散落 admin 判断。身份取 JWT principal
 * (LeaderGuard 先例,不用 MemberService.current()——那是「唯一凭据者」,多登录者时会身份错认)。
 * NotFound 先于 403:项目不存在一律 404,不泄露存在性(项目级 403 文案区分:read 门「无权访问该项目」/edit 门「需要项目 editor 权限」)。
 */
@Service
class ProjectGuard(
    private val projects: ProjectRepository,
    private val projectMembers: ProjectMemberRepository,
    private val members: MemberRepository,
    private val memberRoles: MemberRoleRepository,
    private val adminGuard: AdminGuard,
) {
    fun requireMember(projectKey: String) {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException(MESSAGE_ACCESS)
        requireMember(project)
    }

    fun requireMember(projectObjectId: UUID) {
        val project = projects.findByObjectId(projectObjectId) ?: throw NotFoundException(MESSAGE_ACCESS)
        requireMember(project)
    }

    private fun requireMember(project: Project) {
        if (adminGuard.isAdmin()) return
        val me = currentMember()
        if (projectMembers.existsByProjectObjectIdAndMemberObjectId(project.objectId!!, me.objectId!!)) return
        if (hasProjectRole(project, me, MemberRole.ROLE_EDITOR, MemberRole.ROLE_VIEWER)) return
        throw ForbiddenException(MESSAGE_ACCESS)
    }

    fun requireEditor(projectKey: String) {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException(MESSAGE_EDIT)
        requireEditor(project)
    }

    fun requireEditor(projectObjectId: UUID) {
        val project = projects.findByObjectId(projectObjectId) ?: throw NotFoundException(MESSAGE_EDIT)
        requireEditor(project)
    }

    private fun requireEditor(project: Project) {
        if (adminGuard.isAdmin()) return
        val me = currentMember()
        if (hasProjectRole(project, me, MemberRole.ROLE_EDITOR)) return
        throw ForbiddenException(MESSAGE_EDIT)
    }

    /** 全局成员目录/建项目门(行 3b/25):admin ∨ 持有 ≥1 条 (editor, project) 行。 */
    fun requireEditorAnywhere() {
        if (adminGuard.isAdmin()) return
        val me = currentMember()
        if (memberRoles.findByMemberId(me.id!!).any { it.role == MemberRole.ROLE_EDITOR }) return
        throw ForbiddenException(MESSAGE_EDIT)
    }

    /** 他人能力档案/github-username 编辑门(行 5,C1):admin ∨ 目标池行与我 editor 行的项目交集非空。 */
    fun requirePoolEditor(targetMemberObjectId: UUID) {
        if (adminGuard.isAdmin()) return
        val me = currentMember()
        val myEditorProjectIds =
            memberRoles.findByMemberId(me.id!!)
                .filter { it.role == MemberRole.ROLE_EDITOR && it.projectId != null }
                .mapNotNull { it.projectId }
        if (myEditorProjectIds.isEmpty()) throw ForbiddenException(MESSAGE_EDIT)
        val targetPoolProjectIds = projectMembers.findByMemberObjectId(targetMemberObjectId)
            .mapNotNull { projects.findByObjectId(it.projectObjectId)?.id }
            .toSet()
        if (myEditorProjectIds.intersect(targetPoolProjectIds).isNotEmpty()) return
        throw ForbiddenException(MESSAGE_EDIT)
    }

    private fun hasProjectRole(project: Project, member: Member, vararg roles: String): Boolean {
        val pid = project.id ?: return false
        return memberRoles.findByMemberId(member.id!!).any {
            it.projectId == pid && it.role in roles
        }
    }

    private fun currentMember(): Member {
        val principal =
            SecurityContextHolder.getContext().authentication?.principal as? String
                ?: throw ForbiddenException(MESSAGE_ACCESS)
        val objectId =
            runCatching { UUID.fromString(principal) }.getOrNull()
                ?: throw ForbiddenException(MESSAGE_ACCESS)
        return members.findByObjectId(objectId)
            ?: throw ForbiddenException(MESSAGE_ACCESS)
    }

    private companion object {
        const val MESSAGE_ACCESS = "无权访问该项目"
        const val MESSAGE_EDIT = "需要项目 editor 权限"
    }
}
