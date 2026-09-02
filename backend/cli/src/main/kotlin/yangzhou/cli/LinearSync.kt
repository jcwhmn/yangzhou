package yangzhou.cli

import yangzhou.cli.ApiClient
import yangzhou.cli.ExportImport
import tools.jackson.databind.json.JsonMapper
import java.io.File

/**
 * Linear 联邦(单向拉取,V3):Linear 为 source of truth,
 * 按 external_ref("linear:<identifier>")幂等 upsert——首跑导入,重跑更新,零重复。
 * 镜像 item 是 Linear 的影子:本地对它的编辑会被下次同步覆盖(README 明示)。
 */
object LinearSync {

    private val mapper = JsonMapper.builder().build()

    fun sync(api: ApiClient, projectKey: String, csvFile: File) {
        val rows = ExportImport.parseCsv(csvFile.readText())
        if (rows.isEmpty()) error("CSV 无数据行")
        val headers = rows.first().keys
        fun col(vararg names: String): String? =
            names.firstOrNull { n -> headers.any { it.trim().equals(n, ignoreCase = true) } }

        val idCol = col("Identifier", "ID", "编号") ?: error("CSV 缺 Identifier 列(Linear 导出含此列)")
        val titleCol = col("Title", "标题") ?: error("CSV 缺标题列(Title/标题)")
        val statusCol = col("Status", "状态")
        val labelsCol = col("Labels", "标签")
        val descCol = col("Description", "描述")

        val project = api.json("GET", "/api/projects/$projectKey")
        val statusByName = mutableMapOf<String, String>()
        project["statuses"].forEach { st -> statusByName[st["name"].asString()] = st["statusId"].asString() }
        // 别名:Linear 的 "In Progress" → 项目改名为 "Development" 的列(默认模板 V3.5 起)
        if (!statusByName.containsKey("In Progress") && statusByName.containsKey("Development")) {
            statusByName["In Progress"] = statusByName.getValue("Development")
        }
        val knownAttrs = mutableSetOf<String>()
        api.json("GET", "/api/attributes").forEach { a -> knownAttrs += a["name"].asString() }

        // 存量镜像:externalRef → itemId(重跑走更新)
        val existing = mutableMapOf<String, String>()
        api.json("GET", "/api/projects/$projectKey/items").forEach { i ->
            val ref = i["externalRef"]?.let { n -> if (n.isNull) null else n.asString() }
            if (ref != null) existing[ref] = i["itemId"].asString()
        }

        fun requirementsOf(row: Map<String, String>): List<Map<String, Any?>> =
            (row[labelsCol] ?: "")
                .split(',', ';', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { label ->
                    val m = Regex("^(.+?)(?:>=(\\d))?$").find(label) ?: error("标签格式:$label")
                    val (attr, level) = m.destructured
                    if (attr !in knownAttrs) {
                        api.json("POST", "/api/attributes", mapOf("name" to attr, "kind" to "label", "leveled" to level.isNotEmpty()))
                        knownAttrs += attr
                    }
                    if (level.isEmpty()) mapOf<String, Any?>("attribute" to attr, "minLevel" to null)
                    else mapOf<String, Any?>("attribute" to attr, "minLevel" to level.toInt())
                }

        var created = 0
        var updated = 0
        rows.forEachIndexed { index, row ->
            val identifier = row[idCol]?.trim().orEmpty().ifEmpty { return@forEachIndexed }
            val ref = "linear:$identifier"
            val title = row[titleCol]?.trim().orEmpty().ifEmpty { error("行 ${index + 2}:标题为空") }
            val description = row[descCol]?.trim()?.takeIf { it.isNotEmpty() }
            val statusName = row[statusCol]?.trim()?.takeIf { it.isNotEmpty() }
            val requirements = requirementsOf(row)
            val itemId = existing[ref]

            val item = if (itemId != null) {
                updated++
                api.json("PATCH", "/api/items/$itemId", mapOf("title" to title, "description" to description))
                // 状态回放:更新与新建语义一致
                statusName?.let { n -> statusByName[n] }?.let { sid ->
                    api.json("PATCH", "/api/items/$itemId", mapOf("statusItemId" to sid))
                }
                api.json("PUT", "/api/items/$itemId/requirements", mapOf("requirements" to requirements))
                api.json("GET", "/api/items/$itemId")
            } else {
                created++
                val createdItem = api.json("POST", "/api/projects/$projectKey/items", itemBody(title, description, requirements, ref))
                val newId = createdItem["itemId"].asString()
                statusName?.let { n -> statusByName[n] }?.let { sid ->
                    api.json("PATCH", "/api/items/$newId", mapOf("statusItemId" to sid))
                }
                api.json("GET", "/api/items/$newId")
            }
            val number = item["number"].asString()
            if ((created + updated) % 100 == 0) println("已同步 ${created + updated}/${rows.size}")
        }
        println("同步完成:新建 $created · 更新 $updated(共 ${rows.size} 行)→ 项目 $projectKey")
    }

    private fun itemBody(title: String, description: String?, requirements: List<Map<String, Any?>>, ref: String) =
        mapOf(
            "title" to title,
            "description" to description,
            "type" to "task",
            "externalRef" to ref,
            "requirements" to requirements,
        )
}
