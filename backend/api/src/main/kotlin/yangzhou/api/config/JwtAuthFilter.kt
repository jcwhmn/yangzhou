package yangzhou.api.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthFilter(private val jwt: JwtService) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        request.getHeader("Authorization")
            ?.takeIf { it.startsWith("Bearer ") }
            ?.let { jwt.verify(it.removePrefix("Bearer ")) }
            ?.let { subject ->
                SecurityContextHolder.getContext().authentication =
                    UsernamePasswordAuthenticationToken(subject, null, emptyList())
            }
        chain.doFilter(request, response)
    }
}
