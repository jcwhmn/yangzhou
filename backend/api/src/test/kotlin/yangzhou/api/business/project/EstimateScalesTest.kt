package yangzhou.api.business.project

import tools.jackson.databind.ObjectMapper
import yangzhou.api.error.BadRequestException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** YPJ-180(spec 0126):工作量刻度纯函数。 */
class EstimateScalesTest {
    private val mapper = ObjectMapper()

    @Test
    fun `parse null 与空白返回默认五档`() {
        assertEquals(EstimateScales.DEFAULT, EstimateScales.parse(mapper, null))
        assertEquals(EstimateScales.DEFAULT, EstimateScales.parse(mapper, ""))
        assertEquals(EstimateScales.DEFAULT, EstimateScales.parse(mapper, "  "))
        assertEquals(listOf("微", "小", "中", "大", "超大"), EstimateScales.DEFAULT.map { it.label })
        assertEquals(listOf(2.0, 3.0, 5.0, 8.0, 13.0), EstimateScales.DEFAULT.map { it.value })
    }

    @Test
    fun `parse 损坏 JSON 降级默认不抛`() {
        assertEquals(EstimateScales.DEFAULT, EstimateScales.parse(mapper, "{not json"))
    }

    @Test
    fun `serialize 再 parse 往返保真`() {
        val scales = listOf(EstimateScaleDto("h8", "标准日", 8.0, "hour"))
        assertEquals(scales, EstimateScales.parse(mapper, EstimateScales.serialize(mapper, scales)))
    }

    @Test
    fun `validate 负例集合`() {
        EstimateScales.validate(EstimateScales.DEFAULT) // 基线合法
        assertFailsWith<BadRequestException> { EstimateScales.validate(emptyList()) }
        assertFailsWith<BadRequestException> { EstimateScales.validate(List(11) { EstimateScaleDto("k$it", "档", 1.0, "day") }) }
        assertFailsWith<BadRequestException> { EstimateScales.validate(listOf(EstimateScaleDto("BAD KEY", "档", 1.0, "day"))) }
        assertFailsWith<BadRequestException> {
            EstimateScales.validate(listOf(EstimateScaleDto("k", "档", 1.0, "day"), EstimateScaleDto("k", "档二", 2.0, "day")))
        }
        assertFailsWith<BadRequestException> { EstimateScales.validate(listOf(EstimateScaleDto("k", " ", 1.0, "day"))) }
        assertFailsWith<BadRequestException> { EstimateScales.validate(listOf(EstimateScaleDto("k", "长".repeat(17), 1.0, "day"))) }
        assertFailsWith<BadRequestException> { EstimateScales.validate(listOf(EstimateScaleDto("k", "档", 0.0, "day"))) }
        assertFailsWith<BadRequestException> { EstimateScales.validate(listOf(EstimateScaleDto("k", "档", 1000.0, "day"))) }
        assertFailsWith<BadRequestException> { EstimateScales.validate(listOf(EstimateScaleDto("k", "档", 1.0, " "))) }
        assertFailsWith<BadRequestException> { EstimateScales.validate(listOf(EstimateScaleDto("k", "档", 1.0, "天"))) } // 单位封闭枚举,自由文本拒
        assertFailsWith<BadRequestException> { EstimateScales.validate(listOf(EstimateScaleDto("k", "档", 1.0, "月"))) }
    }
}
