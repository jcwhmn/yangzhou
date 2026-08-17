package yangzhou.domain

/** Item 侧的属性附着:不设 minLevel 即 presence 需求(有即可)。 */
data class Requirement(val attribute: String, val minLevel: Int? = null)

/** Member 侧的属性附着:level 为 null 表示「有但未评级」。 */
data class Capability(val attribute: String, val level: Int? = null)

/** 人。匹配引擎读取其能力清单。 */
data class Member(val name: String, val capabilities: List<Capability> = emptyList())

/** 工作单元。引擎只消费其编号/标题/需求。 */
data class Item(val id: String, val title: String, val requirements: List<Requirement> = emptyList())

/** 项目 = Item 的集合(引擎视角)。 */
data class Project(val name: String, val items: List<Item> = emptyList())
