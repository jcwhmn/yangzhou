package yangzhou.api.error

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.ConflictException
import yangzhou.api.error.NotFoundException

data class ApiError(
    val code: String,
    val message: String,
    val path: String,
    val fields: Map<String, String>? = null,
)

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(NotFoundException::class)
    fun notFound(
        e: NotFoundException,
        req: HttpServletRequest,
    ) = error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.message ?: "资源不存在", req)

    @ExceptionHandler(ConflictException::class)
    fun conflict(
        e: ConflictException,
        req: HttpServletRequest,
    ) = error(HttpStatus.CONFLICT, "CONFLICT", e.message ?: "冲突", req)

    @ExceptionHandler(ForbiddenException::class)
    fun forbidden(
        e: ForbiddenException,
        req: HttpServletRequest,
    ) = error(HttpStatus.FORBIDDEN, "FORBIDDEN", e.message ?: "无权操作", req)

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException::class)
    fun notReadable(
        e: org.springframework.http.converter.HttpMessageNotReadableException,
        req: HttpServletRequest,
    ): ResponseEntity<ApiError> = error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "请求体不合法", req)

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException::class)
    fun integrity(
        e: org.springframework.dao.DataIntegrityViolationException,
        req: HttpServletRequest,
    ): ResponseEntity<ApiError> = error(HttpStatus.CONFLICT, "CONFLICT", "数据约束冲突", req)

    @ExceptionHandler(BadRequestException::class, IllegalArgumentException::class)
    fun badRequest(
        e: Exception,
        req: HttpServletRequest,
    ) = error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.message ?: "请求不合法", req)

    @ExceptionHandler(BadCredentialsException::class)
    fun unauthorized(
        e: BadCredentialsException,
        req: HttpServletRequest,
    ) = error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.message ?: "认证失败", req)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(
        e: MethodArgumentNotValidException,
        req: HttpServletRequest,
    ): ResponseEntity<ApiError> {
        val fields = e.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "不合法") }
        log.warn("API 400 VALIDATION path={} fields={}", req.requestURI, fields)
        val body = ApiError("VALIDATION", "请求字段不合法", req.requestURI, fields)
        return ResponseEntity.badRequest().body(body)
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException::class)
    fun methodNotSupported(
        e: org.springframework.web.HttpRequestMethodNotSupportedException,
        req: HttpServletRequest,
    ) = error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "不支持的请求方法", req)

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException::class)
    fun typeMismatch(
        e: org.springframework.web.method.annotation.MethodArgumentTypeMismatchException,
        req: HttpServletRequest,
    ) = error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "路径参数类型不合法", req)

    /** catch-all 兜底(spec 0051):未归类异常 → INTERNAL 通用体,不泄栈与内部信息。 */
    @ExceptionHandler(Exception::class)
    fun internal(
        e: Exception,
        req: HttpServletRequest,
    ): ResponseEntity<ApiError> = error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "内部错误", req, e)

    /** 0055:失败请求必须落 log——5xx ERROR 带栈,4xx WARN 一行;不开 debug 也能查。 */
    private fun error(
        status: HttpStatus,
        code: String,
        message: String,
        req: HttpServletRequest,
        e: Throwable? = null,
    ): ResponseEntity<ApiError> {
        val line = "API {} {} path={} msg={}"
        if (status.is5xxServerError) {
            log.error(line, status, code, req.requestURI, message, e)
        } else {
            log.warn(line, status, code, req.requestURI, message)
        }
        return ResponseEntity.status(status).body(ApiError(code, message, req.requestURI))
    }
}
