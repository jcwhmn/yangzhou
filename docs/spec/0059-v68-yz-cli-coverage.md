# 0059 — V76:yz CLI 覆盖缺口(YPJ-68)

> 状态:**草案(等用户过目后编码)**
> 票:YPJ-68(V22);含本会话实测撞到的 CLI 登录回退与一个后端错误映射小修

## 需求

### 背景

批量/自动化场景(如脚本建票、外部系统同步)被迫直接走 REST——CLI 缺 `--desc/--sprint` 与 sprint 挂载命令(票标题原文)。另有两个本会话实测撞到的工具链痛点顺手修:

1. **CLI 登录被存量服务器卡死**:`ApiClient.login` 先打 bootstrap、409 才转 login;旧版后端 bootstrap 带 8 位下限,6 位密码回 400(非 409)→ login 永不执行(YPJ 密码 `secret` 正中此坑)。
2. **`GET /api/items/YPJ-40`(number 打 UUID 端点)500**:路径参数 UUID 转换失败抛 `MethodArgumentTypeMismatchException`,GlobalExceptionHandler 无对应 handler → 兆成 INTERNAL,应为 400。

### 范围

1. `items create <KEY> <标题> [--desc 文本] [--sprint <名称|UUID>]`——`CreateItemRequest` 本就带 `description`/`sprintId`,CLI 只补 flags 透传;`--sprint` 给名称时查项目 sprints 精确匹配,不唯一即报错并列候选。
2. 新命令 `sprints list <KEY> [--json]`——列 sprint(id/名称/状态/起止),供 `--sprint` 引用。
3. CLI 登录回退:bootstrap 回 **400 也转 login**(login 报错更有信息量;新旧服务器都通)。
4. 后端一行修:GlobalExceptionHandler 加 `MethodArgumentTypeMismatchException` → 400「路径参数类型不合法」。

### 非目标

- `items create` 不加 `--type/--parent/--status` 等(create 后 Web 改,按需再加);
- 不做 sprint 退出/move 命令(挂了就是挂了,退回走 Web);
- CLI 不引入交互式 TUI/配置文件;
- 不给 CLI 写自动化测试套件(cli 模块现无测试目录,改动是 IO 编排,见「测试」节)。

## 详细设计(代码级修改计划)

| 文件 | 改动 |
|---|---|
| `backend/cli/src/main/kotlin/yangzhou/cli/Main.kt` | ① usage 两行(items create 补 flags、新增 sprints list);② `"items create"` 分支:`flags["desc"]` 进 body;`flags["sprint"]` 经 `resolveSprintId(api, key, 值)` 解析(UUID 正则直过;否则 GET sprints 按名精确匹配,0/多匹配 error 列出名称+状态);③ 新分支 `"sprints list"`:GET `/api/projects/{key}/sprints`,表格(截断的 sprintId / 名称 / 状态 / 起止),`--json` 原样 |
| `backend/cli/src/main/kotlin/yangzhou/cli/ApiClient.kt` | `login`:`.let { r -> if (r.statusCode() == 409 \|\| r.statusCode() == 400) post("/api/auth/login") else r }`(400 也回退,注释注明存量服务器 bootstrap 8 位校验场景) |
| `backend/api/src/main/kotlin/yangzhou/api/config/GlobalExceptionHandler.kt` | 加 `@ExceptionHandler(MethodArgumentTypeMismatchException::class)` → `error(BAD_REQUEST, "BAD_REQUEST", "路径参数类型不合法", req)` |
| `backend/script/api.http` | 负例区补:`GET /api/items/YPJ-1`(Bearer 在)→ 断言 400 |

### 否决的备选

- **CLI 自动化测试**(launch Main 断 stdout):要起服务器或抽 seam,成本远超本票收益,手工用例替代;
- **`--sprint` 只认 UUID**:对人不友好,脚本也得先查 id,失去 CLI 意义;
- **sprints 子命令做成 `items sprint ...` 多动词**:与 `items move` 风格不符,单列 `sprints list` 直白。

## 测试

- 后端:`GlobalExceptionHandlerTest` 加一例(GET 路径传非 UUID → 400,断言 code=BAD_REQUEST);
- CLI:无单测,靠手工用例(8081 现役 6 位密码即活例);集成风险 = flags 解析,编码后本机过一遍即验。

## 手工测试用例(用户 build/debug 阶段执行)

1. `yz login --server http://localhost:8081 -u me -p secret` → 成功(6 位密码,400→login 回退生效);
2. `yz sprints list YPJ` → 列出 V21/V22/V23(id/状态/起止);
3. `yz items create YPJ "CLI 冒烟票" --desc "spec 0059 手工用例" --sprint "V22 扩展件"` → 输出已创建 + 编号;Web 打开该票见描述、且在 V22;验证后回收站删除(Web 或 `DELETE /api/items/{id}`);
4. `yz items create YPJ "CLI 冒烟票2" --sprint "不存在的sprint"` → 报错并列出可用 sprint 名,不建票;
5. 浏览器/`api.http`:`GET /api/items/YPJ-1` → 400(不再 500)。

## 验收(对照票)

- [ ] items create 带 --desc/--sprint 一步到位
- [ ] sprints list 可用
- [ ] 存量短密码服务器 yz login 直登成功
- [ ] `GET /items/{number}` 回 400
- [ ] api.http 负例区同步,gradle 集成测试绿

## As-built

(完工后补)
