package yangzhou.api.auth

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(private val authService: AuthService) {

    @PostMapping("/bootstrap")
    fun bootstrap(@Valid @RequestBody request: BootstrapRequest): ResponseEntity<AuthService.TokenResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(authService.bootstrap(request.username, request.password))

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: BootstrapRequest): ResponseEntity<AuthService.TokenResponse> =
        ResponseEntity.ok(authService.login(request.username, request.password))
}
