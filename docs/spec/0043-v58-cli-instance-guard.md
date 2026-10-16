# 0043 — V20 / YPJ-58:yz CLI 实例定位防护

> 需求源:YPJ-58(2026-10-16 建票,谜底取证在票描述)。背景事件:session.json 指向 8080(test 库)时 yz 404「项目不存在:YPJ」,耗数小时取证;更早的「实例漂移」误判同根因。

## 一、需求

### 背景
`yz login` 不带 `--server` 默认 `http://localhost:8080`(test 实例);本机 PM 日常实例在 **8081**(default 库)。指错实例时命令照常执行,直到项目级命令 404「项目不存在:YPJ」——错误信息不指向实例,难定位。

### 范围
1. **login 当场探测**:`yz login` 成功后拉 `GET /api/projects`,回显该实例的项目 key 列表;**不含 YPJ 时打显著警告**(提示 PM 实例应 `--server http://localhost:8081` 重登)。错误从「几小时后 404 谜团」提前到「login 当场可见」。
2. **错误消息带 server**:`ApiClient.json()` 非 2xx 抛错时附 `(server=...)`,404 现场即可见 session 指向。
3. usage 文案:login 帮助注明默认 8080 / PM 实例 8081。

### 非目标
- 不改默认端口(8080 是合法实例)。
- 不加实例指纹/健康检查/心跳。
- 不为 cli 建测试基建(零测试模块保持;验证走双实例手动冒烟)。

### 验收(票面)
- [ ] yz 再指错实例时,login 当场可见警告 / 出错现场可见 server
- [ ] 既有命令行为不变

## 二、详细设计

### 1. `Main.kt` login 分支
```kotlin
"login null", "login " -> {
    val server = (flags["server"] ?: "http://localhost:8080").trimEnd('/')
    val user = flags["u"] ?: error("缺 -u")
    val pass = flags["p"] ?: error("缺 -p")
    val client = ApiClient.login(server, user, pass)
    println("已登录:$server")
    // YPJ-58 实例定位防护:回显项目 key;无 YPJ 打警告(test 实例误登当场可见)
    val keys = client.json("GET", "/api/projects").map { it["key"].asString() }
    println("实例项目:${keys.joinToString("、")}")
    if ("YPJ" !in keys) println("⚠ 该实例没有 YPJ 项目;若要操作 PM 数据,重登:yz login --server http://localhost:8081 -u ... -p ...")
}
```
否决:把探测埋进 ApiClient.login——打印归 Main(展示层),client 保持薄。

### 2. `ApiClient.kt` json() 错误消息
`error("[$method $path] ${response.statusCode()}:$msg(server=${requireSession().server})")`——一行改动。

### 3. usage 文案
login 行改为:`login [-u 用户名] [-p 密码] [--server URL]   登录(默认 8080;PM 日常实例用 http://localhost:8081)`。

### 4. 验证(无测试基建,手动冒烟)
1. `gradle :cli:installDist` 或直接 jar;login 8081 → 「已登录 + 实例项目:YPJ、…」,无警告。
2. login 8080(test 实例)→ 警告行出现;session.json 被覆盖为 8080——冒烟后**重登回 8081**(收尾清单项)。
3. 指错实例下跑 `yz items move YPJ-57 "Done"` 类命令 → 错误消息含 `(server=http://localhost:8080)`。
4. `gradle build` 绿(单测不受影响)。

## 三、手工测试用例(用户 build/debug 执行)

1. `yz login -u <pm> -p <密码>`(8081)→ 已登录 + 「实例项目:YPJ、…」,无警告。
2. `yz login --server http://localhost:8080 ...` → ⚠ 警告行;随后库确认 session 落 8080。
3. 8080 会话下 `yz attrs list` 等全局命令正常;`yz` 项目级命令 404 消息尾带 `(server=http://localhost:8080)`。
4. 重登 8081,`yz log` 照旧。

## As-built(2026-10-16,PR #113 合入)

- **按票交付**:login 回显「实例项目:key 列表」+ 无 YPJ ⚠ 警告;json() 非 2xx 尾附 `(server=…)`;usage 注明 PM 实例 8081。偏差:无(第 4 项冒烟步骤「重登回 8081」由用户执行完成,session 现指 8081)。
- **实现期记坑**:Jackson3 `JsonNode` 虽实现 `Iterable<JsonNode>`,但自带 `map(...)` 成员遮蔽 Kotlin stdlib `Iterable.map`(返回非 List,编译期才炸,症状是下游 `joinToString` 全候选不匹配)→ 改 `values().map { … }`(jackson3 新 API,`Collection<JsonNode>`)。排查法:提最小 Probe.kt 复现 + `List<String>` 显式标注让真实类型现形。
- 验证:`gradle :cli:build` 绿;双实例冒烟(用户执行):8080 = 登录成功 + 46 个 test 库 key + ⚠ 警告;8081 = 含 YPJ 无警告。PR #113。
- 顺带实证:8081 上 CHE*/SMK* 13 个垃圾项目 = YPJ-59(default 库清理)的活证据。
