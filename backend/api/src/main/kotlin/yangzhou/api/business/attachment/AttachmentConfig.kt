package yangzhou.api.business.attachment

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import yangzhou.api.config.AttachmentProperties

/** YPJ-50:enabled=true 注册 S3 驱动;false/缺省注册占位(调用即 400)。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AttachmentProperties::class)
class AttachmentConfig {

    @Bean
    @ConditionalOnProperty(prefix = "yangzhou.attachment", name = ["enabled"], havingValue = "true")
    fun s3ObjectStorage(props: AttachmentProperties): S3ObjectStorage = S3ObjectStorage(props)

    @Bean
    @ConditionalOnProperty(
        prefix = "yangzhou.attachment",
        name = ["enabled"],
        havingValue = "false",
        matchIfMissing = true,
    )
    fun disabledObjectStorage(): DisabledObjectStorage = DisabledObjectStorage()
}
