package yangzhou.api.projectmember

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.support.ConflictException
import yangzhou.api.support.NotFoundException
import yangzhou.persistence.Member
import yangzhou.persistence.ProjectMember
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.ProjectMemberRepository
import yangzhou.persistence.repository.ProjectRepository
import java.util.UUID

/**
 * 项目成员池(V3.5-B):项目的"合法指派范围"。
 * 语义(Q6 同款):表无行 = 全 workspace 池可用(存量项目零配置);
 * 有行 = 仅池内可指派/进候选。指派(ItemService)与候选(CandidateService)共用 [restrictedPool]。
 */
@Service
class ProjectMemberService(
    private val projects: ProjectRepository,
    private val members: MemberRepository,
    private val projectMembers: ProjectMemberRepository,
) {

    data class MemberDto(val memberId: UUID, val displayName: String, val virtual: Boolean)

    @Transactional
    fun add(projectKey: String, memberId: UUID): MemberDto {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        val member = members.findByObjectId(memberId) ?: throw NotFoundException("成员不存在")
        if (projectMembers.existsByProjectIdAndMemberId(project.id!!, member.id!!))
            throw ConflictException("该成员已在项目成员中")
        projectMembers.save(ProjectMember(projectId = project.id!!, memberId = member.id!!))
        return member.toDto()
    }

    /** 按加入顺序(id 自增即加入序)。 */
    fun list(projectKey: String): List<MemberDto> {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        return projectMembers.findByProjectIdOrderByIdAsc(project.id!!).map {
            members.findById(it.memberId).orElseThrow().toDto() // FK 保证成员在
        }
    }

    @Transactional
    fun remove(projectKey: String, memberId: UUID) {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        val member = members.findByObjectId(memberId) ?: throw NotFoundException("成员不存在")
        val row = projectMembers.findByProjectIdAndMemberId(project.id!!, member.id!!)
            ?: throw NotFoundException("该成员不在项目成员中")
        projectMembers.delete(row)
    }

    /** 池规则共用点:null = 未配置池(全 workspace 池可用);非空 = 仅池内成员 id。 */
    fun restrictedPool(projectId: Long): Set<Long>? =
        projectMembers.findByProjectId(projectId).takeIf { it.isNotEmpty() }?.mapTo(HashSet()) { it.memberId }

    /** 池外指派 fail-fast(409);未配置池时全放行。 */
    fun assertAssignable(projectId: Long, memberId: Long) {
        val pool = restrictedPool(projectId)
        if (pool != null && memberId !in pool) throw ConflictException("该成员不在项目成员中")
    }

    private fun Member.toDto() = MemberDto(objectId, displayName, virtual = passwordHash == null)
}
