package yangzhou.api.business.itemgroup

import yangzhou.persistence.GroupStatusHistory
import java.time.Instant
import java.util.UUID

/**
 * YPJ-269(spec 0174):ItemGroup 生命周期时刻派生——公式与 item 侧 LifecycleTimes(YPJ-132/0150)逐字同构:
 * startedAt=首次迁出 START;completedAt=最近一次迁入 FINAL(reopen 后未再完成则保留上次完成时刻)。
 * 组侧独立实现而非泛化 item 签名(spec 0174 否决记录:动 item 4 个调用点零行为收益);
 * 两侧公式漂移时以 LifecycleTimes 注释为准双向锚定。
 */
object GroupLifecycleTimes {
    fun startedAt(
        histories: List<GroupStatusHistory>,
        startIds: Set<UUID>,
    ): Instant? = histories.firstOrNull { it.toGroupStatusObjectId !in startIds }?.createdAt

    fun completedAt(
        histories: List<GroupStatusHistory>,
        finalIds: Set<UUID>,
    ): Instant? = histories.lastOrNull { it.toGroupStatusObjectId in finalIds }?.createdAt
}
