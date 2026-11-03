package yangzhou.api.business.workflow

import jakarta.validation.constraints.NotBlank
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException
import yangzhou.api.guard.LeaderGuard
import yangzhou.persistence.Status
import yangzhou.persistence.StatusType
import yangzhou.persistence.repository.ProjectRepository
import yangzhou.persistence.repository.StatusRepository
import java.util.UUID

/**
 * Workflow = Project 上的数据(DOMAIN.md):{Status 集合 + 内嵌出边}。
 * 出边内嵌 status.transitions JSON(spec 0097 S1,依附 status 不独立建表):
 * 某状态出边空数组 = 自由迁移(全放行,领域规则 3);非空 = 仅列出的 from→to 合法。
 */

@Service
class WorkflowService(
    private val leaderGuard: LeaderGuard,
    private val projects: ProjectRepository,
    private val statuses: StatusRepository,
    private val mapper: ObjectMapper,
) {

    data class StatusDto(
        val statusId: UUID,
        val name: String,
        val icon: String?,
        val statusType: StatusType,
        val transitions: List<TransitionDto>,
        val position: Int,
    )

    data class TransitionDto(val from: String, val to: String, val function: String = "switch")

    companion object {
        /** 预定义动作函数集(自定义动作实现 defer;displayName/icon 前端按 function 渲染,文案外置)。 */
        val FUNCTIONS = setOf("start", "complete", "switch", "forward", "backward", "cancel")
    }

    // ---------- Status ----------

    @Transactional
    fun createStatus(projectKey: String, name: String, icon: String?, statusType: StatusType, position: Int?): StatusDto {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        leaderGuard.requireLeader(project.objectId!!)
        val projectObjectId = project.objectId!!
        val existing = statuses.findByProjectObjectIdOrderByPosition(projectObjectId)
        if (existing.any { it.name == name }) throw ConflictException("状态名已存在:$name")
        val saved = statuses.save(
            Status(
                projectObjectId = projectObjectId,
                name = name,
                icon = icon,
                statusType = statusType,
                position = position ?: ((existing.maxOfOrNull { it.position } ?: -1) + 1),
            ),
        )
        return saved.toDto()
    }

    @Transactional
    fun updateStatus(statusId: UUID, name: String?, icon: String?, statusType: StatusType?, position: Int?): StatusDto {
        val status = statuses.findByObjectId(statusId) ?: throw NotFoundException("状态不存在")
        leaderGuard.requireLeader(status.projectObjectId)
        try {
            val updated = statuses.save(
                status.copy(
                    name = name ?: status.name,
                    icon = icon ?: status.icon,
                    statusType = statusType ?: status.statusType,
                    position = position ?: status.position,
                ),
            )
            assertHasStartAndFinal(updated.projectObjectId)
            return updated.toDto()
        } catch (_: DataIntegrityViolationException) {
            throw ConflictException("状态名已存在:$name")
        }
    }

    @Transactional
    fun deleteStatus(statusId: UUID) {
        val status = statuses.findByObjectId(statusId) ?: throw NotFoundException("状态不存在")
        leaderGuard.requireLeader(status.projectObjectId)
        try {
            statuses.delete(status) // 内嵌出边随行删除;他状态指向本状态的出边由调用方整表替换维护
            assertHasStartAndFinal(status.projectObjectId)
        } catch (_: DataIntegrityViolationException) {
            throw ConflictException("状态仍被 item 引用,先把它们迁到其他状态")
        }
    }

    /** workflow 至少各保留一个 START 与 FINAL(spec 0097 需求 1);删/改后校验,违反即回滚。 */
    private fun assertHasStartAndFinal(projectObjectId: UUID) {
        val remaining = statuses.findByProjectObjectIdOrderByPosition(projectObjectId)
        if (remaining.none { it.statusType == StatusType.START } || remaining.none { it.statusType == StatusType.FINAL }) {
            throw ConflictException("工作流至少需要一个起点状态和一个终态状态")
        }
    }

    // ---------- Transitions(整表替换;存储内嵌于各 status.transitions JSON) ----------

    fun listTransitions(projectKey: String): List<TransitionDto> {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        val projectObjectId = project.objectId!!
        return statuses.findByProjectObjectIdOrderByPosition(projectObjectId).flatMap { status ->
            parseTransitions(status).map { TransitionDto(status.name, it.second, it.first) }
        }
    }

    @Transactional
    fun replaceTransitions(projectKey: String, list: List<TransitionDto>): List<TransitionDto> {
        val project = projects.findByKey(projectKey) ?: throw NotFoundException("项目不存在:$projectKey")
        leaderGuard.requireLeader(project.objectId!!)
        val projectObjectId = project.objectId!!
        val byName = statuses.findByProjectObjectIdOrderByPosition(projectObjectId).associateBy { it.name }
        list.forEach { t ->
            if (t.from !in byName || t.to !in byName) {
                throw BadRequestException("迁移引用了不存在的状态:${t.from}→${t.to}")
            }
            if (t.function !in FUNCTIONS) {
                throw BadRequestException("未知动作函数:${t.function}")
            }
        }
        // 整表替换:每状态出边 = 以它为 from 的项(按出现序);没有出边的状态清空(全放行)
        byName.values.forEach { status ->
            val edges = list.filter { it.from == status.name }.map { it.function to it.to }
            statuses.save(status.copy(transitions = mapper.writeValueAsString(edges.map { (fn, to) -> mapOf("function" to fn, "target" to to) })))
        }
        return listTransitions(projectKey)
    }

    /** Item 状态迁移校验:from 状态出边空 = 全放行;否则 from→to 必须在出边内(from==to 恒放行)。 */
    fun assertTransitionAllowed(projectObjectId: UUID, fromStatusObjectId: UUID, toStatusObjectId: UUID) {
        if (fromStatusObjectId == toStatusObjectId) return
        val from = statuses.findByObjectId(fromStatusObjectId) ?: return
        val edges = parseTransitions(from)
        if (edges.isEmpty()) return // 自由迁移(出边空数组,V1 默认语义)
        val to = statuses.findByObjectId(toStatusObjectId)
        if (to == null || edges.none { it.second == to.name }) {
            throw ConflictException("工作流不允许此状态迁移")
        }
    }

    /** status.transitions JSON → (function, target 名) 对;坏 JSON/空数组返回空(全放行)。 */
    private fun parseTransitions(status: Status): List<Pair<String, String>> = try {
        mapper.readTree(status.transitions).map { node ->
            val obj = node
            (obj["function"]?.asString() ?: "switch") to (obj["target"]?.asString() ?: "")
        }.filter { it.second.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }

    private fun Status.toDto() = StatusDto(
        objectId,
        name,
        icon,
        statusType,
        parseTransitions(this).map { (fn, to) -> TransitionDto(name, to, fn) },
        position,
    )
}

data class CreateStatusRequest(
    @field:NotBlank val name: String,
    val icon: String? = null,
    val statusType: StatusType = StatusType.ACTIVE,
    val position: Int? = null,
)

data class UpdateStatusRequest(
    val name: String? = null,
    val icon: String? = null,
    val statusType: StatusType? = null,
    val position: Int? = null,
)

data class ReplaceTransitionsRequest(val transitions: List<WorkflowService.TransitionDto> = emptyList())
