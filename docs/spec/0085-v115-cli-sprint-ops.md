# spec 0085 V44 YPJ-115 yz CLI 补 sprint 操作(sprints move + items assign)

票:YPJ-115。纯 CLI 薄层,单文件,零后端改动。

## 一、需求

### 范围
- yz 新增两命令(瘦客户端,打既有 REST):
  - `sprints move <KEY> <sprint名|UUID> <planned|active|completed>` → `PATCH /api/projects/{key}/sprints/{groupId}`,body `{"status": …}`
  - `items assign <KEY-N|itemId> <sprint名|UUID|backlog>` → `PUT /api/items/{itemId}/sprint`;`backlog` = sprintId 传 null(移出当前 sprint)
- 复用既有 resolver:`resolveItemId`(KEY-N→UUID)、`resolveSprintId`(UUID 直过/项目内名称精确匹配,0/多匹配报错列候选)——都是现成函数
- `usage()` 补两行帮助

### 非目标
- 不做 sprint create/delete CLI;不做 `--json`(println 确认行,与 `items move` 同款);不做批量
- 不动 yangzhou skill(完工后我手改 `~/.pi` skill 的传输行切到 yz,不入 repo)

## 二、详细设计(代码级)

改 `backend/cli/src/main/kotlin/yangzhou/cli/Main.kt` 单文件:

1. `usage()`:sprints 段补 `sprints move <KEY> <名|UUID> <状态>`;items 段补 `items assign <KEY-N|itemId> <sprint名|UUID|backlog>`。
2. dispatch `when ("$noun $verb")` 加两支:
   - `"sprints move"`:`rest[0]`=KEY、`rest[1]`=sprint 引用、`rest[2]`=状态名,缺参 error;状态白名单 `{planned, active, completed}`(否则 error 列可选,免一次 400 往返);`resolveSprintId` → PATCH → println 确认行。
   - `"items assign"`:`resolveItemId`;引用串忽略大小写等于 `backlog` → sprintId=null;否则 `GET /api/items/{itemId}` 取 number 前缀为 project key → `resolveSprintId(api, key, ref)`;PUT body `{"sprintId": id|null}` → println(item 编号 + sprint 名或「已移出 sprint」)。
3. 403(非 leader)/404 语义走 ApiClient 既有异常路径,不特殊处理。

否决的备选:
- `sprints move <KEY-N> …` 用 item 编号式定位 sprint——sprint 没有编号,KEY-N 是 item 的;票面描述笔误,以本 spec 为准。
- 命令名 `assign` 单独动词——已被成员指派占用,`items assign` 前缀区分。
- CLI 侧做「旧收尾+新激活+迁移」编排——那是 YPJ-116 的服务端单事务,CLI 保持单调用薄层。

## 三、手工测试用例(用户 build/debug 阶段)

1. `yz sprints move YPJ "<测试sprint>" active` → 打印已迁移;web 上状态同步 active。
2. `yz sprints move YPJ <UUID> planned` → UUID 直过。
3. `yz items assign YPJ-<n> "<测试sprint>"` → item 挂入;`yz items assign YPJ-<n> backlog` → 移出,回 backlog。
4. 负例:状态名拼错 → 列可选;sprint 名不存在/不唯一 → 报错列候选;非 leader 执行 → 可读报错。
5. 编号解析:`yz items assign YPJ-1 <sprint>` 与 UUID 等效。

## As-built(完工后补)

- 实际改动:Main.kt(usage 两行 + dispatch 两支)。
- 验证:手工用例 1–5;`gradle :cli:build` 绿(CI 兜底)。
