# 0118 — Item sprint 显示:completed sprint 成员不消失(YPJ-62 案例)

> 日期:2026-11-13。用户报:item detail 页有 sprint 的 item 不显示所在 sprint(YPJ-62)。

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
