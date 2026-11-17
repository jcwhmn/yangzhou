# 0138 — YPJ-208 item 排期 TIME 级迁移(主票:timestamp+工作日历+前端 datetime 控件)

> 需求源:PRD §五/§七;用户拍板(2026-11-17):拆三切片独立合并——a 后端迁移/兼容,b 工作日历,c 前端控件(**前端必须能录 time,否则功能无意义**);子票 223/224/225。票:YPJ-208,V31。

## ⚠ 架构影响声明

本票**动 schema(Flyway V66)+ 改 REST 契约语义**(日期字段从 date-only 扩为 datetime 兼容)。domain 引擎零 LocalDate 不受影响;ADR 无违背(三层容器/同质树/统一属性不动)。

## ① 需求(范围/非目标)

item 的 `startDate/dueDate` 升到分钟级,支撑:
- 排期/实际线管理到 time 级(甘特 S5 的前置);
- 「结束=开始+预估时长」按工作日历精确换算(AGENTS.md 排期纪律的系统化);
- overdue 精确到时刻。

**范围内(切片 a,YPJ-223)**:items 两列迁移;契约双向兼容;date-only 默认时刻(常量 09:00/18:00 硬编码,日历票前);overdue 口径;CLI `items schedule`。
**范围内(切片 b,YPJ-224)**:工作日历项目设置(workdayStart/End,默认 09:00/18:00)+date-only 填充改读日历+WorkdayCalculator(供 YPJ-216)。
**范围内(切片 c,YPJ-225)**:详情页 start/due 输入改 datetime-local(分钟粒度);date-only 兼容;甘特/表格/看板展示按档位裁剪;e2e fillDate 适配。
**非目标**:Milestone/Release 日期(保持 DATE);节假日日历;时区/多时区;依赖感知占位自动排期(YPJ-216)。

## ② 详细设计(代码级)

### 迁移 V66(切片 a;含 project 两日历列,切片 b 启用)
```sql
ALTER TABLE items ALTER start_date TYPE timestamp USING start_date::timestamp;
ALTER TABLE items ALTER due_date TYPE timestamp USING due_date::timestamp;
```
ALTER TABLE projects ADD workday_start VARCHAR(5) NOT NULL DEFAULT '09:00';
ALTER TABLE projects ADD workday_end VARCHAR(5) NOT NULL DEFAULT '18:00';
```
存量语义:全部变当日 00:00(展示层裁剪兜住,切片 c)。实体 `Item.startDate/dueDate: LocalDate? → LocalDateTime?`。

### 契约兼容(信任边界,ItemService)
- 解析器(私有纯函数,单测直测):入参 String → LocalDateTime:
  - `yyyy-MM-dd` → 该日 **DEFAULT_WORKDAY_START/END 常量 09:00/18:00**(切片 b 改读日历);
  - ISO datetime(`yyyy-MM-dd'T'HH:mm[:ss]`)→ 原样;
  - 非法 → 400(现行为)。
- 序列化:响应统一 `yyyy-MM-dd'T'HH:mm`(本地墙钟,无时区后缀)。

### 工作日历(切片 b,YPJ-224)
- `GET/PATCH /settings` 增 `workdayStart/workdayEnd`(HH:mm;校验格式+start<end)。
- 切片 a 的解析器常量改注入日历;b 交付后 date-only 填充跟日历走。

### overdue 与消费方
- `overdue = due != null && now > due`(Clock 注入,已有 seam);`now` 用 LocalDateTime.now(clock) 同墙钟语义。
- `ProjectService.gantt`:GanttRow startDate/dueDate 直接输出 datetime 串(前端百分比定位天然兼容);overdue 判定同步。
- `ExcelExportService`/metrics DTO:输出字符串原样透传,不改结构。
- `ItemGroupService`(sprint 起止)不动——group 日期保持 DATE。

### CLI(Main.kt;切片 a)
- `items schedule <KEY-N> <start> [--due <due>]`:start/due 接受 date-only 或 datetime;不传 due 时调 `WorkdayCalculator` 语义由后端算?——不,CLI 无日历;**due 缺省留空由后端填充不现实(PATCH 语义)**。方案:CLI 只透传 start(+可选 due),due 缺省=后端 date-only 默认填充规则自动得到 workdayEnd。显示层 `ifEmpty` 兼容新串无需改。

### 前端(切片 c,YPJ-225;用户拍板必须有录入能力)
- 详情页 start/due:输入改 `datetime-local`(分钟粒度),提交 `yyyy-MM-dd'T'HH:mm`。
- 展示裁剪:甘特/表格/看板日期只显 MM-dd(统一 helper);详情页显全串。
- e2e:`fillDate` helper 适配(date-only 填充在 datetime-local 上验证后决定是否改)。

### 测试
- 单元:解析器(date-only/datetime/非法)、WorkdayCalculator(同日/跨日/不足一日)、默认填充(workdayStart/End)。
- 集成(ItemDateApiTest 扩):PATCH date-only → 响应含 T09:00/T18:00;PATCH datetime 原样;settings 改日历后 date-only 填充跟随;overdue 时刻判定。
- api.http:items 区块补 datetime 负例(非法串 400)。

### 否决的备选
- Instant+时区:排期语义本地墙钟,时区无收益反添乱。
- 前端不换控件(原裁剪方案):**用户否决**——无 time 录入能力功能不成立。
- 一张票全包:用户指出拆分,改主票+三子票垂直切片。
- 前后端一起换 datetime 控件:爆炸半径大,拆票(前端随甘特 S 系)。

## ③ 手工测试用例(用户 build/debug)
1. `PATCH /api/items/{id}` body `{"startDate":"2026-11-21"}` → 响应 `2026-11-21T09:00`;
2. `{"dueDate":"2026-11-21"}` → `2026-11-21T18:00`;
3. `{"startDate":"2026-11-21T14:30"}` → 原样回;
4. settings 改 workdayStart 08:30 → 再发 date-only → 得 T08:30;
5. CLI `items schedule YPJ-x 2026-11-25` → 起止显示带时刻;
6. 甘特图正常渲染,存量 item 位置不变。

## 验收清单
- [x] V66 迁移 + entities.puml 同步(items 两列类型)
- [x] 契约兼容双向绿(集成)
- [x] 工作日历设置 + date-only 填充绿
- [x] overdue 时刻口径绿
- [x] CLI items schedule 可用
- [x] api.http 补节;`gradle build`/`integrationTest` 绿;As-built 补本 spec

## As-built(2026-11-17)

三切片按「一票一支、按序 PR」交付,全部合入 main:

| 切片 | 票 | PR | 内容 |
|---|---|---|---|
| a | YPJ-223 | #224 | V66(items 两列 timestamp 墙钟 + project 两日历列)、契约 date-only/datetime 双进 datetime 出、overdue 时刻口径、CLI `items schedule` |
| b | YPJ-224 | #225 | settings workdayStart/End(校验格式+start<end)、date-only 填充读日历、WorkdayCalculator.dueFrom 纯函数(供 YPJ-216) |
| c | YPJ-225 | #226 | 详情页 DateInput+时刻 TextField(type=time)并排、`web/lib/dates.ts` fmtDate 列表裁剪 MM-dd、board e2e datetime 断言 |

与设计的偏差:
- date-only 默认时刻切片 a 先硬编码 09:00/18:00,切片 b 落地后改读项目日历 workdayBounds(设计即此序,无偏差);
- 前端控件未用 `datetime-local`,拆成 DateInput + `TextField(type=time)` 并排(更贴合现有 DateInput 分段控件与 fillDate helper);
- e2e 补修一处用例竞态:保存→前端 load() 回拉会回填时刻框,用例须等回拉 GET 完成(台账 A10)。

验证:integrationTest 278 全例绿、api.http 全绿、全量 e2e 绿(均为用户本机);CLI 在 8082 test 实测。
