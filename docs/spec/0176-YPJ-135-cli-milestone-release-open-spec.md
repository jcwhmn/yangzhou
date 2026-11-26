# spec 0176 — YPJ-135 yz CLI:milestone/release list + items open

> 来源:YPJ-135 票面(spec 0094 §4 中低优缺口拆票);CLI 只读考古 + CLI↔web 跳转。

## 目的与范围

yz CLI 增三命令,均为只读/导航,零写操作:

1. `milestone list <KEY> [--json]` — 里程碑列表(与 sprints list 同构表格);
2. `release list <KEY> [--json]` — 发布列表(同构);
3. `items open <KEY-N|itemId> [--web URL]` — 拼 web 详情 URL 打开浏览器(headless 兜底打印 URL)。

非目标(否决/不做):

- milestone/release 的 create/move 写命令 — web 已覆盖主路径(spec 0094 §4 原判,不扩);
- `items open` 开 project/milestone/release 页 — 票面只要求 item 详情;
- milestone/release 走 `groups?type=` 接口 — 二者是独立实体(MilestoneController/ReleaseController 独立端点),与 sprint(group level-1)不同族,不套 level1TypeKey;
- dashboard/metrics/view/notification — spec 0094 §4 已判不做。

## 现状事实(调研 2026-11-28)

- API 已有只读端点:
  - `GET /api/projects/{key}/milestones` → `MilestoneDto(milestoneId, name, description, owner, status, targetDate, overdue, dueSoon, daysToTarget, gateDone, gateTotal)`;status ∈ planned/in_progress/completed/cancelled;
  - `GET /api/projects/{key}/releases` → `ReleaseDto(releaseId, name, version, stage, status, targetDate, releasedDate, createdAt, overdue, doneCount, totalCount)`;stage ∈ alpha/beta/rc/ga。
- web 详情路由:`/p/{KEY}/i/{itemId}`(`web/app/p/[key]/i/[itemId]/page.tsx`)。
- CLI 已有 `resolveItemId(api, ref)`(KEY-N→itemId,UUID 直过)、sprints list 表格范式(`%-36s %-16s %-10s`,中文表头,空列表 `(无 sprint)`)、`--json` 走 `printRaw`。
- 会话只存 API server URL(8080=prod / 8081=dev;AGENTS.md profile 约定 3000+8080、3001+8081 成对)。

## 详细设计(代码级修改计划)

只改 CLI 模块(纯瘦客户端,API/web 零改动):`backend/cli/src/main/kotlin/yangzhou/cli/Main.kt` + 测试两文件。

### 1. usage 帮助(Main.kt usage())

追加三行(sprints list 风格):

```text
milestone list <KEY> [--json]                里程碑列表(id/名称/状态/目标日/剩超)
release list <KEY> [--json]                  发布列表(id/名称/版本/阶段/状态/目标日/进度)
items open <KEY-N|itemId> [--web URL]        浏览器打开 web 详情页(端口 8080→3000/8081→3001 自动推导)
```

### 2. `milestone list`(对齐 sprints list 分支)

```kotlin
"milestone list" -> {
    val key = rest.getOrNull(0) ?: error("缺少项目 KEY")
    val body = api.json("GET", "/api/projects/$key/milestones")
    if (flags.containsKey("json")) return printRaw(body.toString())
    if (body.size() == 0) return println("(无里程碑)")
    println("milestoneId                          名称             状态         目标日        剩/超")
    body.forEach { m ->
        val td = if (m["targetDate"].isNull || m["targetDate"].isMissingNode) "—" else m["targetDate"].asString()
        val days = if (m["daysToTarget"].isNull || m["daysToTarget"].isMissingNode) "—" else {
            val n = m["daysToTarget"].asInt()
            when { n > 0 -> "剩 $n 天"; n == 0 -> "今日"; else -> "超 ${-n} 天" }
        }
        println("%-36s %-16s %-12s %-12s %s".format(m["milestoneId"].asString(), m["name"].asString(), m["status"].asString(), td, days))
    }
}
```

剩/超列复用徽标同源的 `daysToTarget`(targetDate 空即 null → "—"),不本地算日期。

### 3. `release list`(同构)

```kotlin
"release list" -> {
    val key = rest.getOrNull(0) ?: error("缺少项目 KEY")
    val body = api.json("GET", "/api/projects/$key/releases")
    if (flags.containsKey("json")) return printRaw(body.toString())
    if (body.size() == 0) return println("(无发布)")
    println("releaseId                            名称           版本      阶段      状态         目标日        进度")
    body.forEach { r ->
        val td = if (r["targetDate"].isNull || r["targetDate"].isMissingNode) "—" else r["targetDate"].asString()
        println(
            "%-36s %-14s %-9s %-9s %-12s %-12s %s/%s".format(
                r["releaseId"].asString(), r["name"].asString(),
                r["version"]?.takeUnless { it.isNull }?.asString() ?: "—",
                r["stage"]?.takeUnless { it.isNull }?.asString() ?: "—",
                r["status"].asString(), td,
                r["doneCount"].asInt(), r["totalCount"].asInt(),
            ),
        )
    }
}
```

进度列 `doneCount/totalCount`(US-R8 口径:仅供参考,表头只写「进度」不标完成率语义)。

### 4. `items open`

```kotlin
"items open" -> {
    val ref = rest.getOrNull(0) ?: error("缺少 item ID(支持 YPJ-58 或 UUID)")
    val itemId = resolveItemId(api, ref)
    // KEY-N 直接取前段作 key;UUID 反查 number(items show 同构的 GET /api/items/{id})
    val key = if (ref.contains('-') && !ref.contains(' ')) ref.substringBefore('-')
              else api.json("GET", "/api/items/$itemId")["number"].asString().substringBefore('-')
    val url = "${webBase(api.requireSession().server, flags)}/p/$key/i/$itemId"
    val opened = runCatching {
        val d = java.awt.Desktop
        if (d.isDesktopSupported() && d.getDesktop().isSupported(d.Action.BROWSE)) { d.getDesktop().browse(URI(url)); true } else false
    }.getOrDefault(false)
    println(if (opened) "已打开浏览器:$url" else "无法自动打开浏览器,请手动打开:$url")
}
```

### 5. 新 helper `webBase`(Main.kt,resolveItemId 附近)

```kotlin
/** web 详情基址:--web 优先;否则按 profile 端口约定推导(8080→3000, 8081→3001);推导不出报错(YPJ-135)。 */
private fun webBase(server: String, flags: Map<String, String>): String {
    flags["web"]?.let { return it.trimEnd('/') }
    val uri = runCatching { URI(server) }.getOrNull() ?: error("API 地址无法解析:$server(用 --web <URL> 指定 web 地址)")
    val webPort = mapOf(8080 to 3000, 8081 to 3001)[uri.port]
        ?: error("无法从 API 地址推导 web 地址($server);用 --web <URL> 显式指定")
    return "http://${uri.host ?: "localhost"}:$webPort"
}
```

- **设计决策(请复核)**:web 基址从 API server 端口按 profile 约定推导(8080↔3000、8081↔3001),不存会话、不加环境变量。理由:该配对是项目宪法级稳定事实(AGENTS.md 环境红线),推导零配置;8082(test)无 web,不在映射内 → fail-fast 报错引导 `--web`,与 CLI 现有 error 风格一致。
- `Desktop.browse` 为 JDK 标准库跨平台方案;headless/异常(runCatching 兜底)不打断命令,打印 URL 由人接手——CLI 场景 URL 本身就是一等输出。

### 6. 测试

- **SmokeTest 锚点追加**(usage 完整性,YPJ-115 惯例):`"milestone list", "release list", "items open"`。
- **新增 `backend/cli/src/test/kotlin/yangzhou/cli/WebUrlTest.kt`**(纯函数单测,BoardorderMovesTest 风格):
  - 8080 → `http://localhost:3000`;8081 → `http://localhost:3001`;
  - 未知端口(如 9090)→ error 含「--web」;
  - `--web` 覆盖优先于推导;`--web` 值尾斜杠去除;
  - 无端口 URL → error 引导。
- CLI 网络路径(实际 list/open)不自动测——现有 CLI 测试即零网络惯例(SmokeTest 注释:防加命令忘 usage);真实数据正确性归手工用例。

### 否决备选

- **web 基址存会话文件**(session.json 加 webBase 字段):旧会话无字段需迁移逻辑,每次 login 多一步;profile 约定稳定,推导即可 ✗
- **环境变量 YZ_WEB_URL**:多一个隐式配置面,排查成本高于 `--web` 显式 flag ✗
- **Runtime.exec("cmd /c start"/"open"/"xdg-open") 平台分支**:java.awt.Desktop 是 JDK 标准库统一入口,自写分支无增量价值 ✗
- **open 打印后仍尝试 Desktop.browse 的返回码区分**:browse 成败 UI 差异(浏览器窗口)不可进程级验证,统一「已打开/请手动」二态文案即可 ✗

## 手工测试用例(落档 docs/testing/manual-test-cases.md → M-135)

**前置**:dev 容器(3001+8081)运行含本票代码的构建(容器 Image sha 对比 `docker images` 最新);yz session 指 http://localhost:8081。

| # | 步骤 | 预期 |
|---|---|---|
| M-135-1 | `yz milestone list YPJ` | 表格行与 web 里程碑页一致(id/名称/状态/目标日/剩超);目标日空的行显示 — |
| M-135-2 | `yz release list YPJ` | 表格行与 web 发布页一致(版本/阶段/状态/目标日/进度 n/m) |
| M-135-3 | `yz milestone list YPJ --json` | 原始 JSON 数组(printRaw) |
| M-135-4 | `yz items open YPJ-135` | 浏览器自动打开 `http://localhost:3001/p/YPJ/i/<uuid>`,页面为该票详情 |
| M-135-5 | 无里程碑/发布的空项目 `yz milestone list <KEY>` / `yz release list <KEY>` | `(无里程碑)` / `(无发布)`,退出 0 |
| M-135-6 | `yz items open <UUID形式itemId>` | 同 M-135-4(UUID 反查 key 路径) |
| M-135-7 | `yz items open YPJ-135 --web http://localhost:3001` | 显式覆盖仍打开正确页面 |

## 验收口径

- 后端验证:`gradle :cli:build`(含 SmokeTest/WebUrlTest)绿;CLI 无 integrationTest 面。
- 用户验收 = M-135 用例逐条勾;过 → PR。

## As-built

(完工后补)
