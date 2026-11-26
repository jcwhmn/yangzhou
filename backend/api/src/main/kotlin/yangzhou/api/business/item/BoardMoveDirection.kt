package yangzhou.api.business.item

import yangzhou.api.general.DisplayEnum

/** YPJ-285:看板箭头排序方向——wire 字面量 up/down;校验/报错走 DisplayEnum 泛化,服务层不再见协议字面量。 */
enum class BoardMoveDirection(override val displayName: String) : DisplayEnum {
    UP("up"),
    DOWN("down"),
}
