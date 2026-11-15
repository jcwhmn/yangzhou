package yangzhou.api.business.item

import yangzhou.api.error.BadRequestException
import yangzhou.persistence.Item
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/** YPJ-186(spec 0128):预估三态解析与合并(纯函数,不碰 DB)。 */
class EstimateInputTest {
    private fun item(
        v: Double? = null,
        u: String? = null,
    ): Item = Item(
        projectObjectId = UUID.randomUUID(),
        number = 1,
        title = "t",
        statusObjectId = UUID.randomUUID(),
        estimateValue = v,
        estimateUnit = u,
    )

    // —— parse 三态 ——

    @Test
    fun `parse 两null等于不改`() {
        assertNull(EstimateInput.parse(null, null))
    }

    @Test
    fun `parse 空对等于清除`() {
        assertEquals(null to null, EstimateInput.parse("", " "))
    }

    @Test
    fun `parse 成对设置并去空白`() {
        assertEquals(5.0 to "day", EstimateInput.parse("5", "day"))
        assertEquals(2.5 to "hour", EstimateInput.parse(" 2.5 ", "hour"))
    }

    @Test
    fun `parse 单给其一400`() {
        assertFailsWith<BadRequestException> { EstimateInput.parse("5", null) }
        assertFailsWith<BadRequestException> { EstimateInput.parse(null, "day") }
        assertFailsWith<BadRequestException> { EstimateInput.parse("5", "") }
        assertFailsWith<BadRequestException> { EstimateInput.parse("", "day") }
    }

    @Test
    fun `parse 非法值与单位400`() {
        assertFailsWith<BadRequestException> { EstimateInput.parse("abc", "day") }
        assertFailsWith<BadRequestException> { EstimateInput.parse("0", "day") }
        assertFailsWith<BadRequestException> { EstimateInput.parse("1000", "day") }
        assertFailsWith<BadRequestException> { EstimateInput.parse("5", "month") }
    }

    // —— applyEstimate 合并 ——

    @Test
    fun `合并 不改与同值跳过返回原实例`() {
        val cur = item(5.0, "day")
        assertSame(cur, applyEstimate(cur, null, null, required = false))
        assertSame(cur, applyEstimate(cur, "5", "day", required = false))
        assertSame(cur, applyEstimate(cur, "5.0", "day", required = false))
    }

    @Test
    fun `合并 清除与设置`() {
        val cleared = applyEstimate(item(5.0, "day"), "", "", required = false)
        assertNull(cleared.estimateValue)
        assertNull(cleared.estimateUnit)
        val set = applyEstimate(item(), "14", "hour", required = true)
        assertEquals(14.0 to "hour", set.estimateValue to set.estimateUnit)
    }

    @Test
    fun `合并 required项目显式清除400`() {
        assertFailsWith<BadRequestException> { applyEstimate(item(5.0, "day"), "", "", required = true) }
        // 已空再清 = 同值跳过,不拦(required 只禁「从有到无」);同一实例才谈得上 assertSame
        val empty = item()
        assertSame(empty, applyEstimate(empty, "", "", required = true))
    }

    @Test
    fun `合并 required项目设置不受拦`() {
        assertEquals(8.0, applyEstimate(item(), "8", "hour", required = true).estimateValue)
    }

    // —— estimateText ——

    @Test
    fun `文本格式去尾零`() {
        assertEquals("5 day", estimateText(5.0, "day"))
        assertEquals("2.5 hour", estimateText(2.5, "hour"))
        assertEquals("(空)", estimateText(null, null))
    }
}
