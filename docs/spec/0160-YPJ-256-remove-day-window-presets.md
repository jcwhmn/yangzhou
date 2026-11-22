# spec 0160 — YPJ-256 甘特日窗预设按钮(1/2/3/5 日)移除

> 小票精简 spec。背景:234 缺省自适应视窗 + 连续缩放落地后,日窗预设冗余(用户 2026-11-22 提出)。

## 需求

删除工具栏 1/2/3/5 日预设按钮及整条 `goToDayWindow` 机制;e2e 中 4 处以 `gantt-win-*` 为缩放手段的用例改为 zoom-in 连点/今日按钮。

## 设计

- `useTimelineScale.ts`:删 `goToDayWindow`/`applyDayWindow`/`pendingWinRef`/pending 补放 effect 及导出(唯一消费方是预设按钮,连带死代码)。
- `page.tsx`:删预设按钮组与 `goToDayWindow` 引用。`savedView`(zoom/center/scope)无 dayWindow 字段,无残留依赖(已核)。
- e2e 改造:
  - L167-170 可见清单去 4 个 win 项
  - YPJ-219「1 日预设小时刻度」→ zoom-in 连点至 `gantt-row-hour` 出现;工况保持断言同步改
  - YPJ-229「慢加载挂起补放」:挂起机制随功能删除 → 该部分删,保留今日居中 + 零时长条断言(改用 gantt-today 居中)
  - YPJ-230/236 段2「宽屏不被 720 夹」→ zoom-in 连点后日距 ∈ [1000, 2400](MAX_PPD 钳制仍在)
- 非 goal:zoom/今日/视点日导航语义不动。

## 手测

甘特:4 个预设按钮消失;+/- 缩放、今日回中、视点日 ◀▶/指定日期均正常;刷新后缩放位记忆保持。

## 验收(票面)

按钮消失;tsc/e2e 全绿;缩放/今日/视点日导航不受影响。
