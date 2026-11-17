# 0138 — YPJ-208 item 排期 TIME 级迁移(start/due DATE→timestamp + 工作日历)

> 需求源:docs/requirement/甘特图 PRD.md §五/§七(用户拍板:item 域功能,与甘特解耦)。票:YPJ-208,V31。

## ⚠ 架构影响声明

本票**动 schema(Flyway V66)+ 改 REST 契约语义**(日期字段从 date-only 扩为 datetime 兼容)。domain 引擎零 LocalDate 不受影响;ADR 无违背(三层容器/同质树/统一属性不动)。

## ① 需求(范围/非目标)

item 的 `startDate/dueDate` 升到分钟级,支撑:
- 排期/实际线管理到 time 级(甘特 S5 的前置);
- 「结束=开始+预估时长」按工作日历精确换算(AGENTS.md 排期纪律的系统化);
- overdue 精确到时刻。

**范围内**:items 两列迁移;契约双向兼容(date-only 进,datetime 出);项目工作日历设置(workdayStart/End,默认 09:00/18:00);date-only 默认填充;overdue 口径;CLI `items schedule` 子命令(顺带交付 YPJ-207 的排期半边,`--estimate` 另计)。
**非目标**:Milestone/Release 日期(保持 DATE);节假日日历;时区/多时区;前端 datetime 控件(另票,随甘特 S 系前端做);依赖感知占位自动排期(YPJ-216)。

## ② 详细设计(代码级)

### 迁移 V66(`persistence/resources/db/migration/V66__item_dates_timestamp.sql`)
```sql
ALTER TABLE items ALTER start_date TYPE timestamp USING start_date::timestamp;
ALTER TABLE items ALTER due_date TYPE timestamp USING due_date::timestamp;
```
存量语义:全部变当日 00:00(读取侧由默认填充规则兜住展示,见下)。实体 `Item.startDate/dueDate: LocalDate? → LocalDateTime?`。

### 契约兼容(信任边界,ItemService)
- 解析器(私有纯函数,单测直测):入参 String → LocalDateTime:
  - `yyyy-MM-dd` → start=该日 `workdayStart`,due=该日 `workdayEnd`;
  - ISO datetime(`yyyy-MM-dd'T'HH:mm[:ss]`)→ 原样;
  - 非法 → 400(现行为)。
- 序列化:响应统一 `yyyy-MM-dd'T'HH:mm`(本地墙钟,无时区后缀)。
- `GET /settings` 增 `workdayStart/workdayEnd`("HH:mm");`PATCH /settings` 可改,校验 HH:mm 且 start<end。存 project 表两新列(V66 同迁移加列,默认 '09:00'/'18:00')。

### overdue 与消费方
- `overdue = due != null && now > due`(Clock 注入,已有 seam);`now` 用 LocalDateTime.now(clock) 同墙钟语义。
- `ProjectService.gantt`:GanttRow startDate/dueDate 直接输出 datetime 串(前端百分比定位天然兼容);overdue 判定同步。
- `ExcelExportService`/metrics DTO:输出字符串原样透传,不改结构。
- `ItemGroupService`(sprint 起止)不动——group 日期保持 DATE。

### 时长换算工具(本票交付,供 YPJ-216 复用)
`WorkdayCalculator`(api 模块纯类,构造参数 workdayStart/End):
- `dueFrom(start: LocalDateTime, hours: Double): LocalDateTime` — 按日历跨日铺(首日从 max(start, workdayStart) 起算,当日剩余不足则顺延次日);纯函数,单测覆盖跨日/跨周末不涉及(周末本期不做节假日,周六日**照算**——节假日另票)。

### CLI(Main.kt)
- `items schedule <KEY-N> <start> [--due <due>]`:start/due 接受 date-only 或 datetime;不传 due 时调 `WorkdayCalculator` 语义由后端算?——不,CLI 无日历;**due 缺省留空由后端填充不现实(PATCH 语义)**。方案:CLI 只透传 start(+可选 due),due 缺省=后端 date-only 默认填充规则自动得到 workdayEnd。显示层 `ifEmpty` 兼容新串无需改。

### 前端
- 本票**不动 UI 控件**(详情页日期输入仍是 date-only,经兼容层自动得默认时刻);甘特/详情展示 datetime 的裁剪(日期档只显 MM-dd)随甘特 S 系做。**风险标注**:详情页现在会显示 `2026-11-21T09:00` 全串——接受,前端票再裁。

### 测试
- 单元:解析器(date-only/datetime/非法)、WorkdayCalculator(同日/跨日/不足一日)、默认填充(workdayStart/End)。
- 集成(ItemDateApiTest 扩):PATCH date-only → 响应含 T09:00/T18:00;PATCH datetime 原样;settings 改日历后 date-only 填充跟随;overdue 时刻判定。
- api.http:items 区块补 datetime 负例(非法串 400)。

### 否决的备选
- Instant+时区:排期语义本地墙钟,时区无收益反添乱(PR 前已述)。
- 前后端一起换 datetime 控件:爆炸半径大,拆票(前端随甘特 S 系)。

## ③ 手工测试用例(用户 build/debug)
1. `PATCH /api/items/{id}` body `{"startDate":"2026-11-21"}` → 响应 `2026-11-21T09:00`;
2. `{"dueDate":"2026-11-21"}` → `2026-11-21T18:00`;
3. `{"startDate":"2026-11-21T14:30"}` → 原样回;
4. settings 改 workdayStart 08:30 → 再发 date-only → 得 T08:30;
5. CLI `items schedule YPJ-x 2026-11-25` → 起止显示带时刻;
6. 甘特图正常渲染,存量 item 位置不变。

## 验收清单
- [ ] V66 迁移 + entities.puml 同步(items 两列类型)
- [ ] 契约兼容双向绿(集成)
- [ ] 工作日历设置 + date-only 填充绿
- [ ] overdue 时刻口径绿
- [ ] CLI items schedule 可用
- [ ] api.http 补节;`gradle build`/`integrationTest` 绿;As-built 补本 spec
