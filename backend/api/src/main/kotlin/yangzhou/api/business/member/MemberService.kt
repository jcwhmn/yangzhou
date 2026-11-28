package yangzhou.api.business.member

import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.business.workspace.WorkspaceService
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.api.guard.AdminGuard
import yangzhou.persistence.Member
import yangzhou.persistence.MemberRole
import yangzhou.persistence.MemberStatus
import yangzhou.persistence.ProjectMember
import yangzhou.persistence.repository.CapabilityRepository
import yangzhou.persistence.repository.CommentRepository
import yangzhou.persistence.repository.ItemActivityRepository
import yangzhou.persistence.repository.ItemStatusHistoryRepository
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.NotificationRepository
import yangzhou.persistence.repository.ProjectMemberRepository
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.ProjectViewRepository
import yangzhou.persistence.repository.TimeEntryRepository
import java.time.Instant
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
    private val items: yangzhou.persistence.repository.ItemRepository,
    private val statuses: yangzhou.persistence.repository.StatusRepository,
    private val comments: CommentRepository,
    private val itemActivities: ItemActivityRepository,
    private val itemStatusHistories: ItemStatusHistoryRepository,
    private val timeEntries: TimeEntryRepository,
    private val projectViews: ProjectViewRepository,
    private val notifications: NotificationRepository,
    private val encoder: PasswordEncoder,
    private val blacklist: MemberBlacklistService,
    private val memberRoles: yangzhou.persistence.repository.MemberRoleRepository,
    private val projectMembers: ProjectMemberRepository,
    private val projects: ProjectRepository,
    private val adminGuard: AdminGuard,
) {
    data class MemberResponse(
        val memberId: UUID,
        val displayName: String,
        val username: String?,
        val virtual: Boolean,
        val githubUsername: String? = null,
        val color: String? = null,
        val lastProjectKey: String? = null,
        val status: String = "active",
    )

    /** S2(YPJ-293)whoami:前端导航/落地依赖;projectRoles S3 落 editor/viewer 行前恒空,形状现在定死。 */
    data class WhoamiResponse(
        val memberId: UUID,
        val displayName: String,
        val username: String?,
        val virtual: Boolean,
        val githubUsername: String? = null,
        val color: String? = null,
        val lastProjectKey: String? = null,
        val admin: Boolean,
        val projectRoles: List<ProjectRoleDto>,
    )

    data class ProjectRoleDto(
        val projectId: UUID,
        val role: String,
    )

    /** V10-S3 调色板轮转:按成员数取模,8 色循环。 */
    private fun nextColor(): String {
        val palette = listOf("#e57373", "#f06292", "#ba68c8", "#7986cb", "#64b5f6", "#4db6ac", "#ffd54f", "#a1887f")
        return palette[members.findAll().count() % palette.size]
    }

    /** 当前登录成员 = JWT subject(= member objectId;JwtAuthFilter 验签后写入 SecurityContext)。
     *  不得回退「第一个有密码的账号」:多登录账号时 findAll 堆序漂移会串号(YPJ-131 复验实测,
     *  me 行被 last-project 更新挪页后,views/能力/收藏等按 dev2 解析 → 视图空集/记忆失效)。 */
    fun current(): Member {
        val sub = SecurityContextHolder.getContext().authentication?.principal?.toString()
        val id = sub?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw NotFoundException("未认证")
        return members.findByObjectId(id) ?: throw NotFoundException("成员不存在")
    }

    fun list(): List<MemberResponse> = members
        .findByWorkspaceObjectId(workspaceService.required().objectId!!)
        .map { it.toResponse() }

    /**
     * 建成员(spec 0177 §4.4,S2 扩展):displayName 必填;username+password 成对提供=真人账号
     * (username 全局唯一 409、password≥8 由 Bean Validation 拦、必挂 ≥1 项目(C8,入 ProjectMember 池));
     * 均不给=虚拟成员(现状不变,不挂池——矩阵行 30 未标 C8,游客概念只对登录账号有意义)。
     */
    @Transactional
    fun create(
        displayName: String,
        username: String?,
        password: String?,
        projectIds: List<UUID>?,
    ): MemberResponse {
        val workspaceObjectId = workspaceService.required().objectId!!
        members
            .findByWorkspaceObjectId(workspaceObjectId)
            .firstOrNull { it.displayName == displayName }
            ?.let { throw ConflictException("成员已存在:$displayName") }
        val cleanedUsername = username?.trim()?.ifEmpty { null }
        val credentialed = cleanedUsername != null
        if ((cleanedUsername != null) != (password != null)) {
            throw BadRequestException("凭据需 username 与 password 同时提供")
        }
        if (credentialed) {
            if (projectIds.isNullOrEmpty()) throw BadRequestException("建登录账号必挂 ≥1 项目(C8)")
            members.findByUsername(cleanedUsername)?.let { throw ConflictException("用户名已被占用:$cleanedUsername") }
        } else if (!projectIds.isNullOrEmpty()) {
            throw BadRequestException("虚拟成员不接受 projectIds")
        }
        val saved =
            members.save(
                Member(
                    workspaceObjectId = workspaceObjectId,
                    username = cleanedUsername,
                    passwordHash = password?.let { encoder.encode(it) },
                    displayName = displayName,
                    color = nextColor(),
                ),
            )
        if (credentialed) {
            projectIds!!.forEach { pid -> poolMember(pid, saved.objectId!!) }
        }
        return saved.toResponse()
    }

    /** C8 建号即入池:项目不存在 404;已在池幂等跳过。 */
    private fun poolMember(
        projectId: UUID,
        memberObjectId: UUID,
    ) {
        val project = projects.findByObjectId(projectId) ?: throw NotFoundException("项目不存在:$projectId")
        if (!projectMembers.existsByProjectObjectIdAndMemberObjectId(project.objectId!!, memberObjectId)) {
            projectMembers.save(ProjectMember(projectObjectId = project.objectId!!, memberObjectId = memberObjectId))
        }
    }

    /**
     * 删除成员(spec 0177 §4.4,S2 放开真人可删):仅「自己」不可删(自删 409);能力随删,
     * team_member 由 FK 级联;登录账号删后 member 行没了,filter sub 存在性即拒(S1 链)。
     * V10-Q9:有非终态 assignee item → 409。
     */
    @Transactional
    fun delete(memberId: UUID) {
        val member = members.findByObjectId(memberId) ?: throw NotFoundException("成员不存在")
        if (member.objectId == current().objectId) throw ConflictException("不可删除自己")
        val activeItems =
            items
                .findByAssigneeObjectIdAndDeletedAtIsNull(member.objectId)
                .filter { item -> statuses.findByObjectId(item.statusObjectId)?.statusType != yangzhou.persistence.StatusType.FINAL }
        if (activeItems.isNotEmpty()) {
            throw ConflictException("该成员仍有进行中 item(${activeItems.size} 个),请先改派或完成")
        }
        // YPJ-60:restrict 引用非空即拒(直删会触发 DB 异常 500);附引用计数供清理决策
        val mid = member.objectId!!
        val refs =
            buildList {
                comments.countByAuthorMemberObjectId(mid).takeIf { it > 0 }?.let { add("评论 $it") }
                itemActivities.countByActorMemberObjectId(mid).takeIf { it > 0 }?.let { add("动态 $it") }
                itemStatusHistories.countByActorMemberObjectId(mid).takeIf { it > 0 }?.let { add("状态历史 $it") }
                timeEntries.countByMemberObjectId(mid).takeIf { it > 0 }?.let { add("工时 $it") }
                projectViews.countByCreatorObjectId(mid).takeIf { it > 0 }?.let { add("视图 $it") }
                notifications.countByActorMemberObjectId(mid).takeIf { it > 0 }?.let { add("通知 $it") }
            }
        if (refs.isNotEmpty()) {
            throw ConflictException("该成员仍有引用数据(${refs.joinToString("、")}),先删除相关项目或数据")
        }
        capabilities.deleteByMemberObjectId(mid)
        members.delete(member)
    }

    fun requireMember(memberId: UUID): Member = members.findByObjectId(memberId) ?: throw NotFoundException("成员不存在")

    /**
     * spec 0177(S1)§4.4:自助改密。验 old(错 → 401);改后黑名单 upsert——
     * 踢所有会话含当前,需重登(R7 拍板「踢」)。≥8 位由 Bean Validation 在请求线上拦。
     */
    @Transactional
    fun changeMyPassword(
        oldPassword: String,
        newPassword: String,
    ) {
        val member = current()
        // 登录账号必有 passwordHash(虚拟成员无法登录至此);防御式兜底同一文案
        if (member.passwordHash == null || !encoder.matches(oldPassword, member.passwordHash)) {
            throw BadCredentialsException("原密码错误")
        }
        members.save(member.copy(passwordHash = encoder.encode(newPassword), updatedAt = Instant.now()))
        blacklist.upsert(member.id!!)
    }

    /** S2(YPJ-293)whoami:身份 + admin 布尔(单缝走守卫)+ projectRoles(S3 前恒空)。 */
    fun whoami(): WhoamiResponse {
        val me = current()
        val roles = memberRoles.findByMemberId(me.id!!)
        val projectRoles =
            roles
                .filter { it.projectId != null }
                .mapNotNull { row ->
                    projects.findById(row.projectId!!).orElse(null)?.let { ProjectRoleDto(it.objectId!!, row.role) }
                }
        return WhoamiResponse(
            memberId = me.objectId!!,
            displayName = me.displayName,
            username = me.username,
            virtual = me.passwordHash == null,
            githubUsername = me.githubUsername,
            color = me.color,
            lastProjectKey = me.lastProjectKey,
            admin = adminGuard.isAdmin(),
            projectRoles = projectRoles,
        )
    }

    /**
     * S2 §4.4 重置凭据:真人=重置密码(+黑名单踢会话);虚拟=设凭据即升级(必须给 username,唯一 409)。
     */
    @Transactional
    fun resetCredentials(
        memberId: UUID,
        username: String?,
        password: String,
    ): MemberResponse {
        val member = requireMember(memberId)
        if (member.passwordHash == null) {
            val name = username?.trim()?.ifEmpty { null } ?: throw BadRequestException("虚拟成员升级需同时提供 username")
            members.findByUsername(name)?.let { if (it.objectId != member.objectId) throw ConflictException("用户名已被占用:$name") }
            return members.save(member.copy(username = name, passwordHash = encoder.encode(password), updatedAt = Instant.now())).toResponse()
        }
        val saved = members.save(member.copy(passwordHash = encoder.encode(password), updatedAt = Instant.now()))
        blacklist.upsert(member.id!!)
        return saved.toResponse()
    }

    /** S2 §4.4 禁用/复职:禁用=黑名单 upsert,复职=删行(方法 S1 已落);最后 admin 不可禁用自己 409。 */
    @Transactional
    fun setStatus(
        memberId: UUID,
        requested: String,
    ): MemberResponse {
        val status =
            when (requested.lowercase()) {
                "active" -> MemberStatus.ACTIVE
                "disabled" -> MemberStatus.DISABLED
                else -> throw BadRequestException("status 需为 active 或 disabled")
            }
        val member = requireMember(memberId)
        if (status == MemberStatus.DISABLED &&
            member.objectId == current().objectId &&
            adminGuard.isAdmin() &&
            adminGuard.countWorkspaceAdmins() == 1
        ) {
            throw ConflictException("最后 admin 不可禁用自己")
        }
        val saved = members.save(member.copy(status = status, updatedAt = Instant.now()))
        when (status) {
            MemberStatus.DISABLED -> blacklist.upsert(member.id!!)
            MemberStatus.ACTIVE -> blacklist.clear(member.id!!)
        }
        return saved.toResponse()
    }

    /** S2 §4.4 授/收 workspace admin:授=插 (admin, NULL) 行,收=删行,双向幂等;最后 admin 不可收 409。 */
    @Transactional
    fun setAdmin(
        memberId: UUID,
        admin: Boolean,
    ): MemberResponse {
        val member = requireMember(memberId)
        val targetId = member.id!!
        val existing = memberRoles.findByMemberIdAndRoleAndProjectIdIsNull(targetId, MemberRole.ROLE_ADMIN)
        if (admin) {
            if (existing == null) memberRoles.save(MemberRole(memberId = targetId, role = MemberRole.ROLE_ADMIN))
        } else if (existing != null) {
            if (adminGuard.countWorkspaceAdmins() == 1) throw ConflictException("最后 admin 不可收")
            memberRoles.deleteByMemberIdAndRoleAndProjectIdIsNull(targetId, MemberRole.ROLE_ADMIN)
        }
        return member.toResponse()
    }

    /**
     * V6:设 GitHub 用户名(把 PR 作者/分支创建者映射回成员);空串 = 清除。
     * workspace 内唯一(DB 部分唯一索引兜底,应用层先行校验给 409)。
     */
    @Transactional
    fun setGithubUsername(
        memberId: UUID,
        githubUsername: String?,
    ): MemberResponse {
        val member = requireMember(memberId)
        val cleaned = githubUsername?.trim()?.ifEmpty { null }
        if (cleaned != null) {
            if (!Regex("^[A-Za-z0-9-]{1,39}$").matches(cleaned)) {
                throw BadRequestException("GitHub 用户名格式非法:$cleaned")
            }
            members
                .findByWorkspaceObjectIdAndGithubUsername(member.workspaceObjectId, cleaned)
                ?.let { if (it.objectId != member.objectId) throw ConflictException("GitHub 用户名已被占用:$cleaned") }
        }
        val saved = members.save(member.copy(githubUsername = cleaned, updatedAt = Instant.now()))
        return saved.toResponse()
    }

    /** V11-S1:记录最后访问的项目 key(看板页 mount 时调用);空串 = 清除。 */
    @Transactional
    fun setLastProjectKey(
        memberId: UUID,
        key: String?,
    ): MemberResponse {
        val member = members.findByObjectId(memberId) ?: throw NotFoundException("成员不存在")
        val cleaned = key?.trim()?.ifEmpty { null }
        val saved = members.save(member.copy(lastProjectKey = cleaned, updatedAt = Instant.now()))
        return saved.toResponse()
    }

    private fun Member.toResponse() = MemberResponse(
        objectId,
        displayName,
        username,
        virtual = passwordHash == null,
        githubUsername = githubUsername,
        color = color,
        lastProjectKey = lastProjectKey,
        status = status.name.lowercase(),
    )
}
