package yangzhou.api.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import yangzhou.persistence.MemberStatus
import yangzhou.persistence.repository.MemberBlacklistRepository
import yangzhou.persistence.repository.MemberRepository
import java.time.Clock
import java.util.UUID

/**
 * 请求链(spec 0177 §4.2):验签+exp → member 按 sub 存在(删号即拒)→ status == active(禁用即拒)
 * → 黑名单无有效行(expires_at > now 即拒;过期行顺带删,惰性清理 §4.3)→ principal=member.objectId。
 * 两次单行索引查询(spec §4.2 允许「实现定」);任一不过 → 不置身份,由 entry point 统一 401。
 */
@Component
class JwtAuthFilter(
    private val jwt: JwtService,
    private val members: MemberRepository,
    private val blacklists: MemberBlacklistRepository,
    private val clock: Clock,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        request
            .getHeader("Authorization")
            ?.takeIf { it.startsWith("Bearer ") }
            ?.let { jwt.verify(it.removePrefix("Bearer ")) }
            ?.let { subject ->
                val member =
                    runCatching { UUID.fromString(subject) }.getOrNull()
                        ?.let { members.findByObjectId(it) }
                if (member != null && member.status == MemberStatus.ACTIVE && !blacklisted(member.id!!)) {
                    SecurityContextHolder.getContext().authentication =
                        UsernamePasswordAuthenticationToken(member.objectId.toString(), null, emptyList())
                }
            }
        chain.doFilter(request, response)
    }

    /** 有效行(expires_at > now)即拒;过期行无害,读时顺带删(spec §4.3 惰性清理,无定时任务)。 */
    private fun blacklisted(memberId: Long): Boolean {
        val row = blacklists.findByMemberId(memberId) ?: return false
        if (row.expiresAt.isAfter(clock.instant())) return true
        blacklists.delete(row)
        return false
    }
}
