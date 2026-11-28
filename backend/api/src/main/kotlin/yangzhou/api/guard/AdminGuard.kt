package yangzhou.api.guard

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import yangzhou.api.error.ForbiddenException
import yangzhou.persistence.Member
import yangzhou.persistence.MemberRole
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.MemberRoleRepository
import java.util.UUID

/**
 * workspace 级治理门(spec 0177 §5 三级守卫之一,YPJ-288 新立):
 * requireAdmin = (me, admin, NULL) 行判定;admin 兜底单缝纪律——兜底判定只住守卫函数内,
 * 业务代码禁止散落 admin 判断(§2 注)。身份取 JWT principal(member objectId),不取 MemberService.current()。
 */
@Service
class AdminGuard(
    private val members: MemberRepository,
    private val memberRoles: MemberRoleRepository,
) {
    /** admin 判定单缝(spec 0177 §5):whoami/最后 admin 计数等业务侧读写一律走此守卫,不散落查叉表。 */
    fun isAdmin(): Boolean {
        val objectId = currentMemberObjectId()
        val member = members.findByObjectId(objectId) ?: return false
        return isAdminMember(member)
    }

    fun requireAdmin() {
        if (!isAdmin()) throw ForbiddenException(MESSAGE)
    }

    /** workspace 级 admin 行数(最后 admin 守卫用)。 */
    fun countWorkspaceAdmins(): Int = memberRoles.findByRoleAndProjectIdIsNull(MemberRole.ROLE_ADMIN).size

    private fun isAdminMember(member: Member): Boolean = memberRoles
        .findByMemberId(member.id!!)
        .any { it.role == MemberRole.ROLE_ADMIN && it.projectId == null }

    private fun currentMemberObjectId(): UUID {
        val principal =
            SecurityContextHolder.getContext().authentication?.principal as? String
                ?: throw ForbiddenException(MESSAGE)
        return runCatching { UUID.fromString(principal) }.getOrNull()
            ?: throw ForbiddenException(MESSAGE)
    }

    private companion object {
        const val MESSAGE = "需要 workspace admin 权限"
    }
}
