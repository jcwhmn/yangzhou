package yangzhou.api.github

import yangzhou.api.AbstractApiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JCW-110:V6-S1 配置面黑盒(PAT 不回显 / 仓库挂载 / 事件→状态映射 / github_username)。 */
class GithubConfigApiTest : AbstractApiTest() {

    // ---------- workspace PAT ----------

    @Test
    fun `PAT 配置后只回尾 4 位,可覆盖与清除`() {
        val authed = bootstrapAndAuth()

        val set = json.readTree(
            authed.put().uri("/api/workspace/github-token")
                .body(mapOf("token" to "github_pat_11ABCDEF1234567890"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertTrue(set["configured"].asBoolean())
        assertEquals("…7890", set["tokenHint"].asText())

        // 覆盖
        val reset = json.readTree(
            authed.put().uri("/api/workspace/github-token")
                .body(mapOf("token" to "zzzzzz"))
                .exchange().expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals("…zzzz", reset["tokenHint"].asText())

        authed.delete().uri("/api/workspace/github-token")
            .exchange().expectStatus().isNoContent()
    }

    @Test
    fun `空 token 400,未登录 401`() {
        val authed = bootstrapAndAuth()
        authed.put().uri("/api/workspace/github-token")
            .body(mapOf("token" to "  "))
            .exchange().expectStatus().isBadRequest()

        rest.post().uri("/api/projects/CHE/repos")
            .body(mapOf("repo" to "jcwhmn/yangzhou"))
            .exchange().expectStatus().isUnauthorized()
    }

    // ---------- 项目仓库 ----------

    @Test
    fun `仓库增删查——格式非法 400,重复 409,未知项目或挂载 404`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")

        val added = json.readTree(
            authed.post().uri("/api/projects/CHE/repos")
                .body(mapOf("repo" to "jcwhmn/yangzhou"))
                .exchange().expectStatus().isCreated()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals("jcwhmn/yangzhou", added["repo"].asText())

        // 重复
        authed.post().uri("/api/projects/CHE/repos")
            .body(mapOf("repo" to "jcwhmn/yangzhou"))
            .exchange().expectStatus().isEqualTo(409)

        // 列表
        val list = json.readTree(
            authed.get().uri("/api/projects/CHE/repos").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(1, list.size())

        // 格式非法
        authed.post().uri("/api/projects/CHE/repos")
            .body(mapOf("repo" to "没有斜杠"))
            .exchange().expectStatus().isBadRequest()

        // 删除 + 再删 404
        authed.delete().uri("/api/projects/CHE/repos/${added["repoId"].asText()}")
            .exchange().expectStatus().isNoContent()
        authed.delete().uri("/api/projects/CHE/repos/${added["repoId"].asText()}")
            .exchange().expectStatus().isNotFound()

        // 未知项目
        authed.get().uri("/api/projects/NOPE/repos")
            .exchange().expectStatus().isNotFound()
    }

    // ---------- 事件→状态映射 ----------

    @Test
    fun `四槽默认全空——PUT 回放——跨项目状态与未知事件 400`() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        createProject(authed, "SEC")

        // 默认:四槽全空
        val empty = json.readTree(
            authed.get().uri("/api/projects/CHE/workflow-rules").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(4, empty.size())
        assertTrue(empty.all { it["statusId"].isNull })

        val che = json.readTree(
            authed.get().uri("/api/projects/CHE").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )["statuses"]
        val sec = json.readTree(
            authed.get().uri("/api/projects/SEC").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        )["statuses"]

        // 配两个槽
        val saved = json.readTree(
            authed.put().uri("/api/projects/CHE/workflow-rules")
                .body(
                    mapOf(
                        "rules" to listOf(
                            mapOf("eventType" to "branch_created", "statusId" to che[1]["statusId"].asText()),
                            mapOf("eventType" to "pr_merged", "statusId" to che[2]["statusId"].asText()),
                        ),
                    ),
                )
                .exchange().expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals(4, saved.size())
        assertEquals(che[1]["statusId"].asText(), saved.first { it["eventType"].asText() == "branch_created" }["statusId"].asText())
        assertEquals("Development", saved.first { it["eventType"].asText() == "branch_created" }["statusName"].asText())

        // 跨项目状态 400
        authed.put().uri("/api/projects/CHE/workflow-rules")
            .body(mapOf("rules" to listOf(mapOf("eventType" to "pr_opened", "statusId" to sec[0]["statusId"].asText()))))
            .exchange().expectStatus().isBadRequest()

        // 未知事件 400
        authed.put().uri("/api/projects/CHE/workflow-rules")
            .body(mapOf("rules" to listOf(mapOf("eventType" to "pr_starred", "statusId" to che[0]["statusId"].asText()))))
            .exchange().expectStatus().isBadRequest()

        // 全空 PUT = 清空
        val cleared = json.readTree(
            authed.put().uri("/api/projects/CHE/workflow-rules")
                .body(mapOf("rules" to emptyList<Any>()))
                .exchange().expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertTrue(cleared.all { it["statusId"].isNull })
    }

    // ---------- member github_username ----------

    @Test
    fun `github_username 设置清除——重复 409——非法 400`() {
        val authed = bootstrapAndAuth()
        val me = json.readTree(
            authed.get().uri("/api/members").exchange()
                .expectStatus().isOk().expectBody(String::class.java).returnResult().responseBody!!,
        ).first { !it["virtual"].asBoolean() }
        val meId = me["memberId"].asText()

        val set = json.readTree(
            authed.put().uri("/api/members/$meId/github-username")
                .body(mapOf("githubUsername" to "cwjiang"))
                .exchange().expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertEquals("cwjiang", set["githubUsername"].asText())

        // 自己重复设置自己 = 幂等通过
        authed.put().uri("/api/members/$meId/github-username")
            .body(mapOf("githubUsername" to "cwjiang"))
            .exchange().expectStatus().isOk()

        // 他人占用 409
        val other = json.readTree(
            authed.post().uri("/api/members")
                .body(mapOf("displayName" to "小李"))
                .exchange().expectStatus().isCreated()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        authed.put().uri("/api/members/${other["memberId"].asText()}/github-username")
            .body(mapOf("githubUsername" to "cwjiang"))
            .exchange().expectStatus().isEqualTo(409)

        // 非法格式 400
        authed.put().uri("/api/members/$meId/github-username")
            .body(mapOf("githubUsername" to "空 格!"))
            .exchange().expectStatus().isBadRequest()

        // 空串清除
        val cleared = json.readTree(
            authed.put().uri("/api/members/$meId/github-username")
                .body(mapOf("githubUsername" to ""))
                .exchange().expectStatus().isOk()
                .expectBody(String::class.java).returnResult().responseBody!!,
        )
        assertTrue(cleared["githubUsername"].isNull)
    }
}
