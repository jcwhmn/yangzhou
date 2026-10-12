package yangzhou.api.feasibility

import yangzhou.api.AbstractApiTest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * YPJ-26:并发 requirements/capabilities PUT 负载下偶发 500——复现锤。
 *
 * 4 线程成员能力 PUT(recomputeWorkspace 全量重算)× 4 线程 item 需求 PUT
 * (recomputeProjectSignal 项目重算)混合 12 轮,断言全 2xx。
 * 失败消息带 status + body,按异常类名区分 40P01 死锁 / 连接池超时 / 其他。
 */
class ConcurrentPutHammerApiTest : AbstractApiTest() {

    @Test
    fun `并发混合 PUT——workspace 重算 × 项目重算——全 2xx`() {
        val authed = bootstrapAndAuth()
        repeat(5) { createProject(authed, "HM$it") }
        val attrs = (1..6).map { "锤技$it" }
        attrs.forEach { createSkillAttribute(authed, it) }
        val itemIds = mutableListOf<String>()
        repeat(5) { p ->
            repeat(2) { k ->
                val item = createItem(
                    authed, "HM$p", "锤$p-$k",
                    listOf(mapOf("attribute" to attrs[k % attrs.size], "minLevel" to 2)),
                )
                itemIds.add(item["itemId"].asText())
            }
        }
        val members = json.readTree(
            authed.get().uri("/api/members")
                .exchange().expectBody(String::class.java).returnResult().responseBody,
        )
        assertTrue(members.isArray && members.size() > 0, "无成员可打:/api/members 响应=$members")
        val memberId = members[0]["memberId"].asText()

        val token = login("me", "secret")["token"].asText()
        val client = HttpClient.newHttpClient()
        val pool = Executors.newFixedThreadPool(8)
        val futures = mutableListOf<java.util.concurrent.Future<*>>()

        // 4 线程:成员能力 PUT → recomputeWorkspace(全部 project 行,item 全量重写)
        repeat(4) { t ->
            futures += pool.submit(Callable {
                val attr = attrs[t % attrs.size]
                repeat(ROUNDS) { round ->
                    val resp = client.send(
                        HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:$port/api/members/$memberId/capabilities"))
                            .header("Authorization", "Bearer $token")
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(
                                json.writeValueAsString(mapOf("attribute" to attr, "level" to (round % 4 + 1)))))
                            .build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                    assertTrue(resp.statusCode() == 200, "caps[$t] r$round -> ${resp.statusCode()}: ${resp.body()}")
                }
            })
        }

        // 4 线程:item 需求整体替换 PUT → recomputeProjectSignal(该项目 item 行 + project 行)
        repeat(4) { t ->
            futures += pool.submit(Callable {
                val itemId = itemIds[t % itemIds.size]
                repeat(ROUNDS) { round ->
                    val resp = client.send(
                        HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:$port/api/items/$itemId/requirements"))
                            .header("Authorization", "Bearer $token")
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                                mapOf("requirements" to listOf(
                                    mapOf("attribute" to attrs[(round + t) % attrs.size], "minLevel" to (round % 3 + 1)),
                                )))))
                            .build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                    assertTrue(resp.statusCode() == 200, "reqs[$t] r$round -> ${resp.statusCode()}: ${resp.body()}")
                }
            })
        }

        pool.shutdown()
        futures.forEach { it.get() } // 工作线程断言失败在此重抛
    }

    private companion object {
        const val ROUNDS = 12
    }
}
