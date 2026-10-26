package yangzhou.api.general

import org.springframework.core.io.ClassPathResource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** YPJ-101:构建期注入的版本号(git 最近 tag,见 api/build.gradle.kts processResources);无 git 环境 = dev。 */
@RestController
@RequestMapping("/api")
class VersionController {

    @GetMapping("/version")
    fun version(): Map<String, String> = mapOf("version" to VERSION)

    companion object {
        private val VERSION: String by lazy {
            runCatching {
                ClassPathResource("version.txt").getContentAsString(java.nio.charset.StandardCharsets.UTF_8).trim()
            }.getOrNull()?.takeIf { it.isNotEmpty() && it != "__VERSION__" } ?: "dev"
        }
    }
}
