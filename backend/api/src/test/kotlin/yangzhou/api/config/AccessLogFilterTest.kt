package yangzhou.api.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import yangzhou.api.AbstractApiTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** YPJ-33:访问日志黑盒——每 call 一行、写操作 INFO、密码脱敏、GET 默认不落。 */
// 级别钉在本类自己的 context:DEBUG 变体类会全局改 logger 级别,复用缓存 context 时 startup 不重放 yml → 顺序依赖偶发
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["logging.level.yangzhou.api.access=INFO"],
)
@ExtendWith(OutputCaptureExtension::class)
class AccessLogFilterTest : AbstractApiTest() {

    @Test
    fun `bootstrap(写操作)落 INFO 一行——密码字段 mask 原文不落`(captured: CapturedOutput) {
        rest.post().uri("/api/auth/bootstrap")
            .body(mapOf("username" to "me", "password" to "pw-plain-123"))
            .exchange()
            .expectStatus().isCreated()

        val logs = captured.toString()
        assertTrue("method=POST path=/api/auth/bootstrap status=201" in logs, logs)
        assertTrue("\"password\":\"***\"" in logs, logs)
        assertFalse("pw-plain-123" in logs, logs)
    }

    @Test
    fun `GET 默认级别不落——写操作 INFO 存在`(captured: CapturedOutput) {
        val authed = bootstrapAndAuth()
        authed.get().uri("/api/projects").exchange().expectStatus().isOk()

        val logs = captured.toString()
        assertTrue("method=POST path=/api/auth" in logs, logs)
        assertFalse("method=GET path=/api/projects" in logs, logs)
    }

    @Test
    fun `401 短路也留痕——无 token 访问受保护端点`(captured: CapturedOutput) {
        rest.post().uri("/api/projects")
            .body(mapOf("key" to "X1", "name" to "x"))
            .exchange()
            .expectStatus().isUnauthorized()

        // body 未被下游消费 → 不落 body 段;POST=INFO 默认可见
        assertTrue("method=POST path=/api/projects status=401" in captured.toString(), captured.toString())
    }
}

/** DEBUG 变体:GET 落行(properties 覆盖 yml,验收「yml 可调」)。 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["logging.level.yangzhou.api.access=DEBUG"],
)
@ExtendWith(OutputCaptureExtension::class)
class AccessLogFilterDebugLevelTest : AbstractApiTest() {

    @Test
    fun `GET 在 DEBUG 级别落行`(captured: CapturedOutput) {
        val authed = bootstrapAndAuth()
        authed.get().uri("/api/projects").exchange().expectStatus().isOk()

        val logs = captured.toString()
        assertTrue("method=GET path=/api/projects status=200" in logs, logs)
    }
}
