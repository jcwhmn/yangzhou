package yangzhou.api.business.auth

import jakarta.validation.constraints.NotBlank
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.business.workspace.WorkspaceService
import yangzhou.api.error.ConflictException
import yangzhou.api.security.ClientType
import yangzhou.api.security.JwtService
import yangzhou.persistence.Member
import yangzhou.persistence.MemberRole
import yangzhou.persistence.MemberStatus
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.MemberRoleRepository
import yangzhou.persistence.repository.WorkspaceRepository

@Service
class AuthService(
    private val members: MemberRepository,
    private val workspaces: WorkspaceRepository,
    private val workspaceService: WorkspaceService,
    private val encoder: PasswordEncoder,
    private val jwt: JwtService,
    private val memberRoles: MemberRoleRepository,
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
        clientType: ClientType = ClientType.WEB,
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
        // spec 0177(S1):首账号必然全权——同时落 (admin, NULL) 行(§2.3 A 表;R7 admin 兕底)
        memberRoles.save(MemberRole(memberId = member.id!!, role = MemberRole.ROLE_ADMIN))
        return tokenFor(member, clientType)
    }

    fun login(
        username: String,
        password: String,
        clientType: ClientType = ClientType.WEB,
    ): TokenResponse {
        val member =
            members
                .findByUsername(username)
                ?.takeIf { encoder.matches(password, it.passwordHash) }
                ?: throw BadCredentialsException("用户名或密码错误")
        // 密码对了才暴露状态语义:disabled 账号登录 → 401「账号已禁用」(spec §2.3 A 表,不与凭据错误混文案)
        if (member.status == MemberStatus.DISABLED) throw BadCredentialsException("账号已禁用")
        return tokenFor(member, clientType)
    }

    private fun tokenFor(
        member: Member,
        clientType: ClientType,
    ) = TokenResponse(
        token = jwt.generate(member.objectId.toString(), clientType),
        memberId = member.objectId.toString(),
        username = member.username ?: member.displayName,
    )
}

data class BootstrapRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val password: String,
    val clientType: String? = null,
)

/** 登录不复用 BootstrapRequest:密码长度策略只管创建,存量短密码也要能登录(401 而非 400)。 */
data class LoginRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val password: String,
    val clientType: String? = null,
)
