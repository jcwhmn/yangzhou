package yangzhou.api.config

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import yangzhou.api.support.BadRequestException
import yangzhou.api.support.ConflictException
import yangzhou.api.support.NotFoundException

data class ApiError(
    val code: String,
    val message: String,
    val path: String,
    val fields: Map<String, String>? = null,
)

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException::class)
    fun notFound(e: NotFoundException, req: HttpServletRequest) =
        error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.message ?: "资源不存在", req)

    @ExceptionHandler(ConflictException::class)
    fun conflict(e: ConflictException, req: HttpServletRequest) =
        error(HttpStatus.CONFLICT, "CONFLICT", e.message ?: "冲突", req)

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException::class)
    fun notReadable(e: org.springframework.http.converter.HttpMessageNotReadableException, req: HttpServletRequest): ResponseEntity<ApiError> {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "请求体不合法", req)
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException::class)
    fun integrity(e: org.springframework.dao.DataIntegrityViolationException, req: HttpServletRequest) =
        error(HttpStatus.CONFLICT, "CONFLICT", "数据约束冲突", req)

    @ExceptionHandler(BadRequestException::class, IllegalArgumentException::class)
    fun badRequest(e: Exception, req: HttpServletRequest) =
        error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.message ?: "请求不合法", req)

    @ExceptionHandler(BadCredentialsException::class)
    fun unauthorized(e: BadCredentialsException, req: HttpServletRequest) =
        error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.message ?: "认证失败", req)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(e: MethodArgumentNotValidException, req: HttpServletRequest): ResponseEntity<ApiError> {
        val fields = e.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "不合法") }
        val body = ApiError("VALIDATION", "请求字段不合法", req.requestURI, fields)
        return ResponseEntity.badRequest().body(body)
    }

    private fun error(
        status: HttpStatus,
        code: String,
        message: String,
        req: HttpServletRequest,
    ): ResponseEntity<ApiError> = ResponseEntity.status(status).body(ApiError(code, message, req.requestURI))
}
