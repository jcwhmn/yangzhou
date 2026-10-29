package yangzhou.persistence.repository

import org.springframework.jdbc.core.JdbcOperations
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * 原子取号:行锁串行化 per-project 编号(spec:DB 层防并发重号)。
 * ponytail: 全项目一个计数器行,吞吐瓶颈不成立(单人 V1);真并发大时可改 per-project sequence。
 */
@Repository
class ItemNumberRepository(private val jdbc: JdbcOperations) {

    fun nextNumber(projectObjectId: UUID): Int =
        jdbc.queryForObject(
            "update project set last_item_number = last_item_number + 1, updated_at = now() where object_id = ? returning last_item_number",
            Int::class.java,
            projectObjectId,
        ) ?: error("project not found: $projectObjectId")
}
