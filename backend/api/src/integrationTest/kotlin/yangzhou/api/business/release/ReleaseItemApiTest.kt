package yangzhou.api.business.release

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.test.web.servlet.client.RestTestClient
import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * YPJ-200(spec 0133,US-R3):item ↔ release 挂接 REST 黑盒。
 * 挂/摘/幂等/不变量(至多一个未终态)/终态冻结/删除守卫。
 */
class ReleaseItemApiTest : AbstractApiTest() {
    private fun put(
        authed: RestTestClient,
        itemId: String,
        body: Map<String, Any?>,
    ): Pair<Int, JsonNode> {
        val r =
            authed
                .put()
                .uri("/api/items/$itemId/release-membership")
                .body(body)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return r.status.value() to json.readTree(r.responseBody!!)
    }

    private fun createRelease(
        authed: RestTestClient,
        key: String,
        name: String,
    ): String {
        val r =
            authed
                .post()
                .uri("/api/projects/$key/releases")
                .body(mapOf("name" to name))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(201, r.status.value(), r.responseBody)
        return json.readTree(r.responseBody!!)["releaseId"].asText()
    }

    private fun createItem(
        authed: RestTestClient,
        key: String,
        title: String,
    ): String {
        val r =
            authed
                .post()
                .uri("/api/projects/$key/items")
                .body(mapOf("title" to title))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(201, r.status.value(), r.responseBody)
        return json.readTree(r.responseBody!!)["itemId"].asText()
    }

    private fun publish(
        authed: RestTestClient,
        releaseId: String,
    ) {
        val r =
            authed
                .patch()
                .uri("/api/projects/RIT/releases/$releaseId")
                .body(mapOf("status" to "released"))
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        assertEquals(200, r.status.value(), r.responseBody)
    }

    private fun deleteRelease(
        authed: RestTestClient,
        releaseId: String,
    ): Int = authed
        .delete()
        .uri("/api/projects/RIT/releases/$releaseId")
        .exchange()
        .returnResult(Void::class.java)
        .status
        .value()

    @Test
    fun `挂摘主链 - 幂等 - 不变量 - 终态冻结 - 删除守卫`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "RIT")
        val a = createRelease(authed, "RIT", "A")
        val b = createRelease(authed, "RIT", "B")
        val i1 = createItem(authed, "RIT", "one")
        val i2 = createItem(authed, "RIT", "two")

        // 挂 i1 → A;幂等
        val (s1, m1) = put(authed, i1, mapOf("releaseId" to a))
        assertEquals(200, s1)
        assertEquals(a, m1["releaseId"].asText())
        assertEquals("A", m1["releaseName"].asText())
        assertEquals(m1, put(authed, i1, mapOf("releaseId" to a)).second)

        // 不变量:i1 已属 A(planned)→ 挂 B 409
        val (s2, c) = put(authed, i1, mapOf("releaseId" to b))
        assertEquals(409, s2)
        assertTrue("A" in c["message"].asText())

        // i2 挂 B 合法(不同 item)
        assertEquals(200, put(authed, i2, mapOf("releaseId" to b)).first)

        // 发布 A;挂到 released 本体 → 409;i1(released A 上)换挂 planned B → 允许(历史多行)
        publish(authed, a)
        assertEquals(409, put(authed, i2, mapOf("releaseId" to a)).first)
        val (s3, m3) = put(authed, i1, mapOf("releaseId" to b))
        assertEquals(200, s3)
        assertEquals(b, m3["releaseId"].asText())

        // 摘 i2(B planned)→ {null,null};再摘幂等
        val (s4, m4) = put(authed, i2, mapOf("releaseId" to null))
        assertEquals(200, s4)
        assertNull(m4["releaseId"].asText().takeIf { it != "null" })
        assertTrue(m4["releaseName"].isNull)

        // 删除守卫:B 仍有成员(i1)→ 409;摘空后 → 204
        assertEquals(409, deleteRelease(authed, b))
        put(authed, i1, mapOf("releaseId" to null))
        assertEquals(204, deleteRelease(authed, b))
    }

    /** YPJ-202(spec 0135):US-R6 留痕。 */
    @Test
    fun `挂接留痕 - 挂捕各行 - 幂等不留痕 - 删后仍可查`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "RIT")
        val a = createRelease(authed, "RIT", "A")
        val i1 = createItem(authed, "RIT", "one")

        fun historyCount(): Int = authed
            .get()
            .uri("/api/projects/RIT/releases/$a/history")
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .let { json.readTree(it).size() }

        fun latest(): JsonNode = authed
            .get()
            .uri("/api/projects/RIT/releases/$a/history")
            .exchange()
            .expectBody(String::class.java)
            .returnResult()
            .responseBody!!
            .let { json.readTree(it)[0] }

        // 初始无留痕
        assertEquals(0, historyCount())

        // 挂 → attached 1 行,含 item 名/actor 名
        put(authed, i1, mapOf("releaseId" to a))
        assertEquals(1, historyCount())
        var row = latest()
        assertEquals("attached", row["action"].asText())
        assertEquals("one", row["itemName"].asText())
        assertTrue(!row["actorName"].isNull)

        // 幂等重挂 → 不留痕
        put(authed, i1, mapOf("releaseId" to a))
        assertEquals(1, historyCount())

        // 摘 → detached;倒序(detached 在前)
        put(authed, i1, mapOf("releaseId" to null))
        assertEquals(2, historyCount())
        row = latest()
        assertEquals("detached", row["action"].asText())

        // 无挂接再摘(幂等)→ 不留痕
        put(authed, i1, mapOf("releaseId" to null))
        assertEquals(2, historyCount())

        // 删 release(已摘空)→ 留痕仍在(永不删)
        assertEquals(204, deleteRelease(authed, a))
        assertEquals(2, historyCount())
    }
}
