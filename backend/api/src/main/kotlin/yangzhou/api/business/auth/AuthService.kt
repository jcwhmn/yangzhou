package yangzhou.api.business.auth

import jakarta.validation.constraints.NotBlank
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.business.workspace.WorkspaceService
import yangzhou.api.error.ConflictException
import yangzhou.api.security.JwtService
import yangzhou.persistence.Member
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.WorkspaceRepository

@Service
class AuthService(
    private val members: MemberRepository,
    private val workspaces: WorkspaceRepository,
    private val workspaceService: WorkspaceService,
    private val encoder: PasswordEncoder,
    private val jwt: JwtService,
) {
    data class TokenResponse(
        val token: String,
        val memberId: String,
        val username: String,
    )

    @Transactional
    fun bootstrap(
        username: String,
        password: String,
    ): TokenResponse {
        // D6(spec 0052):advisory 事务锁串行化并发 bootstrap,双请求不再能造出两个管理员
        workspaces.lockBootstrap()
        if (members.count() > 0) throw ConflictException("已初始化,请直接登录")
        val workspace = workspaceService.ensureDefault()
        val member =
            members.save(
                Member(
                    workspaceObjectId = workspace.objectId!!,
                    username = username,
                    passwordHash = encoder.encode(password),
                    displayName = username,
                ),
            )
        return tokenFor(member)
    }

    fun login(
        username: String,
        password: String,
    ): TokenResponse {
        val member =
            members
                .findByUsername(username)
                ?.takeIf { encoder.matches(password, it.passwordHash) }
                ?: throw BadCredentialsException("用户名或密码错误")
        return tokenFor(member)
    }

    private fun tokenFor(member: Member) =
        TokenResponse(
            token = jwt.generate(member.objectId.toString()),
            memberId = member.objectId.toString(),
            username = member.username ?: member.displayName,
        )
}

data class BootstrapRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val password: String,
)

/** 登录不复用 BootstrapRequest:密码长度策略只管创建,存量短密码也要能登录(401 而非 400)。 */
data class LoginRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val password: String,
)
