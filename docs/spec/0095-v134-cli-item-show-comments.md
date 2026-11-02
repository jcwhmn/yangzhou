# 0095 — v134 yz CLI:items show + items comments(YPJ-134)

> 2026-11-02 立项。CLI 命令面评估(spec 0094 §4)高优缺口:单票详情与列评论,服务过程考古与脚本取数。API 端点均已存在,纯 CLI 瘦壳,**零 REST API 改动**(api.http 不动)。

## 1. 需求

### 范围

- `items show <KEY-N|itemId> [--json]`:单票详情。人读默认:编号/标题/状态/类型/指派/sprint/priority/起止日期/feasSignal/overdue·blocked 标记/externalRef/createdAt/描述/需求/属性;`--json` 走 `printRaw`(全保真,脚本用)
- `items comments <KEY-N|itemId>`:按时间序列评论(`[MM-dd HH:mm] author: body`);无评论打 `(无评论)`

### 非目标

- 评论的写入/删除(133 已做写,删走 web)
- items list 迁移到分页端点(CLI 场景全量 list 够用)
- gitRefs/requirements 的展开展示(show 里单行摘要即可)

## 2. 详细设计(代码级)

| 文件 | 改动 |
|---|---|
| `cli/Main.kt` usage | 加两行命令说明 |
| `cli/Main.kt` dispatch | `"items show"`:`resolveItemId` → `GET /api/items/{id}` → `--json` ? printRaw : 逐行打印(ItemDto 字段名直取,null 跳过;描述整段缩进打印);"items comments":resolveItemId → `GET /api/items/{id}/comments` → 空打 `(无评论)`,否则逐条 `[时间] author: body`(createdAt 截到分钟) |
| `cli/SmokeTest.kt` | usage 锚点 +`"items show"`、`"items comments"`;补一例 `items show` 缺 ID 离线失败 |

否决备选:show 输出对齐 items list 的表格式(单票详情字段多且长短悬殊,逐行 key: value 更可读)✗;评论分页(百级内,全量返回)✗。

## 3. 手工测试用例(用户 build/debug 执行)

1. `items show YPJ-133` → 逐行详情(状态 Done、指派 me、sprint V27);`items show YPJ-133 --json` → 原始 JSON
2. `items comments YPJ-133` → 两条探针评论按时间序;`items comments YPJ-20`(无评论)→ `(无评论)`
3. `items show`(缺 ID)→ 非 0 退出报缺参,无网络请求
