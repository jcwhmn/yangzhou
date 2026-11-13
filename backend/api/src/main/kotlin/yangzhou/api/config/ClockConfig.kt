package yangzhou.api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** 时间敏感代码的注入点(backend-testing skill:凡读时间,构造注入 Clock)。 */
@Configuration
class ClockConfig {
    @Bean
    // YPJ-175(spec 0120 方案 A):全局本地历——徽标日界随运行环境时区;容器由 compose 注入 TZ(默认 Asia/Shanghai),
    // Instant 时间戳不受影响(无 zone 语义),Metrics 日切桶显式 UTC 不走此语义
    fun clock(): Clock = Clock.systemDefaultZone()
}
