package yangzhou.api.business.itemgroup

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.persistence.GroupStatus
import yangzhou.persistence.ItemGroupType
import yangzhou.persistence.StatusType
import yangzhou.persistence.repository.GroupStatusRepository
import yangzhou.persistence.repository.ItemGroupTypeRepository

/** spec 0097 §3.6(YPJ-141):组动作函数注册表——function 名 → 意图校验(source/target statusType)。
 *  已知函数违反意图 → 409;未注册函数不拦(数据驱动:出边即真相,预留给自定义/未来 workflow)。 */
enum class GroupActionFn(
    val sourceType: StatusType? = null,
    val targetType: StatusType? = null,
) {
    START(targetType = StatusType.ACTIVE),
    COMPLETE(targetType = StatusType.FINAL),
    BACKWARD,
    SWITCH,
    FORWARD,
    REOPEN(sourceType = StatusType.FINAL, targetType = StatusType.ACTIVE),
    CANCEL(targetType = StatusType.FINAL),
    ;

    companion object {
        private val byName = entries.associateBy { it.name.lowercase() }
        fun of(function: String): GroupActionFn? = byName[function]
    }
}

/**
 * GroupWorkflow(spec 0097 S2):ItemGroupType 即 workflow 宿主(类型 1→1),group_status 行即其内容。
 * 出边内嵌 group_status.transitions JSON(依附 status,不独立建表);组状态变更 = 动作端点唯一路径触发(函数语义见 GroupActionFn)。
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

    data class GroupTransitionDto(
        val function: String,
        val target: String,
        /** spec 0115:显示覆写,缺省回退前端全局映射(缺省 null,GET 契约兼容)。 */
        val label: String? = null,
        val icon: String? = null,
    )

    private data class Edge(
        val function: String,
        val target: String,
        val label: String? = null,
        val icon: String? = null,
    )

    /** spec 0115:PUT workflow 请求形状——顶层按状态数组全量替换(同 0110 风格);状态集/statusType 锁死。 */
    data class StatusTransitionsRequest(
        val name: String,
        val transitions: List<TransitionRequest>,
    )

    data class TransitionRequest(
        val function: String,
        val target: String,
        val label: String? = null,
        val icon: String? = null,
    )

    // ---------- 种子模板(createType / ProjectService.create 复用) ----------

    private fun template(type: ItemGroupType): List<Triple<String, StatusType, List<Edge>>> = when {
        type.timed -> listOf( // 时间盒模板:timed 类型继承 sprint 时间盒语义(spec 0100 切片B,不再挂 typeKey)
            Triple("planned", StatusType.START, listOf(Edge("start", "active"))),
            Triple("active", StatusType.ACTIVE, listOf(Edge("complete", "completed"), Edge("backward", "planned"))),
            Triple(
                "completed",
                StatusType.FINAL,
                listOf(Edge("reopen", "active")),
            ), // 0108:重开=撤销内部收尾判断(纯状态迁移,成员不动);release 不可重开
        )
        type.typeKey == "release" -> listOf(
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
        template(type).forEachIndexed { pos, (name, statusType, edges) ->
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

    /** spec 0115(YPJ-164):出边全量替换——状态名集合与 statusType 锁死(0097 种子/0111 联动语义锚);
     *  function 限注册表并做意图静态预检(同 applyAction 规则),防配置出「点了必 409」的死边。 */
    @Transactional
    fun updateWorkflow(
        type: ItemGroupType,
        requests: List<StatusTransitionsRequest>,
    ): List<GroupStatusDto> {
        val statuses = groupStatuses.findByGroupTypeIdOrderByPosition(type.id!!)
        val byName = statuses.associateBy { it.name }
        val nameSet = requests.map { it.name }.toSet()
        if (requests.size != nameSet.size || nameSet != byName.keys) {
            throw ConflictException("状态集不可增改:须与现有状态完全一致 $byName.keys")
        }
        requests.forEach { req ->
            val from = byName.getValue(req.name)
            val functions = mutableSetOf<String>()
            req.transitions.forEach { e ->
                val action =
                    GroupActionFn.of(e.function.trim())
                        ?: throw ConflictException("未注册动作函数:$e.function")
                if (!functions.add(action.name)) throw ConflictException("同一状态出边 function 重复:$e.function")
                val target =
                    byName[e.target]
                        ?: throw ConflictException("出边目标不在类型 workflow 内:$e.target")
                if (action.sourceType != null && from.statusType != action.sourceType) {
                    throw ConflictException("动作 $e.function 仅允许从 $action.sourceType 态触发")
                }
                if (action.targetType != null && target.statusType != action.targetType) {
                    throw ConflictException("动作 $e.function 的出边目标必须是 $action.targetType 态")
                }
                if ((e.label?.trim()?.length ?: 0) > 20) throw BadRequestException("label 超长(≤20)")
                if ((e.icon?.trim()?.length ?: 0) > 4) throw BadRequestException("icon 超长(≤4)")
            }
        }
        statuses.forEach { st ->
            val edges =
                requests
                    .first { it.name == st.name }
                    .transitions
                    .map { e ->
                        buildMap<String, String> {
                            put("function", e.function.trim())
                            put("target", e.target)
                            e.label?.trim()?.takeIf { it.isNotEmpty() }?.let { put("label", it) }
                            e.icon?.trim()?.takeIf { it.isNotEmpty() }?.let { put("icon", it) }
                        }
                    }
            groupStatuses.save(st.copy(transitions = mapper.writeValueAsString(edges)))
        }
        return workflow(type)
    }

    /** 组初始状态 = 该类型 workflow 的 START 态名(如 sprint=planned)。 */
    fun startStatusName(type: ItemGroupType): String = groupStatuses.findByGroupTypeIdOrderByPosition(type.id!!).firstOrNull { it.statusType == StatusType.START }?.name
        ?: error("类型 ${type.typeKey} 缺 START 状态")

    /** FINAL 态名集(如 sprint={completed}, release={released})——membership「历史不可变」判定用。 */
    fun finalStatusNames(type: ItemGroupType): Set<String> = groupStatuses.findByGroupTypeIdOrderByPosition(type.id!!).filter { it.statusType == StatusType.FINAL }.map { it.name }.toSet()

    /** spec 0111:状态名 → statusType(不在类型 workflow 内 → null,联动推导用)。 */
    fun statusType(
        type: ItemGroupType,
        statusName: String,
    ): StatusType? = groupStatuses.findByGroupTypeIdAndName(type.id!!, statusName)?.statusType

    /** spec 0111:类型 workflow 中首个指定 statusType 的状态名(缺 → null)。 */
    fun statusNameByType(
        type: ItemGroupType,
        wanted: StatusType,
    ): String? = groupStatuses.findByGroupTypeIdOrderByPosition(type.id!!).firstOrNull { it.statusType == wanted }?.name

    // ---------- 动作(白名单) ----------

    /** 当前状态出边含此 function → 意图校验(§3.6 注册表)后返回目标状态名;无匹配出边 → 409。 */
    @Transactional
    fun applyAction(type: ItemGroupType, currentStatus: String, function: String): String {
        val current =
            groupStatuses.findByGroupTypeIdAndName(type.id!!, currentStatus)
                ?: error("组状态不在类型 workflow 内:$currentStatus")
        val edge = parseTransitions(current).firstOrNull { it.function == function }
            ?: throw ConflictException("当前状态无此动作:$function")
        val target = groupStatuses.findByGroupTypeIdAndName(type.id!!, edge.target)
            ?: error("出边目标不在类型 workflow 内:${edge.target}")
        GroupActionFn.of(function)?.let { action ->
            if (action.sourceType != null && current.statusType != action.sourceType) {
                throw ConflictException("动作 $function 仅允许从 ${action.sourceType} 态触发")
            }
            if (action.targetType != null && target.statusType != action.targetType) {
                throw ConflictException("动作 $function 的出边目标必须是 ${action.targetType} 态")
            }
        }
        return target.name
    }

    private fun parseTransitions(status: GroupStatus): List<Edge> = try {
        mapper.readTree(status.transitions).map { node ->
            Edge(
                function = node["function"]?.asString() ?: "switch",
                target = node["target"]?.asString() ?: "",
                label = node["label"]?.takeIf { !it.isNull }?.asString(),
                icon = node["icon"]?.takeIf { !it.isNull }?.asString(),
            )
        }.filter { it.target.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }

    private fun GroupStatus.toDto() = GroupStatusDto(
        name,
        statusType,
        parseTransitions(this).map { GroupTransitionDto(it.function, it.target, it.label, it.icon) },
    )
}
