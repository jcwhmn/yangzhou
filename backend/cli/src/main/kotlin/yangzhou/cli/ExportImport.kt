package yangzhou.cli

import tools.jackson.databind.json.JsonMapper
import java.io.File

/**
 * 导入导出(票 7/7):
 * - JSON 全保真:树(ref/parent)、type、状态名、requirement;导入拓扑序创建、编号重排、状态按名回放
 * - CSV 平面子集:手写引号解析(RFC4180 子集);Linear 头映射(Title/Status/Labels → 标题/状态/presence 需求)
 * 均为 API 瘦客户端:不直连 DB。
 */
object ExportImport {

    private val mapper = JsonMapper.builder().build()

    // ---------- JSON ----------

    fun exportJson(api: ApiClient, key: String, file: File) {
        val items = api.json("GET", "/api/projects/$key/items")
        val project = api.json("GET", "/api/projects/$key")
        val idToRef = mutableMapOf<String, String>()
        items.forEach { n -> idToRef[n["itemId"].asString()] = n["number"].asString() }
        val out = mapOf(
            "format" to "yangzhou-items/1",
            "project" to mapOf("key" to project["key"].asString(), "name" to project["name"].asString()),
            "items" to buildList {
                items.forEach { i ->
                    add(
                        mapOf<String, Any?>(
                            "ref" to i["number"].asString(),
                            "title" to i["title"].asString(),
                            "description" to i["description"].let { d -> if (d.isNull) null else d.asString() },
                            "type" to i["type"].asString(),
                            "status" to i["status"].asString(),
                            "parent" to i["parentItemId"].let { p -> if (p.isNull) null else idToRef[p.asString()] },
                            "requirements" to buildList {
                                i["requirements"].forEach { r ->
                                    add(
                                        mapOf<String, Any?>(
                                            "attribute" to r["attribute"].asString(),
                                            "minLevel" to r["minLevel"].let { l -> if (l.isNull) null else l.asInt() },
                                        ),
                                    )
                                }
                            },
                        ),
                    )
                }
            },
        )
        file.writeText(mapper.writeValueAsString(out))
        println("已导出 ${items.size()} 个 item → ${file.absolutePath}")
    }

    fun importJson(api: ApiClient, key: String, file: File) {
        val tree = mapper.readTree(file.readText())
        if (tree["format"].asString() != "yangzhou-items/1") error("不识别的格式:${tree["format"]}")
        val items = tree["items"]
        val project = api.json("GET", "/api/projects/$key")
        val statusByName = mutableMapOf<String, String>()
        project["statuses"].forEach { st -> statusByName[st["name"].asString()] = st["statusId"].asString() }

        val pending = mutableListOf<tools.jackson.databind.JsonNode>()
        items.forEach { node -> pending.add(node) }
        val refToItemId = mutableMapOf<String, String>()
        var created = 0
        while (pending.isNotEmpty()) {
            val before = pending.size
            val it = pending.iterator()
            while (it.hasNext()) {
                val item = it.next()
                val parent = item["parent"].let { p -> if (p.isNull) null else p.asString() }
                if (parent != null && parent !in refToItemId) continue // 父未建;父不存在则下轮死锁报错
                val body = api.json(
                    "POST",
                    "/api/projects/$key/items",
                    mapOf(
                        "title" to item["title"].asString(),
                        "description" to item["description"].let { d -> if (d.isNull) null else d.asString() },
                        "type" to item["type"].asString(),
                        "parentItemId" to parent?.let { p -> refToItemId[p] },
                        "requirements" to buildList {
                            item["requirements"].forEach { r ->
                                add(
                                    mapOf<String, Any?>(
                                        "attribute" to r["attribute"].asString(),
                                        "minLevel" to r["minLevel"].let { l -> if (l.isNull) null else l.asInt() },
                                    ),
                                )
                            }
                        },
                    ),
                )
                val itemId = body["itemId"].asString()
                refToItemId[item["ref"].asString()] = itemId
                statusByName[item["status"].asString()]?.let { sid ->
                    api.json("PATCH", "/api/items/$itemId", mapOf("statusItemId" to sid))
                }
                it.remove()
                created++
                if (created % 100 == 0) println("已导入 $created/${items.size()}")
            }
            if (pending.size == before) {
                error("父引用无法解析(缺父或成环):${pending.take(3).joinToString { it["ref"].asString() }}")
            }
        }
        println("导入完成:$created 个 item → 项目 $key(编号已重排)")
    }

    // ---------- CSV(手写 RFC4180 子集:引号/双引号转义/字段内逗号换行) ----------

    fun exportCsv(api: ApiClient, key: String, file: File) {
        val items = api.json("GET", "/api/projects/$key/items")
        fun esc(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"${v.replace("\"", "\"\"")}\"" else v
        val sb = StringBuilder("number,title,type,status,requirements,description\n")
        items.forEach { i ->
            val reqs = i["requirements"].joinToString(";") { r ->
                val lvl = r["minLevel"]
                if (lvl.isNull) r["attribute"].asString() else "${r["attribute"].asString()}>=${lvl.asInt()}"
            }
            val desc = i["description"].let { d -> if (d.isNull) "" else d.asString() }
            sb.appendLine(
                listOf(
                    esc(i["number"].asString()), esc(i["title"].asString()), esc(i["type"].asString()),
                    esc(i["status"].asString()), esc(reqs), esc(desc),
                ).joinToString(","),
            )
        }
        file.writeText(sb.toString())
        println("已导出 ${items.size()} 行 CSV → ${file.absolutePath}")
    }

    fun importCsv(api: ApiClient, key: String, file: File) {
        val rows = parseCsv(file.readText())
        if (rows.isEmpty()) error("CSV 无数据行")
        val headers = rows.first().keys
        fun col(vararg names: String): String? =
            names.firstOrNull { n -> headers.any { it.trim().equals(n, ignoreCase = true) } }

        val titleCol = col("Title", "标题") ?: error("CSV 缺标题列(Title/标题)")
        val statusCol = col("Status", "状态")
        val labelsCol = col("Labels", "标签")
        val descCol = col("Description", "描述")
        val typeCol = col("Type", "类型")

        val project = api.json("GET", "/api/projects/$key")
        val statusByName = mutableMapOf<String, String>()
        project["statuses"].forEach { st -> statusByName[st["name"].asString()] = st["statusId"].asString() }
        val knownAttrs = mutableSetOf<String>()
        api.json("GET", "/api/attributes").forEach { a -> knownAttrs += a["name"].asString() }

        var created = 0
        rows.forEach { row ->
            val title = row[titleCol]?.trim().orEmpty().ifEmpty { return@forEach }
            val requirements = (row[labelsCol] ?: "")
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
            val body = api.json(
                "POST",
                "/api/projects/$key/items",
                mapOf(
                    "title" to title,
                    "description" to row[descCol]?.trim()?.takeIf { d -> d.isNotEmpty() },
                    "type" to (row[typeCol]?.trim()?.takeIf { it.isNotEmpty() } ?: "task"),
                    "requirements" to requirements,
                ),
            )
            statusCol?.let { c ->
                row[c]?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                    statusByName[name]?.let { sid ->
                        api.json("PATCH", "/api/items/${body["itemId"].asString()}", mapOf("statusItemId" to sid))
                    }
                }
            }
            created++
            if (created % 100 == 0) println("已导入 $created/${rows.size}")
        }
        println("CSV 导入完成:$created 个 item → 项目 $key")
    }

    /** 解析 CSV 文本 → 行×列(带表头映射);支持引号包裹、双引号转义、字段内逗号与换行。 */
    fun parseCsv(text: String): List<Map<String, String>> {
        val rows = mutableListOf<List<String>>()
        var field = StringBuilder()
        var record = mutableListOf<String>()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' -> inQuotes = true
                c == ',' -> { record += field.toString(); field = StringBuilder() }
                c == '\r' -> { /* CRLF 归一 */ }
                c == '\n' -> {
                    record += field.toString(); field = StringBuilder()
                    if (record.any { it.isNotEmpty() }) rows += record
                    record = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || record.isNotEmpty()) {
            record += field.toString()
            if (record.any { it.isNotEmpty() }) rows += record
        }
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim() }
        return rows.drop(1).map { row -> header.indices.associate { h -> header[h] to row.getOrElse(h) { "" } } }
    }
}
