package yangzhou.api.item

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class ItemsApiTest : AbstractApiTest() {

    @Test
    fun `编号项目内递增,带 key 前缀`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        createSkillAttribute(authed, "React")

        val first = createItem(authed, "CHE", "auth baseline", listOf(mapOf("attribute" to "React", "minLevel" to 2)))
        val second = createItem(authed, "CHE", "websocket draw")

        assertEquals("CHE-1", first["number"].asText())
        assertEquals("CHE-2", second["number"].asText())
        assertEquals("To Do", first["status"].asText())
        assertEquals("React", first["requirements"][0]["attribute"].asText())
        assertEquals(2, first["requirements"][0]["minLevel"].asInt())
    }

    @Test
    fun `并发创建编号不重不漏`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")

        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val numbers = java.util.Collections.synchronizedList(mutableListOf<Int>())

        repeat(threads) {
            pool.submit {
                ready.countDown()
                go.await()
                val item = createItem(authed, "CHE", "并发 $it")
                numbers += item["number"].asText().removePrefix("CHE-").toInt()
            }
        }
        ready.await(); go.countDown(); pool.shutdown(); assertTrue(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS))

        assertEquals((1..threads).toList(), numbers.sorted())
    }

    @Test
    fun `把祖先挂到后代下被拒绝(防环)`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        val parent = createItem(authed, "CHE", "父")
        val child = createItem(authed, "CHE", "子")

        // 正常:child.parent = parent
        authed.patch().uri("/api/items/${child["itemId"].asText()}")
            .body(mapOf("parentItemId" to parent["itemId"].asText()))
            .exchange().expectStatus().isOk()

        // 非法:parent.parent = child → 环
        authed.patch().uri("/api/items/${parent["itemId"].asText()}")
            .body(mapOf("parentItemId" to child["itemId"].asText()))
            .exchange().expectStatus().isEqualTo(409)
    }

    @Test
    fun `状态自由迁移(V1 无约束)`() {
        val authed = bootstrapAndAuth()
        val project = createProject(authed, "CHE")
        val item = createItem(authed, "CHE", "x")
        val done = project["statuses"].first { it["name"].asText() == "Done" }["statusId"].asText()

        authed.patch().uri("/api/items/${item["itemId"].asText()}")
            .body(mapOf("statusItemId" to done))
            .exchange().expectStatus().isOk()
            .expectBody(String::class.java).returnResult().responseBody!!
            .let { assertEquals("Done", json.readTree(it)["status"].asText()) }
    }

    @Test
    fun `需求整表替换——增删改一次落库`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        createSkillAttribute(authed, "Java")
        createSkillAttribute(authed, "架构")
        val item = createItem(authed, "CHE", "x", listOf(mapOf("attribute" to "Java", "minLevel" to 3)))

        // 替换:Java 改门槛 + 新增架构 presence + 移除(整表替换语义)
        val updated = json.readTree(
            authed.put().uri("/api/items/${item["itemId"].asText()}/requirements")
                .body(mapOf("requirements" to listOf(
                    mapOf("attribute" to "Java", "minLevel" to 4),
                    mapOf("attribute" to "架构", "minLevel" to null),
                )))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        val reqs = updated["requirements"].map { it["attribute"].asText() to if (it["minLevel"].isNull) null else it["minLevel"].asInt() }
        assertEquals(listOf("Java" to 4, "架构" to null), reqs)

        // 再替换为空 = 清空
        authed.put().uri("/api/items/${item["itemId"].asText()}/requirements")
            .body(mapOf("requirements" to emptyList<String>()))
            .exchange().expectStatus().isOk()
        val cleared = json.readTree(
            authed.get().uri("/api/items/${item["itemId"].asText()}").exchange()
                .expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(0, cleared["requirements"].size())
    }

    @Test
    fun `未知属性的需求 400`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        authed.post().uri("/api/projects/CHE/items")
            .body(mapOf("title" to "x", "requirements" to listOf(mapOf("attribute" to "幽灵", "minLevel" to 1))))
            .exchange().expectStatus().isBadRequest()
    }
}
