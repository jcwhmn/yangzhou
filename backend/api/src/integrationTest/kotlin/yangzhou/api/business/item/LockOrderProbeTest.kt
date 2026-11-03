package yangzhou.api.business.item

import yangzhou.api.AbstractApiTest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * YPJ-128/spec 0096 归因实锤探针(非回归):双 connection 复刻「project→item」与
 * 「item→project」两条真实事务锁序,断言 40P01 死锁必现——证明环真实存在。
 * 修复(统一锁序)不改变本探针行为;它钉住的是"反序必死锁"这一前提。
 */
class LockOrderProbeTest : AbstractApiTest() {
    @Test
    fun `反序锁序在 SQL 层必现 40P01`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "LP")
        val itemId = createItem(authed, "LP", "probe")["itemId"].asText()
        val pid = jdbc.queryForObject("select object_id::text from project where key = 'LP'", String::class.java)

        val a = jdbc.dataSource!!.connection
        val b = jdbc.dataSource!!.connection
        a.autoCommit = false
        b.autoCommit = false
        val errs = mutableListOf<String>()
        try {
            // T1:先锁 project(对应 create 的 nextNumber)
            a.createStatement().use { it.execute("update project set updated_at = now() where object_id = '$pid'") }
            // T2:先锁 item(对应 replaceRequirements/delete/purge 的首写)
            b.createStatement().use { it.execute("update item set title = title where object_id = '$itemId'") }
            // 交叉:T2 等 project,T1 等 item → 环
            val bCross =
                CompletableFuture.runAsync {
                    runCatching {
                        b.createStatement().use { it.execute("update project set updated_at = now() where object_id = '$pid'") }
                    }.onFailure { synchronized(errs) { errs.add(it.message ?: "b-null") } }
                }
            Thread.sleep(300) // 确保 B 已在 project 锁上排队,再让 A 请求 item
            runCatching {
                a.createStatement().use { it.execute("update item set title = title where object_id = '$itemId'") }
            }.onFailure { synchronized(errs) { errs.add(it.message ?: "a-null") } }
            bCross.get(20, TimeUnit.SECONDS)

            assertTrue(errs.isNotEmpty() && errs.any { it.contains("deadlock") }, "期望 40P01 死锁实锤,实得:$errs")
        } finally {
            runCatching { a.rollback() }
            runCatching { b.rollback() }
            runCatching { a.close() }
            runCatching { b.close() }
        }
    }
}
