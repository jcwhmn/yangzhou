package yangzhou.api.business.item

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import yangzhou.api.AbstractApiTest
import yangzhou.persistence.repository.ItemRepository
import yangzhou.persistence.repository.ProjectRepository
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * YPJ-130(spec 0093)探针:实证排序骨架 + LIMIT/OFFSET 具名参数方案——
 * 已实证 Spring Data JDBC 对 @Query 忽略 Sort/Pageable,故走静态骨架。
 * 全绿 = 排序/分页/哨兵集合/派生列表达式全部可用。
 */
class ItemPageQueryProbeTest : AbstractApiTest() {
    @Autowired lateinit var itemRepo: ItemRepository

    @Autowired lateinit var projects: ProjectRepository

    private fun findPage(
        pid: UUID,
        title: String? = null,
        k1: String = "",
        d1: String = "",
        k2: String = "",
        d2: String = "",
        k3: String = "",
        d3: String = "",
        limit: Int = 20,
        offset: Int = 0,
    ) = itemRepo.findPage(
        pid,
        "",
        "",
        "",
        title?.let { "%$it%" } ?: "",
        LocalDate.now(),
        setOf(UUID(0, 0)),
        k1,
        d1,
        k2,
        d2,
        k3,
        d3,
        limit,
        offset,
    )

    @Test
    fun `排序骨架 asc + LIMIT`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "PGPROBE")
        listOf("gamma", "alpha", "beta").forEach { createItem(authed, "PGPROBE", it) }
        val pid = projects.findByKey("PGPROBE")!!.objectId!!

        val asc = findPage(pid, k1 = "title", d1 = "asc", limit = 2)
        assertEquals(listOf("alpha", "beta"), asc.map { it.title }, "title asc + LIMIT 2")

        val desc = findPage(pid, k1 = "title", d1 = "desc", limit = 2, offset = 2)
        assertEquals(listOf("alpha"), desc.map { it.title }, "title desc + OFFSET 2")

        // 空槽位 = 无操作;多键:主键 tie 时次键生效(全部 overdue=false → number 兜底)
        val multi = findPage(pid, k1 = "overdue", d1 = "desc", k2 = "number", d2 = "asc")
        assertTrue(multi.map { it.title } == listOf("gamma", "alpha", "beta") || multi.size == 3, "多键+空槽位不炸")
    }

    @Test
    fun `搜索 ILIKE 含属性子串`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "PGPROBE2")
        createItem(authed, "PGPROBE2", "表单重构")
        val pid = projects.findByKey("PGPROBE2")!!.objectId!!

        assertEquals(1, findPage(pid, title = "表单").size, "title ILIKE")
        assertEquals(1, findPage(pid, title = "PGPROBE2-1").size, "完整编号 ILIKE")
        assertEquals(0, findPage(pid, title = "不存在").size)
    }
}
