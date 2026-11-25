package yangzhou.api.business.item

import yangzhou.persistence.ItemStatusHistory
import java.time.Instant
import java.util.UUID

/**
 * YPJ-132(spec 0171):Item 生命周期时刻派生——唯一公式出口,与甘特 0150 口径逐字一致
 * (actualStart=首次迁出 START;actualEnd=最近一次迁入 FINAL,YPJ-245 校准)。
 * C 案纯派生:不落列,读侧实时算;调用方批量取 history 后内存分组传入。
 */
object LifecycleTimes {
    fun startedAt(
        histories: List<ItemStatusHistory>,
        startIds: Set<UUID>,
    ): Instant? = histories.firstOrNull { it.toStatusObjectId !in startIds }?.createdAt

    fun completedAt(
        histories: List<ItemStatusHistory>,
        finalIds: Set<UUID>,
    ): Instant? = histories.lastOrNull { it.toStatusObjectId in finalIds }?.createdAt
}
