package yangzhou.api.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/** V6-S2:@Scheduled 轮询(GitHub 同步)总开关。 */
@Configuration
@EnableScheduling
class SchedulingConfig
