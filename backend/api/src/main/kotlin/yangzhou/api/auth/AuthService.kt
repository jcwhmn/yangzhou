package yangzhou.api.auth

import jakarta.validation.constraints.NotBlank
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.config.JwtService
import yangzhou.api.workspace.WorkspaceService
import yangzhou.api.support.ConflictException
import yangzhou.persistence.MemberEntity
import yangzhou.persistence.repository.MemberRepository
import org.springframework.security.authentication.BadCredentialsException

@Service
class AuthService(
    private val members: MemberRepository,
    private val workspaceService: WorkspaceService,
    private val encoder: PasswordEncoder,
    private val jwt: JwtService,
) {

    data class TokenResponse(val token: String, val memberId: String, val username: String)

    @Transactional
    fun bootstrap(username: String, password: String): TokenResponse {
        if (members.count() > 0) throw ConflictException("已初始化,请直接登录")
        val workspace = workspaceService.ensureDefault()
        val member = members.save(
            MemberEntity(
                workspaceId = workspace.id!!,
                username = username,
                passwordHash = encoder.encode(password),
                displayName = username,
            ),
        )
        return tokenFor(member)
    }

    fun login(username: String, password: String): TokenResponse {
        val member = members.findByUsername(username)
            ?.takeIf { encoder.matches(password, it.passwordHash) }
            ?: throw BadCredentialsException("用户名或密码错误")
        return tokenFor(member)
    }

    private fun tokenFor(member: MemberEntity) =
        TokenResponse(
            token = jwt.generate(member.objectId.toString()),
            memberId = member.objectId.toString(),
            username = member.username,
        )
}

data class BootstrapRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val password: String,
)
