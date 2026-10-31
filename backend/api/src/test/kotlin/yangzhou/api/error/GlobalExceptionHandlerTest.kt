package yangzhou.api.error

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import yangzhou.api.error.ConflictException
import yangzhou.api.error.GlobalExceptionHandler
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0055:失败请求必须落 log——5xx ERROR 带栈,4xx WARN;纯单测不走 Spring。 */
class GlobalExceptionHandlerTest {
    private val handler = GlobalExceptionHandler()
    private val appender = ListAppender<ILoggingEvent>()
    private val logger: Logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java) as Logger

    private val req = mockk<HttpServletRequest> { every { requestURI } returns "/api/test" }

    @BeforeTest
    fun setUp() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterTest
    fun tearDown() {
        logger.detachAppender(appender)
        appender.list.clear()
    }

    private fun events(level: Level) = appender.list.filter { it.level == level }

    @Test
    fun `5xx catch-all - ERROR 带异常栈`() {
        val e = RuntimeException("boom")
        val res = handler.internal(e, req)
        assertEquals(500, res.statusCode.value())
        assertEquals("INTERNAL", res.body!!.code)
        val ev = events(Level.ERROR).single()
        assertTrue(ev.formattedMessage.contains("/api/test"))
        assertNotNull(ev.throwableProxy)
    }

    @Test
    fun `4xx 业务冲突 - WARN 一行不带栈`() {
        val res = handler.conflict(ConflictException("不在池内"), req)
        assertEquals(409, res.statusCode.value())
        val ev = events(Level.WARN).single()
        assertTrue(ev.formattedMessage.contains("409"))
        assertTrue(ev.formattedMessage.contains("/api/test"))
        assertNull(ev.throwableProxy)
    }

    @Test
    fun `路径参数类型不匹配 - 400 BAD_REQUEST`() {
        val e =
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                "YPJ-1",
                java.util.UUID::class.java,
                "itemObjectId",
                null,
                null,
            )
        val res = handler.typeMismatch(e, req)
        assertEquals(400, res.statusCode.value())
        assertEquals("BAD_REQUEST", res.body!!.code)
    }
}
