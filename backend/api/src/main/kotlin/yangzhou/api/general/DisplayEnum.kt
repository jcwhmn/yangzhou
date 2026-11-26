package yangzhou.api.general

import yangzhou.api.error.BadRequestException

/**
 * 字面量协议枚举的通用模式(YPJ-285):displayName 即对外 wire 字面量。
 * String→枚举的校验与报错由此口泛化——调用方不 hardcode 合法值清单,报错自动列举全部合法 displayName。
 * 用法:`DisplayEnum.parse<SomeDirection>(raw)`。
 */
interface DisplayEnum {
    val displayName: String

    companion object {
        /** 解析失败抛 400,报错列举该枚举全部合法 displayName。 */
        inline fun <reified E> parse(raw: String?): E where E : Enum<E>, E : DisplayEnum = enumValues<E>().firstOrNull { it.displayName == raw?.trim() }
            ?: throw BadRequestException(
                "非法值:${raw ?: "<null>"}(允许:${enumValues<E>().joinToString("/") { it.displayName }})",
            )
    }
}
