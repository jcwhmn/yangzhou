package yangzhou.api.business.project

import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import yangzhou.api.error.BadRequestException

/** 工作量刻度单档(spec 0126):key 稳定标识,label/value/unit 项目级可配置。 */
data class EstimateScaleDto(
    val key: String,
    val label: String,
    val value: Double,
    val unit: String,
)

/**
 * YPJ-180:项目级工作量刻度。存 projects.estimate_scales(JSON 文本),null=内置默认;
 * 无 CHECK 约束——档位集合是动态数据,合法性在此集中校验。
 */
object EstimateScales {
    private val log = LoggerFactory.getLogger(EstimateScales::class.java)
    private val KEY_RE = Regex("^[a-z0-9_-]{1,32}$")

    /** 初始占位默认(Fibonacci 从 2 起,单位天);项目可整体替换。 */
    val DEFAULT =
        listOf(
            EstimateScaleDto("xs", "微", 2.0, "天"),
            EstimateScaleDto("s", "小", 3.0, "天"),
            EstimateScaleDto("m", "中", 5.0, "天"),
            EstimateScaleDto("l", "大", 8.0, "天"),
            EstimateScaleDto("xl", "超大", 13.0, "天"),
        )

    /** 解析存量 JSON;null/损坏降级为默认(warn 不 500)。readTree 手解(仓内 Jackson 3 惯例,不依赖 kotlin module 注册)。 */
    fun parse(mapper: ObjectMapper, json: String?): List<EstimateScaleDto> {
        if (json.isNullOrBlank()) return DEFAULT
        return try {
            val node = mapper.readTree(json)
            if (!node.isArray) return DEFAULT
            node.map { EstimateScaleDto(it["key"].asText(), it["label"].asText(), it["value"].asDouble(), it["unit"].asText()) }
        } catch (e: Exception) {
            log.warn("estimate_scales 解析失败,降级默认:{}", e.message)
            DEFAULT
        }
    }

    fun serialize(mapper: ObjectMapper, scales: List<EstimateScaleDto>): String = mapper.writeValueAsString(scales.map { mapOf("key" to it.key, "label" to it.label, "value" to it.value, "unit" to it.unit) })

    /** 写入前校验(spec 0126):1–10 档;key 格式唯一;label/unit 非空限长;value>0≤999。 */
    fun validate(scales: List<EstimateScaleDto>) {
        if (scales.isEmpty()) throw BadRequestException("工作量刻度至少 1 档")
        if (scales.size > 10) throw BadRequestException("工作量刻度最多 10 档")
        val keys = mutableSetOf<String>()
        scales.forEachIndexed { i, s ->
            val at = "estimateScales[$i]"
            if (!KEY_RE.matches(s.key)) throw BadRequestException("$at.key 须为 1–32 位小写字母/数字/-/_")
            if (!keys.add(s.key)) throw BadRequestException("$at.key 重复:${s.key}")
            if (s.label.isBlank() || s.label.length > 16) throw BadRequestException("$at.label 须 1–16 字符")
            if (s.value <= 0 || s.value > 999) throw BadRequestException("$at.value 须 (0,999]")
            if (s.unit.isBlank() || s.unit.length > 8) throw BadRequestException("$at.unit 须 1–8 字符")
        }
    }
}
