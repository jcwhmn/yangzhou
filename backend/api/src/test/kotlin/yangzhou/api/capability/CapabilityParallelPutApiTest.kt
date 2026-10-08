package yangzhou.api.capability

import yangzhou.api.AbstractApiTest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertTrue

/** YPJ-10:并行能力 PUT → recomputeWorkspace 按 id 序更新 project 行,不再 40P01 死锁 → 500。 */
class CapabilityParallelPutApiTest : AbstractApiTest() {

    @Test
    fun `并行能力 PUT——workspace 重算按 id 序更新项目——无死锁`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        repeat(4) { createProject(authed, "DP$it") } // 重算集合跨多行:单行集合构不成锁序环
        val attrs = (1..8).map { "技能$it" }
        attrs.forEach { createSkillAttribute(authed, it) }

        val token = login("me", "secret")["token"].asText()
        val client = HttpClient.newHttpClient()
        val pool = Executors.newFixedThreadPool(attrs.size)
        val futures = attrs.map { attr ->
            pool.submit(Callable {
                repeat(6) { round ->
                    val resp = client.send(
                        HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:$port/api/capabilities"))
                            .header("Authorization", "Bearer $token")
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(
                                json.writeValueAsString(mapOf("attribute" to attr, "level" to (round % 4 + 1)))))
                            .build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                    assertTrue(resp.statusCode() == 200, "PUT ${resp.statusCode()}: ${resp.body()}")
                }
            })
        }
        pool.shutdown()
        futures.forEach { it.get() } // 工作线程断言失败在此重抛
    }
}
