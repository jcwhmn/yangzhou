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
        System.err.println("错误:${e.message}")
        kotlin.system.exitProcess(1)
    }
}

private fun usage() {
    println(
        """
        yz — yangzhou 命令行
        用法:yz <命令> [参数] [选项]

          login --server <URL> -u <用户名> -p <密码>   登录(全新服务器自动 bootstrap,保存会话)
          attrs create <属性> [--kind skill|label] [--leveled]   建词表属性
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
    when ("$noun $verb") {
        "login null", "login " -> {
            val server = flags["server"] ?: error("缺少 --server")
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
