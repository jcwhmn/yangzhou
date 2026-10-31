package yangzhou.api.business.attachment

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import yangzhou.api.config.AttachmentProperties
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Attachment
import yangzhou.persistence.repository.AttachmentRepository
import yangzhou.persistence.repository.ItemRepository
import java.util.UUID

/** YPJ-50:附件注册/列表/下载 URL/删除;校验(大小/扩展名)在此,字节不走后端。 */
@Service
class AttachmentService(
    private val attachments: AttachmentRepository,
    private val items: ItemRepository,
    private val storage: ObjectStorage,
    private val props: AttachmentProperties,
) {
    private val log = LoggerFactory.getLogger(AttachmentService::class.java)

    @Transactional
    fun register(
        itemObjectId: UUID,
        filename: String,
        contentType: String,
        sizeBytes: Long,
    ): AttachmentUploadedResponse {
        val item = items.findByObjectId(itemObjectId) ?: throw NotFoundException("item 不存在")
        val name = filename.trim()
        if (name.isEmpty() || name.length > 255) throw BadRequestException("文件名非法")
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty() || ext !in props.allowedExtensions) {
            throw BadRequestException("不允许的文件类型:$ext")
        }
        if (sizeBytes <= 0 || sizeBytes > props.maxSizeMb * 1024 * 1024) {
            throw BadRequestException("文件大小超限(最大 ${props.maxSizeMb}MB)")
        }
        val objectId = UUID.randomUUID()
        val key = "attachments/$objectId/${name.replace(Regex("[/\\\\]"), "_").trim('.')}"
        val entity =
            attachments.save(
                Attachment(
                    objectId = objectId,
                    itemObjectId = item.objectId!!,
                    filename = name,
                    contentType = contentType,
                    sizeBytes = sizeBytes,
                    objectKey = key,
                ),
            )
        return AttachmentUploadedResponse(
            attachmentId = entity.objectId,
            filename = name,
            contentType = contentType,
            sizeBytes = sizeBytes,
            uploadUrl = storage.presignPut(key, contentType),
        )
    }

    @Transactional(readOnly = true)
    fun list(itemObjectId: UUID): List<AttachmentResponse> {
        val item = items.findByObjectId(itemObjectId) ?: throw NotFoundException("item 不存在")
        return attachments.findByItemObjectId(item.objectId!!).map { it.toResponse() }
    }

    @Transactional(readOnly = true)
    fun downloadUrl(
        itemObjectId: UUID,
        attachmentObjectId: UUID,
    ): AttachmentDownloadResponse {
        val row = requireRow(itemObjectId, attachmentObjectId)
        return AttachmentDownloadResponse(downloadUrl = storage.presignGet(row.objectKey))
    }

    @Transactional
    fun delete(
        itemObjectId: UUID,
        attachmentObjectId: UUID,
    ) {
        val row = requireRow(itemObjectId, attachmentObjectId)
        attachments.delete(row)
        runCatching { storage.delete(row.objectKey) }
            .onFailure { log.warn("S3 对象删除失败(附件行已删): {}: {}", row.objectKey, it.message) }
    }

    private fun requireRow(
        itemObjectId: UUID,
        attachmentObjectId: UUID,
    ): Attachment {
        val item = items.findByObjectId(itemObjectId) ?: throw NotFoundException("item 不存在")
        return attachments.findByObjectIdAndItemObjectId(attachmentObjectId, item.objectId!!)
            ?: throw NotFoundException("附件不存在")
    }

    private fun Attachment.toResponse() = AttachmentResponse(
        attachmentId = objectId,
        filename = filename,
        contentType = contentType,
        sizeBytes = sizeBytes,
    )
}

data class AttachmentResponse(
    val attachmentId: UUID,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
)

data class AttachmentUploadedResponse(
    val attachmentId: UUID,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val uploadUrl: String,
)

data class AttachmentDownloadResponse(
    val downloadUrl: String,
)
