package yangzhou.api.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** YPJ-50:附件存储配置(enabled=false 时任何附件端点 400「未启用附件存储」)。 */
@ConfigurationProperties(prefix = "yangzhou.attachment")
data class AttachmentProperties(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val region: String = "us-east-1",
    val bucket: String = "",
    val accessKey: String = "",
    val secretKey: String = "",
    val maxSizeMb: Long = 20,
    val allowedExtensions: List<String> = listOf(
        "pdf", "png", "jpg", "jpeg", "gif", "webp", "txt", "md", "csv", "xlsx", "docx", "pptx", "zip",
    ),
)
