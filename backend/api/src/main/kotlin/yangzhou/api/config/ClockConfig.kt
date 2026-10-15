package yangzhou.api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** 时间敏感代码的注入点(backend-testing skill:凡读时间,构造注入 Clock)。 */
@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
