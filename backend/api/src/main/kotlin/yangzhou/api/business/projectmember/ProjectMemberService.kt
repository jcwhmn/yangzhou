package yangzhou.api.business.projectmember

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.api.guard.LeaderGuard
import yangzhou.persistence.Member
import yangzhou.persistence.ProjectMember
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.ProjectMemberRepository
import yangzhou.persistence.repository.ProjectRepository
import java.util.UUID

/**
 * 项目成员池(V3.5-B → 0047 严格化):项目内「合法指派范围」。
 * 恒校验:池内成员可指派/进候选;空池 = 无人可指派/无候选。
 * 指派(ItemService)与候选(CandidateService)共用 [restrictedPool]。
 */
@Service
class ProjectMemberService(
    private val leaderGuard: LeaderGuard,
    private val projects: ProjectRepository,
    private val members: MemberRepository,
    private val projectMembers: ProjectMemberRepository,
) {
    data class MemberDto(
        val memberId: UUID,
        val displayName: String,
        val virtual: Boolean,
        val role: String,
    )

    @Transactional
    fun add(
        projectKey: String,
        memberId: UUID,
    ): MemberDto {
        leaderGuard.requireLeader(projectKey)
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        val member = members.findByObjectId(memberId) ?: throw NotFoundException("成员不存在")
        if (projectMembers.existsByProjectObjectIdAndMemberObjectId(project.objectId!!, member.objectId!!)) {
            throw ConflictException("该成员已在项目成员中")
        }
        projectMembers.save(ProjectMember(projectObjectId = project.objectId!!, memberObjectId = member.objectId!!))
        return member.toDto(ProjectMember.ROLE_MEMBER)
    }

    /** 按加入顺序(id 自增即加入序)。 */
    fun list(projectKey: String): List<MemberDto> {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        return projectMembers.findByProjectObjectIdOrderByIdAsc(project.objectId!!).map {
            (members.findByObjectId(it.memberObjectId) ?: throw NoSuchElementException("No value present")).toDto(it.role) // FK 保证成员在
        }
    }

    @Transactional
    fun remove(
        projectKey: String,
        memberId: UUID,
    ) {
        leaderGuard.requireLeader(projectKey)
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        val member = members.findByObjectId(memberId) ?: throw NotFoundException("成员不存在")
        val row =
            projectMembers.findByProjectObjectIdAndMemberObjectId(project.objectId!!, member.objectId!!)
                ?: throw NotFoundException("该成员不在项目成员中")
        requireNotLastLeader(row)
        projectMembers.delete(row)
    }

    /** YPJ-102(spec 0081):设/撤 leader;唯一 leader 降级 409(防静默开放管理)。 */
    @Transactional
    fun setRole(
        projectKey: String,
        memberId: UUID,
        role: String,
    ): MemberDto {
        leaderGuard.requireLeader(projectKey)
        if (role != ProjectMember.ROLE_MEMBER && role != ProjectMember.ROLE_LEADER) {
            throw BadRequestException("role 只能是 leader 或 member")
        }
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        val member = members.findByObjectId(memberId) ?: throw NotFoundException("成员不存在")
        val row =
            projectMembers.findByProjectObjectIdAndMemberObjectId(project.objectId!!, member.objectId!!)
                ?: throw NotFoundException("该成员不在项目成员中")
        if (row.role == ProjectMember.ROLE_LEADER && role == ProjectMember.ROLE_MEMBER) requireNotLastLeader(row)
        projectMembers.save(row.copy(role = role))
        return member.toDto(role)
    }

    private fun requireNotLastLeader(row: ProjectMember) {
        if (row.role == ProjectMember.ROLE_LEADER &&
            projectMembers.findByProjectObjectId(row.projectObjectId).count { it.role == ProjectMember.ROLE_LEADER } <= 1
        ) {
            throw ConflictException("最后一个 leader 不可降级/移出,先设好接任者")
        }
    }

    /** 池规则共用点(0047 严格化):恒返回池内成员 id;空池 = 无人可指派/无候选。 */
    fun restrictedPool(projectObjectId: UUID): Set<UUID> =
        projectMembers.findByProjectObjectId(projectObjectId).mapTo(HashSet()) { it.memberObjectId }

    /** 池外指派 fail-fast(409)。 */
    fun assertAssignable(
        projectObjectId: UUID,
        memberId: UUID,
    ) {
        if (memberId !in restrictedPool(projectObjectId)) throw ConflictException("该成员不在项目成员中")
    }

    private fun Member.toDto(role: String = ProjectMember.ROLE_MEMBER) =
        MemberDto(objectId, displayName, virtual = passwordHash == null, role = role)
}
