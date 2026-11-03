package yangzhou.api.business.itemgroup

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import yangzhou.api.error.ConflictException
import yangzhou.persistence.GroupStatus
import yangzhou.persistence.ItemGroupType
import yangzhou.persistence.StatusType
import yangzhou.persistence.repository.GroupStatusRepository
import yangzhou.persistence.repository.ItemGroupTypeRepository

/**
 * GroupWorkflow(spec 0097 S2):ItemGroupType 即 workflow 宿主(类型 1→1),group_status 行即其内容。
 * 出边内嵌 group_status.transitions JSON(依附 status,不独立建表);组状态变更 = 动作白名单触发。
 */
@Service
class GroupWorkflowService(
    private val types: ItemGroupTypeRepository,
    private val groupStatuses: GroupStatusRepository,
    private val mapper: ObjectMapper,
) {

    data class GroupStatusDto(
        val name: String,
        val statusType: StatusType,
        val transitions: List<GroupTransitionDto>,
    )

    data class GroupTransitionDto(val function: String, val target: String)

    private data class Edge(val function: String, val target: String)

    // ---------- 种子模板(createType / ProjectService.create 复用) ----------

    private fun template(typeKey: String): List<Triple<String, StatusType, List<Edge>>> = when (typeKey) {
        "sprint" -> listOf(
            Triple("planned", StatusType.START, listOf(Edge("start", "active"))),
            Triple("active", StatusType.ACTIVE, listOf(Edge("complete", "completed"), Edge("backward", "planned"))),
            Triple("completed", StatusType.FINAL, emptyList()), // 终态无出边 = 护栏
        )
        "release" -> listOf(
            Triple("planned", StatusType.START, listOf(Edge("complete", "released"))),
            Triple("released", StatusType.FINAL, emptyList()),
        )
        else -> // 自定义默认三态循环 switch(每态单出边无歧义;循环可达任意态,与 0056「可任意推进」语义等效)
            listOf(
                Triple("planned", StatusType.START, listOf(Edge("switch", "active"))),
                Triple("active", StatusType.ACTIVE, listOf(Edge("switch", "completed"))),
                Triple("completed", StatusType.FINAL, listOf(Edge("switch", "planned"))),
            )
    }

    /** 类型落库后种默认状态机(幂等:先清后种)。 */
    @Transactional
    fun seedFor(type: ItemGroupType) {
        groupStatuses.deleteByGroupTypeId(type.id!!)
        template(type.typeKey).forEachIndexed { pos, (name, statusType, edges) ->
            groupStatuses.save(
                GroupStatus(
                    groupTypeId = type.id!!,
                    name = name,
                    statusType = statusType,
                    transitions = mapper.writeValueAsString(edges.map { mapOf("function" to it.function, "target" to it.target) }),
                    position = pos,
                ),
            )
        }
    }

    // ---------- 读取 ----------

    fun workflow(type: ItemGroupType): List<GroupStatusDto> = groupStatuses.findByGroupTypeIdOrderByPosition(type.id!!).map { it.toDto() }

    /** 组初始状态 = 该类型 workflow 的 START 态名(如 sprint=planned)。 */
    fun startStatusName(type: ItemGroupType): String = groupStatuses.findByGroupTypeIdOrderByPosition(type.id!!).firstOrNull { it.statusType == StatusType.START }?.name
        ?: error("类型 ${type.typeKey} 缺 START 状态")

    /** FINAL 态名集(如 sprint={completed}, release={released})——membership「历史不可变」判定用。 */
    fun finalStatusNames(type: ItemGroupType): Set<String> = groupStatuses.findByGroupTypeIdOrderByPosition(type.id!!).filter { it.statusType == StatusType.FINAL }.map { it.name }.toSet()

    fun statusNames(type: ItemGroupType): Set<String> = groupStatuses.findByGroupTypeIdOrderByPosition(type.id!!).map { it.name }.toSet()

    // ---------- 动作(白名单) ----------

    /** 当前状态出边含此 function → 返回目标状态名;否则 409「当前状态无此动作」。 */
    @Transactional
    fun applyAction(type: ItemGroupType, currentStatus: String, function: String): String {
        val current =
            groupStatuses.findByGroupTypeIdAndName(type.id!!, currentStatus)
                ?: error("组状态不在类型 workflow 内:$currentStatus")
        val edge = parseTransitions(current).firstOrNull { it.function == function }
            ?: throw ConflictException("当前状态无此动作:$function")
        if (edge.target !in statusNames(type)) error("出边目标不在类型 workflow 内:${edge.target}")
        return edge.target
    }

    private fun parseTransitions(status: GroupStatus): List<Edge> = try {
        mapper.readTree(status.transitions).map { node ->
            Edge(node["function"]?.asString() ?: "switch", node["target"]?.asString() ?: "")
        }.filter { it.target.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }

    private fun GroupStatus.toDto() = GroupStatusDto(name, statusType, parseTransitions(this).map { GroupTransitionDto(it.function, it.target) })
}
