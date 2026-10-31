package yangzhou.api.general

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.util.ContentCachingRequestWrapper
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.charset.StandardCharsets

/**
 * YPJ-33:REST API 访问日志——每 call 一行(method/path/status/durationMs,写操作附 body)。
 * 脱敏硬规则:body JSON 键名命中拒绝表 → "***",原文永不落日志;body 截 500 chars。
 * 级别:写操作 INFO / 其余 DEBUG(logger yangzhou.api.access,yml 可调)。
 */
@Component
class AccessLogFilter(
    private val mapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val p = request.requestURI
        return p.startsWith("/scalar") || p.startsWith("/v3/api-docs") || p == "/error"
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val start = System.currentTimeMillis()
        val wrapped = ContentCachingRequestWrapper(request, 8192) // cacheLimit 8KB:截断 500 chars 足够,防大 body 占内存
        try {
            chain.doFilter(wrapped, response)
        } finally {
            val method = request.method
            val body = if (method in WRITES) readBody(wrapped) else ""
            val line =
                buildString {
                    append("method=").append(method)
                    append(" path=").append(request.requestURI)
                    append(" status=").append(response.status)
                    append(" durationMs=").append(System.currentTimeMillis() - start)
                    (wrapped.getAttribute(ATTR) as? String)?.let { append(" handler=").append(it) }
                    if (body.isNotEmpty()) append(" body=").append(body)
                }
            if (method in WRITES) LOG.info(line) else LOG.debug(line)
        }
    }

    /** body 仅在下游已消费后才可读(JWT 401 等提前短路时为空 → 不落 body 段)。 */
    private fun readBody(wrapped: ContentCachingRequestWrapper): String {
        val raw = String(wrapped.contentAsByteArray, StandardCharsets.UTF_8).trim()
        if (raw.isEmpty()) return ""
        val masked =
            try {
                mask(mapper.readTree(raw)).toString()
            } catch (_: Exception) {
                raw // 非 JSON:原样截断,不炸
            }
        return truncate(masked.replace('\n', ' '))
    }

    /** 递归脱敏:拒绝表键名(大小写不敏感)→ "***"。 */
    private fun mask(node: JsonNode): JsonNode {
        when {
            node.isObject -> {
                val obj = node as ObjectNode
                node.properties().toList().forEach { (name, child) ->
                    if (name.lowercase() in SENSITIVE) {
                        obj.put(name, "***")
                    } else {
                        mask(child)
                    }
                }
            }
            node.isArray -> node.forEach { mask(it) }
        }
        return node
    }

    companion object {
        private val LOG = LoggerFactory.getLogger("yangzhou.api.access")
        private val WRITES = setOf("POST", "PUT", "PATCH", "DELETE")
        private val SENSITIVE = setOf("password", "token", "secret", "githubtoken", "authorization", "apikey")
        private const val MAX_BODY = 500
        internal const val ATTR = "yz.access.handler"

        private fun truncate(s: String) = if (s.length <= MAX_BODY) s else s.take(MAX_BODY) + "…(truncated)"
    }
}

/** 把实际 handler(AuthController#login)带给 AccessLogFilter——filter 层拿不到 HandlerMethod。 */
@Component
class AccessLogHandlerInterceptor : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (handler is HandlerMethod) {
            request.setAttribute(AccessLogFilter.ATTR, "${handler.beanType.simpleName}#${handler.method.name}")
        }
        return true
    }
}

@Configuration(proxyBeanMethods = false)
class AccessLogWebConfig(
    private val interceptor: AccessLogHandlerInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor)
    }
}
