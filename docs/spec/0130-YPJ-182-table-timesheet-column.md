# spec 0130 — YPJ-182 表格视图:工时列

## ① 需求

- 表格视图(p/[key]/table)新增「工时」可选列:每 item 的时间合计(分钟)。
- 范围:仅工时列。其余列扩展(类型/创建时间/Milestone/Release 等)归已推迟的列扩展大需求票,本票不铺开。
- 合计口径与既有工时功能完全一致(`minutesOf`:有 `minutes` 用之,否则按 startedAt→endedAt(进行中则折算到 now)折算)——不新增语义。
- 后端形态拍板(本会话):**方案 A,挂现有 items 列表响应**(每 item 带 `totalMinutes`),不建独立批量端点;聚合用已有 `findByItemObjectIdIn` 内存分组,**不加冗余列、不加新表、无迁移**。

## ② 详细设计

1. `backend/api/.../item/ItemService.kt`
   - `ItemDto` 加 `val totalMinutes: Long = 0`(默认 0,detail/get 不填也兼容)。
   - 注入 `TimeEntryRepository`;`list()` 里对 `visible` 批量取行、按 item 分组求和,映射进 DTO copy(同 parentNumber/attributes 批量模式)。
   - `get()` 不填(详情页已有完整工时 tab,列表语义足够)。
2. `web/app/p/[key]/table/page.tsx`
   - `COLUMN_DEFS` 加 `{ id: "timesheet", label: "工时", visible: false }`(默认隐藏,列配置里可开)。
   - cell 渲染:0 显示 `—`,>0 用现成时长格式(与详情页工时 tab 同式,如 `1h 30m`)。
   - Row 类型加 `totalMinutes: number`。
3. `backend/script/api.http`:无新端点,不改。

**否决的备选**:独立 time-totals 端点(多一次往返);item 冗余 total_minutes 列(写路径 5+ 处同步负担+回填迁移,当前规模无收益)。

**测试**:
- 集成(extends AbstractApiTest):建 2 item → A 补录 minutes=60 + 计时进行中一条 → B 无工时 → GET 列表断言 A totalMinutes 含补录+进行中折算、B=0;backlogOnly 参数不受影响。
- 单元:无纯函数新增(minutesOf 已有单测),不设。

## ③ 手工测试用例

1. 表格页 → 列配置 → 勾选「工时」→ 列出现,有工时 item 显示合计、无工时显示 —;
2. 对某 item 开计时 → 表格工时数值随时间增长(折算到 now);点 stop 后定格;
3. 关掉列配置再开 → 顺序/可见性与其它列一致持久化。
