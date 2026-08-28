package yangzhou.api.member

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.support.ConflictException
import yangzhou.api.support.NotFoundException
import yangzhou.api.workspace.WorkspaceService
import yangzhou.persistence.Member
import yangzhou.persistence.repository.CapabilityRepository
import yangzhou.persistence.repository.MemberRepository
import java.util.UUID

/**
 * 成员目录(V2):我(bootstrap 真人)+ 虚拟成员(password_hash 空,只参与匹配不登录)。
 * 登录的"我" = 唯一有凭据的成员;JWT subject 即其 objectId。
 */
@Service
class MemberService(
    private val members: MemberRepository,
    private val capabilities: CapabilityRepository,
    private val workspaceService: WorkspaceService,
) {

    data class MemberResponse(
        val memberId: UUID,
        val displayName: String,
        val username: String?,
        val virtual: Boolean,
    )

    /** 登录成员 = 唯一有凭据者(虚拟成员出现后,findAll().first() 不再可靠)。 */
    fun current(): Member =
        members.findAll().firstOrNull { it.passwordHash != null }
            ?: throw NotFoundException("尚未初始化,请先 bootstrap")

    fun list(): List<MemberResponse> =
        members.findByWorkspaceId(workspaceService.required().id!!)
            .map { it.toResponse() }

    /** 建虚拟成员:纯数据人,无凭据无登录;能力自评走 /api/members/{id}/capabilities。 */
    @Transactional
    fun createVirtual(displayName: String): MemberResponse {
        val saved = members.save(
            Member(
                workspaceId = workspaceService.required().id!!,
                username = null,
                passwordHash = null,
                displayName = displayName,
            ),
        )
        return saved.toResponse()
    }

    /** 删除成员:仅虚拟成员可删(我自己不可删);能力随删,team_member 由 FK 级联。 */
    @Transactional
    fun delete(memberId: UUID) {
        val member = members.findByObjectId(memberId) ?: throw NotFoundException("成员不存在")
        if (member.passwordHash != null) throw ConflictException("登录账号不可删除")
        capabilities.deleteByMemberId(member.id!!)
        members.delete(member)
    }

    fun requireMember(memberId: UUID): Member =
        members.findByObjectId(memberId) ?: throw NotFoundException("成员不存在")

    private fun Member.toResponse() =
        MemberResponse(objectId, displayName, username, virtual = passwordHash == null)
}
