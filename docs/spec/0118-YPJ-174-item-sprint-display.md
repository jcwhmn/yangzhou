# 0118 — Item sprint 显示:completed sprint 成员不消失(YPJ-62 案例)

> 日期:2026-11-13。用户报:item detail 页有 sprint 的 item 不显示所在 sprint(YPJ-62)。
> 状态:已完成 · 2026-11-13——YPJ-174 落地(#203 squash = 9d1a383),实现与设计一致,As-built 附后。

## ① 需求

**症状实锤**(2026-11-13,prod 库 + API):YPJ-62 在 `item_group_member` 有行(V21 架构收敛,`status=completed`),但 `GET /api/items/YPJ-62` 返回 `sprintId=null, sprintName=null`——detail 页/表格/yz 全部显示为无 sprint。

**根因**:`ItemService.buildCtx` 的 `currentSprint` 映射带 `it.status != "completed"` 过滤,`ItemDto.sprintId/sprintName` 从它取值。对照:release 同位映射**无状态过滤**(released 照显)——sprint 是唯一被滤掉的组类型。

**范围**:仅 DTO 组装的 sprint 字段来源;**非目标**:backlog/看板谓词零改动、不加历史 sprint 列表 UI、release 语义不动。

**语义真值表**(新:优先活行,否则最近历史):

| item 的 L1 成员行 | sprintId/sprintName |
|---|---|
| 有非 completed 行(active/planned) | 该行(现状不变;多行时非 completed 优先) |
| 仅 completed 行(YPJ-62 型) | `createdAt` 最晚的 completed 行(最近一次归属) |
| 无行 | null(现状不变) |

## ② 详细设计

`ItemService.kt` 单文件:

1. `ItemCtx` 加字段 `latestSprint: Map<UUID, ItemGroup>`(currentSprint 保留——backlog 谓词 `objectId !in currentSprint` 依赖其「仅非 completed」语义,不动);
2. `buildCtx` 新增 latestSprint:全部 L1 行按 item 分组,`firstOrNull { status != "completed" } ?: maxBy { createdAt }`;
3. `toDto` 两行换源:`sprintId/sprintName = latestSprint[item.objectId]`。

否决备选:直接改 currentSprint 加 completed(破坏 backlog 谓词,items 会从 backlog 消失);前端单独拉历史(多一个端点,无谓)。

## ③ 手工测试用例

1. YPJ-62 详情 → 显示「V21 架构收敛」;表格视图 sprint 列同显;`yz items show YPJ-62` sprintName 非空
2. 活 sprint item(如 YPJ-166/168)→ 显示不变(回归)
3. 集成:item 挂 sprint → 完成该 sprint → item 详情仍返 sprintName;item 同挂两 sprint(active+completed)→ 显示 active

## ④ As-built(2026-11-13)

- 落地与 ② 一致,1:1:`ItemService.kt` ItemCtx 加 `latestSprint`(全部 L1 行按 item 分组,`firstOrNull { 非 completed } ?: maxBy { createdAt }`);toDto sprint 两字段换源;`currentSprint` 未动(backlog 谓词 line 198 原样)
- 测试:`ItemsApiTest` +2 例(完成后仍显示且回 backlog / 活行优先于历史行),新增 `detail`/`assignSprint` 私有 helper;`gradle build` / `integrationTest` / e2e 全量绿(用户跑)
- 手工验收:YPJ-62 详情显示「V21 架构收敛」✓
- 偏差:无。搭车修 milestone.spec 票2(me[0]→apiAssignMe,台账 #10)与本 spec 无关,同分支搭车(#9 先例)
- api.http:无涉(无端点/参数变更,仅 DTO 取值源变化)
