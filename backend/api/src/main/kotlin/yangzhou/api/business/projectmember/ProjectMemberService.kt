package yangzhou.api.business.projectmember

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.api.guard.ProjectGuard
import yangzhou.persistence.Member
import yangzhou.persistence.MemberRole
import yangzhou.persistence.ProjectMember
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.MemberRoleRepository
import yangzhou.persistence.repository.ProjectMemberRepository
import yangzhou.persistence.repository.ProjectRepository
import java.util.UUID

/**
 * 项目成员池(V3.5-B → 0047 严格化):项目内「合法指派范围」。
 * 恒校验:池内成员可指派/进候选;空池 = 无人可指派/无候选。
 * 指派(ItemService)与候选(CandidateService)共用 [restrictedPool]。
 *
 * S3(YPJ-299,spec 0177 §3.2/§5):池表去角色化——池行只表成员资格(可见性+item 级门),
 * editor/viewer 授/收写 member_role;矩阵行 21。「最后 leader 保护」随 R2 废除删除(admin 兜底)。
 */
@Service
class ProjectMemberService(
    private val projectGuard: ProjectGuard,
    private val projects: ProjectRepository,
    private val members: MemberRepository,
    private val projectMembers: ProjectMemberRepository,
    private val memberRoles: MemberRoleRepository,
) {
    data class MemberDto(
        val memberId: UUID,
        val displayName: String,
        val virtual: Boolean,
        /** editor | viewer | [ROLE_POOL](池成员无角色;item 级待遇同 viewer,无管理权)。 */
        val role: String,
    )

    @Transactional
    fun add(
        projectKey: String,
        memberId: UUID,
    ): MemberDto {
        projectGuard.requireEditor(projectKey)
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        val member = members.findByObjectId(memberId) ?: throw NotFoundException("成员不存在")
        if (projectMembers.existsByProjectObjectIdAndMemberObjectId(project.objectId!!, member.objectId!!)) {
            throw ConflictException("该成员已在项目成员中")
        }
        projectMembers.save(ProjectMember(projectObjectId = project.objectId!!, memberObjectId = member.objectId!!))
        return member.toDto()
    }

    /** 按加入顺序(id 自增即加入序)。 */
    fun list(projectKey: String): List<MemberDto> {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        return projectMembers.findByProjectObjectIdOrderByIdAsc(project.objectId!!).map {
            val member =
                members.findByObjectId(it.memberObjectId) ?: throw NoSuchElementException("No value present")
            member.toDto(poolRoleOf(project.id!!, member.id!!)) // FK 保证成员在
        }
    }

    @Transactional
    fun remove(
        projectKey: String,
        memberId: UUID,
    ) {
        projectGuard.requireEditor(projectKey)
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        val member = members.findByObjectId(memberId) ?: throw NotFoundException("成员不存在")
        val row =
            projectMembers.findByProjectObjectIdAndMemberObjectId(project.objectId!!, member.objectId!!)
                ?: throw NotFoundException("该成员不在项目成员中")
        projectMembers.delete(row)
        // 出池即撤角色(旧模型池行=角色行,语义等价;角色行保留会孤立出「出池仍 editor」的怪态)
        memberRoles.findByMemberIdAndProjectId(member.id!!, project.id!!)
            .filter { it.role == MemberRole.ROLE_EDITOR || it.role == MemberRole.ROLE_VIEWER }
            .forEach { memberRoles.delete(it) }
    }

    /** S3 授/收角色:editor|viewer = upsert member_role 行(同项目唯一,先清后插);[ROLE_POOL] = 撤角色(删行,池行保留)。 */
    @Transactional
    fun setRole(
        projectKey: String,
        memberId: UUID,
        role: String,
    ): MemberDto {
        projectGuard.requireEditor(projectKey)
        if (role != MemberRole.ROLE_EDITOR && role != MemberRole.ROLE_VIEWER && role != ROLE_POOL) {
            throw BadRequestException("role 只能是 editor、viewer 或 member")
        }
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        val member = members.findByObjectId(memberId) ?: throw NotFoundException("成员不存在")
        projectMembers.findByProjectObjectIdAndMemberObjectId(project.objectId!!, member.objectId!!)
            ?: throw NotFoundException("该成员不在项目成员中")
        if (member.passwordHash == null && role != ROLE_POOL) {
            throw BadRequestException("虚拟成员只进池不进 role(Q19)") // 开放决策 3
        }
        memberRoles.findByMemberIdAndProjectId(member.id!!, project.id!!)
            .filter { it.role == MemberRole.ROLE_EDITOR || it.role == MemberRole.ROLE_VIEWER }
            .forEach { memberRoles.delete(it) }
        if (role != ROLE_POOL) {
            memberRoles.save(MemberRole(memberId = member.id!!, role = role, projectId = project.id!!))
        }
        return member.toDto(if (role == ROLE_POOL) ROLE_POOL else role)
    }

    /** 池规则共用点(0047 严格化):恒返回池内成员 id;空池 = 无人可指派/无候选。 */
    fun restrictedPool(projectObjectId: UUID): Set<UUID> = projectMembers.findByProjectObjectId(projectObjectId).mapTo(HashSet()) { it.memberObjectId }

    /** 池外指派 fail-fast(409)。 */
    fun assertAssignable(
        projectObjectId: UUID,
        memberId: UUID,
    ) {
        if (memberId !in restrictedPool(projectObjectId)) throw ConflictException("该成员不在项目成员中")
    }

    private fun poolRoleOf(projectId: Long, memberId: Long): String = memberRoles.findByMemberIdAndProjectId(memberId, projectId)
        .firstOrNull { it.role == MemberRole.ROLE_EDITOR || it.role == MemberRole.ROLE_VIEWER }?.role ?: ROLE_POOL

    private fun Member.toDto(role: String = ROLE_POOL) = MemberDto(objectId, displayName, virtual = passwordHash == null, role = role)

    companion object {
        /** 池成员无角色哨兵值(非 member_role 角色枚举)。 */
        const val ROLE_POOL = "member"
    }
}
