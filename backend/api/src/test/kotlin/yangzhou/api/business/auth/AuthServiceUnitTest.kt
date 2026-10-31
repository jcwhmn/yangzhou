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
import yangzhou.api.security.JwtService
import yangzhou.persistence.Member
import yangzhou.persistence.Workspace
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.WorkspaceRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-89 单测补齐:AuthService(bootstrap 一次性/登录凭据校验)。 */
class AuthServiceUnitTest {
    private val members = mockk<MemberRepository>(relaxed = true)
    private val workspaces = mockk<WorkspaceRepository>(relaxed = true)
    private val workspaceService = mockk<WorkspaceService>(relaxed = true)
    private val encoder = mockk<PasswordEncoder>(relaxed = true)
    private val jwt = mockk<JwtService>(relaxed = true)
    private val service = AuthService(members, workspaces, workspaceService, encoder, jwt)

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
        clearMocks(members, workspaces, workspaceService, encoder, jwt)
        every { workspaceService.ensureDefault() } returns Workspace(id = 9L)
        every { encoder.encode(any()) } returns "hash"
        every { jwt.generate(any()) } returns "token-xyz"
        every { members.save(any()) } answers { firstArg<Member>() }
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
        fun successIssuesToken() {
            every { members.findByUsername("me") } returns me
            every { encoder.matches("pw", "hash") } returns true
            val dto = service.login("me", "pw")
            assertEquals("token-xyz", dto.token)
            verify { jwt.generate(me.objectId.toString()) }
        }
    }
}
