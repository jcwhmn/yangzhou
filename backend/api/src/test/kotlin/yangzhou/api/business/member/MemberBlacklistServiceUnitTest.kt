package yangzhou.api.business.member

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import yangzhou.persistence.MemberBlacklist
import yangzhou.persistence.repository.MemberBlacklistRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/** spec 0177(S1)§4.3:黑名单写语义——expires=now+CLI TTL;upsert 刷 createdAt(每次事件重踢)。 */
class MemberBlacklistServiceUnitTest {
    private val repo = mockk<MemberBlacklistRepository>(relaxed = true)
    private val fixed = Instant.parse("2026-11-29T08:00:00Z")

    private fun service(ttlHours: Long = 720): MemberBlacklistService {
        // relaxed 泛型 save 返回 Object 会 CCE(同 AuthServiceUnitTest 先例),显式回传实参
        every { repo.save(any()) } answers { firstArg<MemberBlacklist>() }
        return MemberBlacklistService(repo, Clock.fixed(fixed, ZoneId.of("UTC")), ttlHours)
    }

    @Test
    fun `upsert 新行 expires 等于 now 加 CLI TTL,createdAt 等于 now`() {
        every { repo.findByMemberId(any()) } returns null // relaxed 对可空返回也会给 child mock,须显式 null
        service(ttlHours = 720).upsert(5L)
        val saved = slot<MemberBlacklist>()
        verify { repo.save(capture(saved)) }
        assertEquals(fixed, saved.captured.createdAt)
        assertEquals(fixed + Duration.ofHours(720), saved.captured.expiresAt)
    }

    @Test
    fun `upsert 既有行刷新 createdAt,不沿用旧行踢出时刻`() {
        // 旧行周一踢;周五再改密 → 周二~周四签发的 token 也必须死 → createdAt 必须刷成 now
        every { repo.findByMemberId(5L) } returns
            MemberBlacklist(memberId = 5L, expiresAt = fixed, createdAt = fixed.minusSeconds(86400))
        service().upsert(5L)
        val saved = slot<MemberBlacklist>()
        verify { repo.save(capture(saved)) }
        assertEquals(fixed, saved.captured.createdAt)
        assertEquals(fixed + Duration.ofHours(720), saved.captured.expiresAt)
    }

    @Test
    fun `clear 删行`() {
        service().clear(5L)
        verify { repo.deleteByMemberId(5L) }
    }
}
