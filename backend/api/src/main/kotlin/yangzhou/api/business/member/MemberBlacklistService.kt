package yangzhou.api.business.member

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.persistence.MemberBlacklist
import yangzhou.persistence.repository.MemberBlacklistRepository
import java.time.Clock
import java.time.Duration

/**
 * 黑名单写语义(spec 0177 §4.3,Q13/Q17 用户设计):禁用/重置密码/自助改密 upsert(踢全部会话),
 * 复职删行;行自动过期(expires = 入列时刻 + CLI TTL),过期行由请求链读时顺带删,无定时任务。
 * 读侧(exists 有效行)住 JwtAuthFilter,本服务只管写。
 */
@Service
class MemberBlacklistService(
    private val blacklists: MemberBlacklistRepository,
    private val clock: Clock,
    @Value("\${yangzhou.jwt.ttl-hours-cli:\${yangzhou.jwt.ttl-hours}}") cliTtlHours: Long,
) {
    /** 与 cli token 同寿命:黑名单窗 = CLI TTL(30d),过期后该成员旧 token 本身也已过期(§4.3)。 */
    private val ttl: Duration = Duration.ofHours(cliTtlHours)

    /** 禁用/重置密码/自助改密:upsert(每成员至多一行,唯一约束)。createdAt 一并刷新——每次事件重踢(行间新签 token 也得死);截秒在 filter 比较处统一做。 */
    @Transactional
    fun upsert(memberId: Long) {
        val existing = blacklists.findByMemberId(memberId)
        val now = clock.instant()
        val row =
            if (existing != null) {
                existing.copy(createdAt = now, expiresAt = now + ttl)
            } else {
                MemberBlacklist(memberId = memberId, expiresAt = now + ttl, createdAt = now)
            }
        blacklists.save(row)
    }

    /** 复职:删行(用户强调,§4.3)。 */
    @Transactional
    fun clear(memberId: Long) {
        blacklists.deleteByMemberId(memberId)
    }
}
