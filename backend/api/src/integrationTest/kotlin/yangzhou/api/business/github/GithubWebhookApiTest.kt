package yangzhou.api.business.github

import org.springframework.boot.test.context.SpringBootTest
import yangzhou.api.AbstractApiTest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test

/** YPJ-85:webhook 端点契约——403 全拒 / 200 忽略 / 202 触发(不触外网:未配 PAT 即返回 202)。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["yangzhou.github.webhook-secret=test-webhook-secret"],
)
class GithubWebhookApiTest : AbstractApiTest() {
    private fun sign(body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("test-webhook-secret".toByteArray(), "HmacSHA256"))
        return "sha256=" + mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun post(
        event: String,
        signature: String?,
        body: String,
    ) = rest
        .post()
        .uri("/api/github/webhook")
        .header("X-GitHub-Event", event)
        .apply { if (signature != null) header("X-Hub-Signature-256", signature) }
        .body(body)
        .exchange()

    @Test
    fun missingSignatureIs403() {
        bootstrapAndAuth()
        post("push", null, "{}").expectStatus().isForbidden()
    }

    @Test
    fun badSignatureIs403() {
        bootstrapAndAuth()
        post("push", "sha256=deadbeef", "{}").expectStatus().isForbidden()
    }

    @Test
    fun unknownEventIs200Ignored() {
        bootstrapAndAuth()
        val body = """{"repository":{"full_name":"octo/r"}}"""
        post("issues", sign(body), body)
            .expectStatus()
            .isOk()
            .expectBody()
            .json("""{"status":"ignored"}""")
    }

    @Test
    fun unknownRepoIs200Ignored() {
        bootstrapAndAuth()
        val body = """{"repository":{"full_name":"octo/none"}}"""
        post("push", sign(body), body)
            .expectStatus()
            .isOk()
            .expectBody()
            .json("""{"status":"ignored"}""")
    }

    @Test
    fun pushOnMountedRepoIs202() {
        val authed = bootstrapAndAuth()
        createProject(authed, "CHE")
        authed
            .post()
            .uri("/api/projects/CHE/repos")
            .body(mapOf("repo" to "octo/r"))
            .exchange()
            .expectStatus()
            .isCreated()
        // 不配 workspace PAT → 不触 GitHub API;走「命中挂载」路径返回 202
        val body = """{"repository":{"full_name":"octo/r"}}"""
        post("push", sign(body), body)
            .expectStatus()
            .isAccepted()
            .expectBody()
            .json("""{"status":"accepted"}""")
    }
}
