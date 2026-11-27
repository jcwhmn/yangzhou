package yangzhou.api.business.auth

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import yangzhou.api.business.workspace.WorkspaceService
import yangzhou.api.error.ConflictException
import yangzhou.api.security.ClientType
import yangzhou.api.security.JwtService
import yangzhou.persistence.Member
import yangzhou.persistence.MemberRole
import yangzhou.persistence.MemberStatus
import yangzhou.persistence.Workspace
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.MemberRoleRepository
import yangzhou.persistence.repository.WorkspaceRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-89 单测补齐:AuthService(bootstrap 一次性/登录凭据校验)。YPJ-288:disabled 拒登/bootstrap 落 admin 行/ct 分型。 */
class AuthServiceUnitTest {
    private val members = mockk<MemberRepository>(relaxed = true)
    private val workspaces = mockk<WorkspaceRepository>(relaxed = true)
    private val workspaceService = mockk<WorkspaceService>(relaxed = true)
    private val encoder = mockk<PasswordEncoder>(relaxed = true)
    private val jwt = mockk<JwtService>(relaxed = true)
    private val memberRoles = mockk<MemberRoleRepository>(relaxed = true)
    private val service = AuthService(members, workspaces, workspaceService, encoder, jwt, memberRoles)

    private val me =
        Member(
            id = 1L,
            objectId = UUID(0, 1),
            workspaceObjectId = UUID(0, 9),
            username = "me",
            passwordHash = "hash",
            displayName = "me",
        )

    @BeforeEach
    fun setup() {
        clearMocks(members, workspaces, workspaceService, encoder, jwt, memberRoles)
        every { workspaceService.ensureDefault() } returns Workspace(id = 9L)
        every { encoder.encode(any()) } returns "hash"
        every { jwt.generate(any(), any()) } returns "token-xyz"
        every { members.save(any()) } answers { firstArg<Member>().copy(id = 1L) }
        every { memberRoles.save(any()) } answers { firstArg() }
    }

    @Nested
    inner class Bootstrap {
        @Test
        fun secondBootstrapRejected() {
            every { members.count() } returns 1L
            assertFailsWith<ConflictException> { service.bootstrap("me", "pw") }
        }

        @Test
        fun firstBootstrapLocksSavesAndIssuesToken() {
            every { members.count() } returns 0L
            val dto = service.bootstrap("me", "pw")
            assertEquals("me", dto.username)
            assertEquals("token-xyz", dto.token)
            verify { workspaces.lockBootstrap() } // spec 0052 advisory 锁
        }

        @Test
        fun firstBootstrapFallsAdminRoleRow() {
            // spec 0177(S1):首账号必然全权——同时落 (admin, NULL) 行(§2.3 A 表)
            every { members.count() } returns 0L
            service.bootstrap("me", "pw")
            verify {
                memberRoles.save(
                    withArg {
                        assertEquals(1L, it.memberId)
                        assertEquals(MemberRole.ROLE_ADMIN, it.role)
                        assertEquals(null, it.projectId)
                    },
                )
            }
        }

        @Test
        fun clientTypePassedToToken() {
            every { members.count() } returns 0L
            service.bootstrap("me", "pw", ClientType.CLI)
            verify { jwt.generate(any(), ClientType.CLI) }
        }
    }

    @Nested
    inner class Login {
        @Test
        fun wrongPasswordRejected() {
            every { members.findByUsername("me") } returns me
            every { encoder.matches("bad", "hash") } returns false
            assertFailsWith<BadCredentialsException> { service.login("me", "bad") }
        }

        @Test
        fun unknownUserRejected() {
            every { members.findByUsername("nope") } returns null
            assertFailsWith<BadCredentialsException> { service.login("nope", "pw") }
        }

        @Test
        fun disabledAccountRejectedWithDistinctMessage() {
            // spec §2.3 A 表:密码对了才暴露状态语义,401「账号已禁用」(不与凭据错误混文案)
            val disabled = me.copy(status = MemberStatus.DISABLED)
            every { members.findByUsername("me") } returns disabled
            every { encoder.matches("pw", "hash") } returns true
            val e = assertFailsWith<BadCredentialsException> { service.login("me", "pw") }
            assertEquals("账号已禁用", e.message)
        }

        @Test
        fun disabledAccountWithWrongPasswordKeepsGenericMessage() {
            val disabled = me.copy(status = MemberStatus.DISABLED)
            every { members.findByUsername("me") } returns disabled
            every { encoder.matches("bad", "hash") } returns false
            val e = assertFailsWith<BadCredentialsException> { service.login("me", "bad") }
            assertEquals("用户名或密码错误", e.message)
        }

        @Test
        fun successIssuesToken() {
            every { members.findByUsername("me") } returns me
            every { encoder.matches("pw", "hash") } returns true
            val dto = service.login("me", "pw")
            assertEquals("token-xyz", dto.token)
            verify { jwt.generate(me.objectId.toString(), ClientType.WEB) }
        }

        @Test
        fun cliClientTypePassedToToken() {
            every { members.findByUsername("me") } returns me
            every { encoder.matches("pw", "hash") } returns true
            service.login("me", "pw", ClientType.CLI)
            verify { jwt.generate(me.objectId.toString(), ClientType.CLI) }
        }
    }
}
