package yangzhou.api.business.attachment

import yangzhou.api.error.BadRequestException

/** YPJ-50:对象存储唯一 seam(VcsGateway 同款);字节不经后端,presign 直传直取。 */
interface ObjectStorage {
    fun presignPut(
        key: String,
        contentType: String,
    ): String

    fun presignGet(key: String): String

    /** best-effort 删除;失败由调用方 WARN,不影响附件行删除。 */
    fun delete(key: String)
}

/** enabled=false 时的占位驱动:任何调用即 400「未启用附件存储」。 */
class DisabledObjectStorage : ObjectStorage {
    override fun presignPut(
        key: String,
        contentType: String,
    ): String = throw BadRequestException("未启用附件存储")

    override fun presignGet(key: String): String = throw BadRequestException("未启用附件存储")

    override fun delete(key: String) = throw BadRequestException("未启用附件存储")
}
