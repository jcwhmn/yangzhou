package yangzhou.cli

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream

/** CLI 入口:yz <noun> <verb> [args] [--flags]。瘦壳——逻辑全在 API,这里只有编排与展示。 */
fun main(args: Array<String>) {
    // Windows 控制台 GBK 兜底:强制 UTF-8 输出(中文判定行不乱码)
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8"))
    System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, "UTF-8"))

    if (args.isEmpty()) return usage()
    try {
        run(args.toList())
    } catch (e: Exception) {
        System.err.println("错误:${e.message ?: e::class.simpleName}")
        kotlin.system.exitProcess(1)
    }
}

private fun usage() {
    println(
        """
        yz — yangzhou 命令行
        用法:yz <命令> [参数] [选项]

          login [-u 用户名] [-p 密码] [--server URL]      登录(默认 8080;全新服务器自动 bootstrap)
          attrs create <属性> [--kind skill|label] [--leveled]   建词表属性
          members add <显示名>                         建虚拟成员(无凭据,不登录)
          members list [--json]                        成员目录(含我与虚拟)
          members rm <成员名>                          删虚拟成员(登录账号不可删)
          members set <成员名> <属性> [1-4]            给成员自评能力(不带数字=未评级)
          teams add <组名> / teams list                建组/列组(纯分组,视图过滤)
          attrs list [--json]                        词表列表
          projects create <KEY> [名称]                 建项目(自动带三态 workflow)
          projects list [--json]                       项目列表
          items create <KEY> <标题> [--req 属性[>=N]]... [--json]   建 item(需求可分级)
          items list <KEY> [--json]                    项目 item 列表
          items move <ITEM_ID> <状态名>                改状态(经 workflow 校验)
          caps set <属性> [1-4]                        自评能力(不带数字=未评级)
          caps rm <属性>                               移除能力
          caps list [--json]                           能力清单
          feasibility <KEY> [--item <ID>] [--json]     可行性/差距分析
          candidates <KEY-N|itemId> [--json]          候选建议(谁来做:排序+理由)
          assign <KEY-N|itemId> <成员名|--clear>      指派/取消(引擎建议,人拍板)
          export <KEY> [--csv] [--file <路径>]          导出(JSON 全保真/CSV 扁平)
          sync-linear <KEY> <linear.csv>                Linear 导出 CSV 幂等同步(首跑导入,重跑更新)
          import <KEY> <文件>                           导入(JSON 按扩展名或 .csv;Linear CSV 可直接灌)

        会话文件:${ApiClient.sessionFile().absolutePath}
        """.trimIndent(),
    )
}

private fun run(args: List<String>) {
    val flags = mutableMapOf<String, String>()
    val positional = mutableListOf<String>()
    val reqs = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        when {
            a == "--req" -> { reqs += args[i + 1]; i += 2 }
            a == "--json" -> { flags["json"] = ""; i += 1 } // 无值旗标,可放任意位置
            a.startsWith("-") -> { flags[a.trimStart('-')] = args.getOrNull(i + 1) ?: ""; i += 2 }
            else -> { positional += a; i += 1 }
        }
    }
    val noun = positional.getOrNull(0)
    val verb = positional.getOrNull(1)
    val rest = positional.drop(2)

    val api = ApiClient.create()
    if (noun == "feasibility") {
        return feasibility(api, positional.getOrNull(1), flags)
    }
    if (noun == "members") {
        return when (verb) {
            "add" -> {
                val displayName = rest.getOrNull(0) ?: error("缺少显示名")
                val body = api.json("POST", "/api/members", mapOf("displayName" to displayName.trim()))
                println("已建虚拟成员:${body["displayName"].asString()}(无凭据,不登录)")
            }
            "rm" -> {
                val name = rest.getOrNull(0) ?: error("缺少成员名")
                val id = memberIdByDisplayName(api, name) ?: error("成员不存在:$name")
                api.json("DELETE", "/api/members/$id")
                println("已删除:$name")
            }
            "list" -> {
                val body = api.json("GET", "/api/members")
                if (flags.containsKey("json")) return printRaw(body.toString())
                println("成员     性质     用户名")
                body.forEach {
                    println("%-8s %-8s %s".format(
                        it["displayName"].asString(),
                        if (it["virtual"].asBoolean()) "虚拟" else "登录",
                        it["username"]?.let { u -> if (u.isNull) "-" else u.asString() } ?: "-",
                    ))
                }
            }
            "set" -> {
                val name = rest.getOrNull(0) ?: error("缺少成员名")
                val attr = rest.getOrNull(1) ?: error("缺少属性名")
                val level = rest.getOrNull(2)?.let {
                    it.toIntOrNull()?.takeIf { l -> l in 1..4 } ?: error("等级只能是 1-4 或不填(未评级)")
                }
                val memberId = memberIdByDisplayName(api, name) ?: error("成员不存在:$name")
                val body = api.json("PUT", "/api/members/$memberId/capabilities", mapOf("attribute" to attr, "level" to level))
                val lvl = body["level"]
                println("已设置:$name · $attr = ${if (lvl.isNull) "未评级" else lvl.asInt()}")
            }
            else -> usage()
        }
    }
    if (noun == "teams") {
        return when (verb) {
            "add" -> {
                val name = rest.getOrNull(0) ?: error("缺少组名")
                val body = api.json("POST", "/api/teams", mapOf("name" to name.trim()))
                println("已建组:${body["name"].asString()}")
            }
            "list" -> {
                val body = api.json("GET", "/api/teams")
                if (flags.containsKey("json")) return printRaw(body.toString())
                if (body.size() == 0) return println("(无分组)")
                body.forEach { team ->
                    println("${team["name"].asString()}:${team["members"].joinToString("、") { m -> m["displayName"].asString() }.ifEmpty { "(空)" }}")
                }
            }
            else -> usage()
        }
    }
    if (noun == "candidates" || noun == "assign") {
        val ref = positional.getOrNull(1) ?: error("缺少 <KEY-N|itemId>(如 CHE-1)")
        val itemId = resolveItemId(api, ref)
        if (noun == "candidates") {
            val body = api.json("GET", "/api/items/$itemId/candidates")
            if (flags.containsKey("json")) return printRaw(body.toString())
            if (body.size() == 0) return println("(无候选——先建成员)")
            println("名次  成员     性质     信号     缺门  差距")
            body.forEach {
                println(
                    "%-5s %-8s %-8s %-8s %-5s %s".format(
                        "#${it["rank"].asInt()}",
                        it["displayName"].asString(),
                        if (it["virtual"].asBoolean()) "虚拟" else "我",
                        when (it["signal"].asString()) { "GREEN" -> "[绿]"; "YELLOW" -> "[黄]"; else -> "[红]" },
                        it["missingCount"].asInt(),
                        "${it["totalDelta"].asInt()}级",
                    ),
                )
                it["verdicts"].forEach { v ->
                    val attr = v["attribute"].asString()
                    val line = when (v["kind"].asString()) {
                        "satisfied" -> "      ✓ 满足($attr)"
                        "surplus" -> "      ✓ 有余($attr)"
                        "gap" -> "      △ 差 ${v["delta"].asInt()} 级($attr:需≥${v["required"].asInt()},有 ${v["actual"].asInt()})"
                        "unrated" -> "      △ 有但未评级($attr,需≥${v["required"].asInt()})——差距未知"
                        else -> "      ✗ 缺能力($attr)"
                    }
                    println(line)
                }
            }
            return
        }
        // assign
        val name = flags["clear"]?.takeIf { it.isNotEmpty() } ?: positional.getOrNull(2)
        val assigneeId: String? = if (name == null || name == "--") null else {
            val members = api.json("GET", "/api/members")
            var found: String? = null
            members.forEach { m -> if (m["displayName"].asString() == name) found = m["memberId"].asString() }
            found ?: error("成员不存在:$name(可用:${members.joinToString("/") { m -> m["displayName"].asString() }})")
        }
        val body = api.json("PUT", "/api/items/$itemId/assignee", mapOf("assigneeItemId" to assigneeId))
        val who = body["assignee"]
        println(if (who.isNull) "已取消指派:${body["number"].asString()}" else "已指派:${body["number"].asString()}" + " → " + who.asString())
        return
    }
    if (noun == "sync-linear") {
        val key = positional.getOrNull(1) ?: error("缺少项目 KEY")
        val path = positional.getOrNull(2) ?: flags["file"] ?: error("缺少 Linear 导出 CSV 路径")
        yangzhou.cli.LinearSync.sync(api, key, java.io.File(path))
        return
    }
    if (noun == "export") {
        val key = positional.getOrNull(1) ?: error("缺少项目 KEY")
        val csv = flags.containsKey("csv")
        val file = java.io.File(flags["file"] ?: "$key-items.${if (csv) "csv" else "json"}")
        return if (csv) yangzhou.cli.ExportImport.exportCsv(api, key, file) else yangzhou.cli.ExportImport.exportJson(api, key, file)
    }
    if (noun == "import") {
        val key = positional.getOrNull(1) ?: error("缺少项目 KEY")
        val path = positional.getOrNull(2) ?: flags["file"] ?: error("缺少文件路径")
        val file = java.io.File(path)
        return if (file.extension.equals("csv", ignoreCase = true)) {
            yangzhou.cli.ExportImport.importCsv(api, key, file)
        } else {
            yangzhou.cli.ExportImport.importJson(api, key, file)
        }
    }
    when ("$noun $verb") {
        "login null", "login " -> {
            val server = (flags["server"] ?: "http://localhost:8080").trimEnd('/')
            val user = flags["u"] ?: error("缺少 -u")
            val pass = flags["p"] ?: error("缺少 -p")
            ApiClient.login(server, user, pass).let { println("已登录:$server") }
        }

        "attrs create" -> {
            val name = rest.getOrNull(0) ?: error("缺少属性名")
            val kind = flags["kind"] ?: "skill"
            val leveled = flags.containsKey("leveled") || flags["kind"] == null || flags["kind"] == "skill"
            val body = api.json("POST", "/api/attributes", mapOf("name" to name, "kind" to kind, "leveled" to leveled))
            printJson(body, flags)
            println("属性 ${body["name"].asString()}(${body["kind"].asString()}${if (body["leveled"].asBoolean()) "/分级" else ""})已创建")
        }

        "attrs list" -> {
            val body = api.json("GET", "/api/attributes")
            if (flags.containsKey("json")) return printRaw(body.toString())
            if (body.size() == 0) return println("(无属性)")
            println("属性     类型    分级")
            body.forEach {
                println("%-8s %-7s %s".format(it["name"].asString(), it["kind"].asString(), if (it["leveled"].asBoolean()) "是" else "否"))
            }
        }

        "projects create" -> {
            val key = rest.getOrNull(0) ?: error("缺少 KEY")
            val body = api.json("POST", "/api/projects", mapOf<String, Any>("key" to key, "name" to (rest.getOrNull(1) ?: key)))
            printJson(body, flags)
            println("项目 ${body["key"].asString()} 已创建")
        }

        "projects list" -> {
            val body = api.json("GET", "/api/projects")
            if (flags.containsKey("json")) return printRaw(body.toString())
            if (body.size() == 0) return println("(无项目)")
            println("KEY     名称     状态数  归档")
            body.forEach {
                println("%-7s %-8s %-7s %s".format(it["key"].asString(), it["name"].asString(), it["statuses"].size(), if (it["archived"].asBoolean()) "是" else "否"))
            }
        }

        "items create" -> {
            val key = rest.getOrNull(0) ?: error("缺少项目 KEY")
            val title = rest.getOrNull(1) ?: error("缺少标题")
            val requirements = reqs.map { raw ->
                val m = Regex("^(.+?)(?:>=(\\d))?$").find(raw.trim()) ?: error("需求格式:属性 或 属性>=N,得到:$raw")
                val (attr, level) = m.destructured
                if (level.isEmpty()) mapOf<String, Any?>("attribute" to attr, "minLevel" to null)
                else mapOf<String, Any?>("attribute" to attr, "minLevel" to level.toInt())
            }
            val body = api.json(
                "POST",
                "/api/projects/$key/items",
                mapOf("title" to title, "requirements" to requirements),
            )
            printJson(body, flags)
            println("已创建 ${body["number"].asString()}:${body["title"].asString()}")
        }

        "items list" -> {
            val key = rest.getOrNull(0) ?: error("缺少项目 KEY")
            val body = api.json("GET", "/api/projects/$key/items")
            if (flags.containsKey("json")) return printRaw(body.toString())
            if (body.size() == 0) return println("(无 item)")
            println("编号    状态          标题                    需求")
            body.forEach { item ->
                val reqsText = item["requirements"].joinToString(",") {
                    val lvl = it["minLevel"]
                    if (lvl.isNull) it["attribute"].asString() else "${it["attribute"].asString()}≥${lvl.asInt()}"
                }
                println("%-7s %-13s %-23s %s".format(item["number"].asString(), item["status"].asString(), item["title"].asString(), reqsText))
            }
        }

        "items move" -> {
            val itemId = rest.getOrNull(0) ?: error("缺少 item ID")
            val statusName = rest.getOrNull(1) ?: error("缺少目标状态名")
            // 状态名 → statusId:从 item 所属项目取状态表
            val item = api.json("GET", "/api/items/$itemId")
            val project = api.json("GET", "/api/projects/${item["number"].asString().substringBefore("-")}")
            val statusId = project["statuses"]
                .firstOrNull { it["name"].asString().equals(statusName, ignoreCase = true) }
                ?.get("statusId")?.asString()
                ?: error("项目无此状态:$statusName(可用:${project["statuses"].joinToString("/") { s -> s["name"].asString() }})")
            val body = api.json("PATCH", "/api/items/$itemId", mapOf("statusItemId" to statusId))
            println("已迁移:${body["number"].asString()} → ${body["status"].asString()}")
        }

        "caps set" -> {
            val attr = rest.getOrNull(0) ?: error("缺少属性名")
            val level = rest.getOrNull(1)?.let {
                it.toIntOrNull()?.takeIf { l -> l in 1..4 } ?: error("等级只能是 1-4 或不填(未评级)")
            }
            val body = api.json("PUT", "/api/capabilities", mapOf("attribute" to attr, "level" to level))
            val lvl = body["level"]
            println("已设置:$attr = ${if (lvl.isNull) "未评级" else lvl.asInt()}")
        }

        "caps rm" -> {
            val attr = rest.getOrNull(0) ?: error("缺少属性名")
            api.json("DELETE", "/api/capabilities/$attr")
            println("已移除:$attr")
        }

        "caps list" -> {
            val body = api.json("GET", "/api/capabilities")
            if (flags.containsKey("json")) return printRaw(body.toString())
            if (body.size() == 0) return println("(无能力)")
            println("属性     等级")
            body.forEach {
                val lvl = it["level"]
                println("%-8s %s".format(it["attribute"].asString(), if (lvl.isNull) "未评级" else lvl.asInt()))
            }
        }

        else -> usage()
    }
}

/** 成员名 → memberId(显示名精确匹配)。 */
private fun memberIdByDisplayName(api: ApiClient, name: String): String? {
    var found: String? = null
    api.json("GET", "/api/members").forEach { m ->
        if (m["displayName"].asString() == name) found = m["memberId"].asString()
    }
    return found
}

/** KEY-N(如 CHE-1)→ itemId;直接给 UUID 也认。 */
private fun resolveItemId(api: ApiClient, ref: String): String {
    if (ref.contains('-') && !ref.contains(' ')) {
        val key = ref.substringBefore('-')
        runCatching {
            val items = api.json("GET", "/api/projects/$key/items")
            items.forEach { if (it["number"].asString() == ref) return it["itemId"].asString() }
        }
    }
    error("找不到 item:$ref")
}

private fun feasibility(api: ApiClient, key: String?, flags: Map<String, String>) {
    if (key == null) error("缺少项目 KEY")
    val itemId = flags["item"]
    if (itemId != null) {
        val body = api.json("GET", "/api/items/$itemId/feasibility")
        if (flags.containsKey("json")) return printRaw(body.toString())
        return printItemFeasibility(body)
    }
    val body = api.json("GET", "/api/projects/$key/feasibility")
    if (flags.containsKey("json")) return printRaw(body.toString())
    val signalZh = mapOf("GREEN" to "✔ 全满足", "YELLOW" to "△ 有差距", "RED" to "✗ 有缺门")
    println("项目 $key → ${signalZh[body["signal"].asString()]}(缺门${body["missingCount"].asInt()} · 总差距${body["totalDelta"].asInt()}级)")
    body["items"].forEach { printItemFeasibility(it) }
}

private fun printItemFeasibility(item: tools.jackson.databind.JsonNode) {
    val signal = when (item["signal"].asString()) {
        "GREEN" -> "[绿]"
        "YELLOW" -> "[黄]"
        else -> "[红]"
    }
    println("%-8s %-24s %s".format(item["number"].asString(), item["title"].asString(), signal))
    item["verdicts"].forEach { v ->
        val attr = v["attribute"].asString()
        val line = when (v["kind"].asString()) {
            "satisfied" -> "    ✓ 满足($attr)"
            "surplus" -> "    ✓ 有余($attr)"
            "gap" -> "    △ 差 ${v["delta"].asInt()} 级($attr:需≥${v["required"].asInt()},有 ${v["actual"].asInt()})"
            "unrated" -> "    △ 有但未评级($attr,需≥${v["required"].asInt()})——差距未知"
            else -> "    ✗ 缺能力($attr)"
        }
        println(line)
    }
}

private fun printJson(body: tools.jackson.databind.JsonNode, flags: Map<String, String>) {
    if (flags.containsKey("json")) println(body.toString())
}

private fun printRaw(json: String) = println(json)
