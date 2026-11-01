package yangzhou.api.business.member

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.business.workspace.WorkspaceService
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Member
import yangzhou.persistence.repository.CapabilityRepository
import yangzhou.persistence.repository.CommentRepository
import yangzhou.persistence.repository.ItemActivityRepository
import yangzhou.persistence.repository.ItemStatusHistoryRepository
import yangzhou.persistence.repository.MemberRepository
import yangzhou.persistence.repository.NotificationRepository
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
) {
    data class MemberResponse(
        val memberId: UUID,
        val displayName: String,
        val username: String?,
        val virtual: Boolean,
        val githubUsername: String? = null,
        val color: String? = null,
        val lastProjectKey: String? = null,
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

    /** 建虚拟成员:纯数据人,无凭据无登录;能力自评走 /api/members/{id}/capabilities。 */
    @Transactional
    fun createVirtual(displayName: String): MemberResponse {
        val workspaceObjectId = workspaceService.required().objectId!!
        members
            .findByWorkspaceObjectId(workspaceObjectId)
            .firstOrNull { it.displayName == displayName }
            ?.let { throw ConflictException("成员已存在:$displayName") }
        val saved =
            members.save(
                Member(
                    workspaceObjectId = workspaceObjectId,
                    username = null,
                    passwordHash = null,
                    displayName = displayName,
                    color = nextColor(),
                ),
            )
        return saved.toResponse()
    }

    /** 删除成员:仅虚拟成员可删(我自己不可删);能力随删,team_member 由 FK 级联。V10-Q9:有非终态 assignee item → 409。 */
    @Transactional
    fun delete(memberId: UUID) {
        val member = members.findByObjectId(memberId) ?: throw NotFoundException("成员不存在")
        if (member.passwordHash != null) throw ConflictException("登录账号不可删除")
        val activeItems =
            items
                .findByAssigneeObjectIdAndDeletedAtIsNull(member.objectId)
                .filter { item -> statuses.findByObjectId(item.statusObjectId)?.isFinal != true }
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
    )
}
