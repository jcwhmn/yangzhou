package yangzhou.api.business.attachment

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import yangzhou.api.config.AttachmentProperties
import yangzhou.api.error.BadRequestException
import yangzhou.api.error.NotFoundException
import yangzhou.persistence.Attachment
import yangzhou.persistence.Item
import yangzhou.persistence.repository.AttachmentRepository
import yangzhou.persistence.repository.ItemRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** YPJ-88 单测补齐:AttachmentService(注册校验/预签名/删除容错)。 */
class AttachmentServiceUnitTest {
    private val attachments = mockk<AttachmentRepository>(relaxed = true)
    private val items = mockk<ItemRepository>(relaxed = true)
    private val storage = mockk<ObjectStorage>(relaxed = true)
    private val props =
        AttachmentProperties(
            enabled = true,
            endpoint = "http://localhost:9000",
            bucket = "yangzhou",
            maxSizeMb = 1,
            allowedExtensions = listOf("pdf", "png"),
        )
    private val service = AttachmentService(attachments, items, storage, props)

    private val item =
        Item(id = 1L, objectId = UUID(0, 1), projectObjectId = UUID(0, 1), number = 1, title = "t", statusObjectId = UUID.randomUUID())

    @BeforeEach
    fun setup() {
        clearMocks(attachments, items, storage)
        every { items.findByObjectId(item.objectId) } returns item
    }

    @Nested
    inner class Register {
        @Test
        fun missingItemRejected() {
            every { items.findByObjectId(any()) } returns null
            assertFailsWith<NotFoundException> { service.register(UUID.randomUUID(), "a.pdf", "application/pdf", 10) }
        }

        @Test
        fun badFilenameRejected() {
            for (name in listOf("", "  ", "x".repeat(256))) {
                assertFailsWith<BadRequestException> { service.register(item.objectId, name, "application/pdf", 10) }
            }
        }

        @Test
        fun disallowedExtensionRejected() {
            assertFailsWith<BadRequestException> { service.register(item.objectId, "a.exe", "app/x", 10) }
            assertFailsWith<BadRequestException> { service.register(item.objectId, "noext", "app/x", 10) }
        }

        @Test
        fun sizeBoundsRejected() {
            assertFailsWith<BadRequestException> { service.register(item.objectId, "a.pdf", "app", 0) }
            assertFailsWith<BadRequestException> { service.register(item.objectId, "a.pdf", "app", 1024 * 1024 + 1) }
        }

        @Test
        fun keySanitizedAndPresigned() {
            every { attachments.save(any()) } answers { firstArg<Attachment>() }
            every { storage.presignPut(any(), any()) } returns "http://put"
            val dto = service.register(item.objectId, "..a/b\\c.PDF", "application/pdf", 100)
            assertEquals("..a/b\\c.PDF", dto.filename) // 原名保留
            verify { storage.presignPut(withArg { assertTrue(it.endsWith("a_b_c.PDF")) }, "application/pdf") }
            verify { attachments.save(withArg { assertEquals(100, it.sizeBytes) }) }
        }
    }

    @Nested
    inner class ListAttachments {
        @Test
        fun mappedRows() {
            val row =
                Attachment(
                    id = 1L,
                    objectId = UUID(0, 1),
                    itemObjectId = UUID(0, 1),
                    filename = "a.pdf",
                    contentType = "application/pdf",
                    sizeBytes = 9,
                    objectKey = "k",
                )
            every { attachments.findByItemObjectId(UUID(0, 1)) } returns listOf(row)
            val dtos = service.list(item.objectId)
            assertEquals(1, dtos.size)
            assertEquals("a.pdf", dtos[0].filename)
        }
    }

    @Nested
    inner class DownloadAndDelete {
        private val row =
            Attachment(
                id = 1L,
                objectId = UUID(0, 1),
                itemObjectId = UUID(0, 1),
                filename = "a.pdf",
                contentType = "p",
                sizeBytes = 9,
                objectKey = "k1",
            )

        @BeforeEach
        fun stubRow() {
            every { attachments.findByObjectIdAndItemObjectId(row.objectId, UUID(0, 1)) } returns row
        }

        @Test
        fun downloadPresignsGet() {
            every { storage.presignGet("k1") } returns "http://get"
            assertEquals("http://get", service.downloadUrl(item.objectId, row.objectId).downloadUrl)
        }

        @Test
        fun crossItemAttachmentIs404() {
            val other = item.copy(id = 2L, objectId = UUID(0, 2))
            every { items.findByObjectId(other.objectId) } returns other
            every { attachments.findByObjectIdAndItemObjectId(row.objectId, UUID(0, 2)) } returns null
            assertFailsWith<NotFoundException> { service.downloadUrl(other.objectId, row.objectId) }
        }

        @Test
        fun s3DeleteFailureDoesNotBreakRowDelete() {
            every { storage.delete("k1") } throws RuntimeException("s3 down")
            service.delete(item.objectId, row.objectId) // 行删成功,S3 失败仅 WARN
            verify { attachments.delete(row) }
        }
    }
}
