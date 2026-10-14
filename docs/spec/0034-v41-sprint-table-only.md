# 0034 — YPJ-41 Sprint 页表格化(移除看板)

> 伞设计:spec 0033 §三。票:YPJ-41。分支 `cwjiang/YPJ-41-sprint-table`。

## ① 需求

**背景**:sprint 是时间盒(有界集合 + 燃尽语义),看板是流式列语义。当前 sprint 页同时提供两视图(dogfood 实证),语义重复且维护两套。看板语义已由项目级看板(spec 0023,active sprint items 并集)统一承载。

**范围**:Sprint 页(`web/app/p/[key]/sprint/[id]/page.tsx`)保留表格视图为唯一视图;移除看板视图与视图切换入口(ToggleButtonGroup)。

**非目标**:
- 不动后端(项目级看板仍消费 board 端点);无 schema/API 变更,api.http 不动。
- burndown / 指标不在本票(V20 YPJ-46)。
- 不改完成确认框逻辑(它用 `statuses` 判终态,与看板无关,保留)。

**验收**:
1. Sprint 页仅表格视图,无「看板/表格」切换按钮。
2. 看板相关 state(`view`)与看板 JSX 整块删除,无死代码残留;`next build` 无 unused 告警。
3. 页面其余功能(状态切换、拉入 item、assign、完成确认、空态提示)行为不变。
4. E2E:涉及 sprint 页看板断言的用例(如有)同步调整。

## ② 详细设计

单文件改动:`web/app/p/[key]/sprint/[id]/page.tsx`(395 行)。

1. 删 import:`ToggleButton`、`ToggleButtonGroup`(`statuses` 类型保留——完成确认框仍用)。
2. 删 state:`const [view, setView] = useState("board")`。
3. 删头部 ToggleButtonGroup JSX(看板/表格切换)。
4. 删整个看板块:`view === "board"` 的条件 JSX(状态列渲染、卡片、列内 AssignPopover)。
5. 表格块条件 `view === "table"` 改为 `items !== null && items.length > 0`。
6. 更新文件头注释(V12-S3 段):标注 V19 YPJ-41 移除看板,看板语义归项目级看板。

**否决的备选**:
- 看板挪为独立路由保留入口——票面明确「看板入口移除」,不做。
- 后端 board 端点删除——项目级看板仍消费,不动。

**测试**:
- E2E:检查 `web/e2e/` 中 sprint 页相关 spec 是否断言看板视图;有则改为表格断言。无单测(纯前端视图删减)。
- 验证:`cd web && npx next build` 绿;本地全量 Playwright 由用户 merge 前自跑。

## ③ 手工测试用例(供 build/debug)

1. 打开任一 active sprint 页:只看到表格(编号/标题/优先级/状态/负责人/截止),无切换按钮。
2. 「开始/完成 Sprint」「拉入 item」仍可用;完成后确认框数字正常。
3. 表格内 assign 弹层改负责人生效;点编号/标题跳详情。
4. 项目级看板(`/p/{key}`)不受影响,仍显示 active sprint items。
5. 空 sprint 页仍显示拉入提示文案。
