package yangzhou.api.business.attachment

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import yangzhou.api.config.AttachmentProperties
import yangzhou.api.error.BadRequestException
import yangzhou.persistence.Item
import yangzhou.persistence.repository.AttachmentRepository
import yangzhou.persistence.repository.ItemRepository
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-50:AttachmentService 校验分支与存储 seam(纯单测,mockk + FakeObjectStorage)。 */
class AttachmentServiceTest {

    private val fake = FakeObjectStorage()

    private fun service(
        props: AttachmentProperties = AttachmentProperties(enabled = true),
        storage: ObjectStorage = fake,
        item: Item? = mockk { every { id } returns 1L },
    ): AttachmentService {
        val items = mockk<ItemRepository>()
        every { items.findByObjectId(any()) } returns item
        val attachments = mockk<AttachmentRepository>(relaxed = true)
        every { attachments.save(any()) } answers { firstArg() }
        return AttachmentService(attachments, items, storage, props)
    }

    @Test
    fun `注册成功——objectKey 含净化文件名,presignPut 带 contentType`() {
        val res = service().register(UUID.randomUUID(), "设计稿 v2.PDF", "application/pdf", 1024)
        assertEquals(1, fake.puts.size)
        assertEquals("application/pdf", fake.puts[0].second)
        assertContains(fake.puts[0].first, "设计稿 v2.PDF")
        assertContains(res.uploadUrl, "http://fake/put/")
    }

    @Test
    fun `文件名路径分隔符被净化`() {
        service().register(UUID.randomUUID(), "a/b\\c.pdf", "application/pdf", 1)
        assertContains(fake.puts[0].first, "a_b_c.pdf")
    }

    @Test
    fun `超限与非法扩展与空白名——400`() {
        val s = service()
        assertFailsWith<BadRequestException> { s.register(UUID.randomUUID(), "big.pdf", "application/pdf", 999_999_999_999) }
        assertFailsWith<BadRequestException> { s.register(UUID.randomUUID(), "virus.exe", "application/x-msdownload", 1) }
        assertFailsWith<BadRequestException> { s.register(UUID.randomUUID(), "noext", "text/plain", 1) }
        assertFailsWith<BadRequestException> { s.register(UUID.randomUUID(), "  ", "text/plain", 1) }
        assertEquals(0, fake.puts.size)
    }

    @Test
    fun `未启用——占位驱动抛 400`() {
        val s = service(storage = DisabledObjectStorage())
        val e = assertFailsWith<BadRequestException> { s.register(UUID.randomUUID(), "a.pdf", "application/pdf", 1) }
        assertEquals("未启用附件存储", e.message)
    }
}
