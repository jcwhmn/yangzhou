package yangzhou.api.guard

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import yangzhou.api.error.ForbiddenException
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
    fun requireAdmin() {
        val objectId = currentMemberObjectId()
        val member = members.findByObjectId(objectId) ?: throw ForbiddenException(MESSAGE)
        val isAdmin =
            memberRoles
                .findByMemberId(member.id!!)
                .any { it.role == MemberRole.ROLE_ADMIN && it.projectId == null }
        if (!isAdmin) throw ForbiddenException(MESSAGE)
    }

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
