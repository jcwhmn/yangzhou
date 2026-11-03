package yangzhou.api.business.item

import yangzhou.api.AbstractApiTest
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * YPJ-128/spec 0096 并发回归:同项目内 create(project→items 锁序)与
 * replaceRequirements(items→project 旧反序,现统一)交错锤,断言全 2xx。
 * 若挂,即锁序归因有误——回 LockOrderProbeTest 重查。
 */
class ConcurrentWriteApiTest : AbstractApiTest() {
    @Test
    fun `并发 create 与 requirements 替换——全 2xx`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CW")
        createSkillAttribute(authed, "锤技")
        val itemId = createItem(authed, "CW", "anchor", listOf(mapOf("attribute" to "锤技", "minLevel" to 1)))["itemId"].asText()

        val failures = Collections.synchronizedList(mutableListOf<String>())
        repeat(10) { round ->
            val b =
                Thread {
                    try {
                        val res =
                            authed
                                .put()
                                .uri("/api/items/$itemId/requirements")
                                .body(mapOf("requirements" to emptyList<Map<String, Any?>>()))
                                .exchange()
                                .expectBody(String::class.java)
                                .returnResult()
                        if (res.status.value() >= 500) {
                            failures.add("round$round replace=${res.status.value()} ${res.responseBody?.take(200)}")
                        }
                    } catch (e: Exception) {
                        failures.add("round$round replace-exc=${e.message?.take(200)}")
                    }
                }
            b.start()
            repeat(3) { k -> createItem(authed, "CW", "c$round-$k") }
            b.join()
        }
        assertTrue(failures.isEmpty(), "并发写出现失败(疑似死锁未根治):${failures.take(5)}")
    }
}
