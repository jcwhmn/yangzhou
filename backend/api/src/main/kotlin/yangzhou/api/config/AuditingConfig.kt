package yangzhou.api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.auditing.DateTimeProvider
import org.springframework.data.domain.AuditorAware
import org.springframework.data.jdbc.repository.config.EnableJdbcAuditing
import yangzhou.api.business.member.MemberService
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID
import java.util.Optional

/**
 * audit 四元组统一接管(spec 0051):
 * 时间经全局 Clock(DateTimeProvider);行为者 = 当前登录成员 objectId。
 * 无认证上下文(系统任务,如 github 同步)→ 空 auditor,列存 NULL。
 */
@Configuration
@EnableJdbcAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
class AuditingConfig {

    @Bean
    fun auditorProvider(members: MemberService): AuditorAware<UUID> = AuditorAware {
        try {
            Optional.of(members.current().objectId)
        } catch (_: Exception) {
            Optional.empty()
        }
    }

    @Bean
    fun auditingDateTimeProvider(clock: Clock): DateTimeProvider = DateTimeProvider {
        Optional.of(OffsetDateTime.now(clock))
    }
}
