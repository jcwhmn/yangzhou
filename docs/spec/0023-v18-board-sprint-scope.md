# 0023 — V18:看板 sprint 语义重构(YPJ-29)

> 状态:spec 待过目 · 2026-10-12 · 分支 `cwjiang/YPJ-29-board-sprint-scope`(经 yz 关联)

## ① 需求(grill 定稿 2026-10-12)

- 看板 = **当前 sprint 工作面**:请求不带 sprint 参数,后端解析 `status=active` 的 sprint 并返回其关联 items(多个 active 则合并)。
- **去掉看板手动 sprint 切换器**(含「无 sprint」选项);assignee/blocked 过滤保留。
- 无 active sprint:看板显示**全部 items** + 顶部提示条「当前无进行中 sprint」+ **「开始 sprint」按钮**(列出 planned sprint,一键激活)。
- 全局驾驶舱(home)**不动**;backlog / table / gantt / sprint 详情页**不动**(仅看板消费方式变化)。

**非目标**:assignee 过滤持久化(原 URL param 方案随切换器删除而失效;assignee 过滤维持组件内状态,另票另议);sprint 管理交互(激活/完成)除「开始 sprint」入口外不动。

## ② 详细设计

### 后端(新端点,不动现有 /items)

```
GET /api/projects/{key}/board
→ BoardDto { activeSprints: List<SprintDto>, items: List<ItemDto> }
```

- `SprintService.board(key)`:activeSprints = status=active 的 groups;
  - 非空:items = 各 active sprint membership 并集 ∩ 非删 items(按 number 序,复用 `SprintService.items` 的 membership→item 装配模式)
  - 空:items = `itemService.list(key)` 全量(降级语义,**后端决定**,前端不传参)
- 端点挂 `SprintController`(@GetMapping("/projects/{key}/board"));DTO 放 SprintService 同文件(小切片惯例)。
- `backend/script/api.http`:项目区块按序号新增 GET board(负例不涉及)。

### 前端(`web/app/p/[key]/page.tsx`)

- 数据源:`GET /api/projects/{key}/board` 替代 `GET /items` + 前端 sprint 过滤。
- 删 `sprintFilter` state 与切换 Dropdown;`bySprint` 客户端过滤删除。
- `activeSprints.length === 0` → 渲染提示条:`t.board.noActiveSprint`(文案外置 texts.ts)+ 「开始 sprint」菜单(planned sprints 来自既有 GET /sprints;选中 → PATCH /sprints/{id} status=active → 重拉 board)。
- 拖拽改状态、建 item、assignee/blocked 过滤链路不动。

### e2e 触点

- 现有用例 fixture 均无 sprint → 看板无 active → 显示全部,断言预计不破;实现时全量本地跑确认。
- 新增 1 用例:API 建 planned sprint → 看板断言提示条 + 显示全部 → 点「开始 sprint」→ 断言仅 sprint 内 items 且提示条消失。

### 否决的备选

- `GET /items?scope=active`:参数式仍让前端知情,不如独立 board 端点语义干净;且 /items 语义(全量列表)不被污染,table/gantt 零改动。
- 前端自行解析 active sprint:违背「后端解析」的用户裁决。

## ③ 手工测试用例(用户 build/debug 用)

1. 建 2 个 item(不进 sprint)→ 看板显示全部 + 提示条;「开始 sprint」列出 planned → 激活 A → 看板只剩 A 内 items,提示条消失。
2. item 拖拽改状态/新建/指派过滤照常;backlog/table/gantt 显示不变。
3. sprint A 完成后(无 active)→ 看板回到全量 + 提示条。
