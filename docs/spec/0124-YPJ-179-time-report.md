# spec 0124 — YPJ-179 工时聚合与查询(项目级:成员/type/时段)

> 状态:**草案——待用户过目**。三件套齐备(①需求 ②详细设计 ③手工测试用例);拍板点见 §0,显著标注。

## 0. 口径拍板点(动工前必须用户拍板)

| # | 拍板点 | 方案 A(推荐) | 方案 B |
|---|--------|--------------|--------|
| D1 | 端点形状 | 新增 `GET /api/projects/{key}/time-report?from&to&groupId`,一次返回 `{byMember, byType, byDay}`;旧 `time-summary` 原样保留(time 页在用,不动契约) | 扩展旧 `time-summary` 加维度参数(响应形状膨胀,单端点多义) |
| D2 | 前端落点 | 现有 `time` 页(`p/[key]/time`)加图表区:按人 bar、按 type bar、按天 line(@mui/x-charts,与 sprint/flow 页同款) | flow 页加工时区(工时语义与 Flow 混在一起) |
| D3 | sprint/组过滤 | 报表端点收 `groupId`(复用 metrics `memberItemObjectIds` 模式);**UI 先不做组切换**,time 页保持项目全景,组级图表留给 sprint 页另行立票 | time 页加组下拉选择器(本票 UI 面扩大) |
| D4 | 时段桶 | `byDay` 固定按天(UTC 日期,锚 = startedAt);跨天条目记入 startedAt 所在天;不加 week 桶(数据量小,前端可自行再聚合) | day\|week bucket 参数(平移 throughput,但暂无消费者) |
| D5 | 进行中条目 | **计入**,与现有 `time-summary` 的 `minutesOf` 口径一致(折算到 now,读时漂移已知) | 不计(报表口径与明细页"已花费"口径分叉,易误解) |

> D1–D5 全部推荐 A;用户回复「按 A」即可开工。

## 一、需求

### 背景 / 现状实锤

- TimeEntry 实体(persistence/Entities.kt:323):`itemObjectId / memberObjectId / startedAt / endedAt? / minutes?(补录,空=计时中) / note`。
- 既有端点(`business/time` 切片):计时/补录/起止三模录入、stop、单 item 明细+totalMinutes、`GET /projects/{key}/time-summary`(仅按人聚合)、工时建议。
- 前端:item 详情工时 tab;项目 time 页 = 按人卡片+手绘比例条。
- 缺口:跨 item 聚合只有"按人"一个维度;无 type 维度(YPJ-177 type 已动态化,可 join 定义显示名)、无时段维度、无图表。

### 范围(本票做)

1. 后端:项目级工时报表端点,一次返回三个聚合:
   - **byMember**:每成员 totalMinutes / entryCount(现有 summary 同构,补进 report);
   - **byType**:按 item.type 聚合 minutes,type 显示名 join ItemType 定义(缺定义回退 key,YPJ-177 惯例);
   - **byDay**:按天(UTC)聚合 minutes 的时序桶(图表用)。
   - 过滤参数:`from` / `to`(ISO-8601 date,可选,闭区间,锚=startedAt)、`groupId`(可选,限 item_group 成员集合)。
2. 前端:time 页图表区三图(按人 Bar / 按 type Bar / 按天 Line),口径文案外置(texts.ts)。

### 非目标

- 不做 sprint 页工时 tab(D3 方案 A 范围外,另行立票)。
- 不做周/月桶、不做导出、不做工时编辑/删除。
- 不动 YPJ-132 生命周期时刻(startedAt/completedAt 语义)——相邻不同概念。
- 不改录入端点与 `time-summary` 契约。

### 验收

- 报表端点对空项目返回三空数组,不 500。
- 补录/起止/计时中三类条目的 minutes 全部计入聚合;进行中折算到 now(D5-A)。
- groupId 过滤只统计该组成员 item 的工时;非法 groupId → 404。
- from/to 过滤生效;from>to → 400。
- type 显示名 join 定义表;定义缺失回退 typeKey。
- time 页三图与卡片数字一致(同一数据源)。

## 二、详细设计(代码级修改计划)

### 后端(backend/)

**`api/business/time/TimeEntryService.kt`** — 新增报表聚合(数据量=项目全部 TimeEntry 行,内存聚合,与 MetricsService.workload 同款;不写新 SQL,复用现有 repository 方法):

- `fun report(key: String, from: Instant?, to: Instant?, groupObjectId: UUID?): TimeReportDto`:
  1. `projects.findByKey(key)` ?: 404;
  2. item 集合:`findByProjectObjectIdAndDeletedAtIsNullOrderByNumber`;groupId 非空时经 `ItemGroupMemberRepository` 交(复用 metrics 的过滤模式:先 `groups.findByObjectId`+校验 project 归属,404 兜底)取成员 itemObjectId 集求交;
  3. `timeEntries.findByItemObjectIdIn(itemIds)` 后按 from/to 过滤(`startedAt ∈ [from, to]`);
  4. byMember = 现有 summary 同构 groupBy memberObjectId;byType = groupBy(item.type,经 items map)→ minutes sum;byDay = groupBy `startedAt` 的 UTC `LocalDate` → minutes sum,按日升序补零桶(从 min 到 max 天,与 throughput 补桶惯例一致);
  5. minutes 计算复用 `minutesOf`(进行中折算 now,D5-A)。
- DTO(小切片同文件):`TimeReportDto(byMember: List<MemberSummary>, byType: List<TypeMinutesDto>, byDay: List<DayMinutesDto>)`;`TypeMinutesDto(typeKey, typeName, minutes, entryCount)`;`DayMinutesDto(date: String(yyyy-MM-dd), minutes)`。

**`api/business/time/TimeEntryController.kt`** — 新增:

```kotlin
@GetMapping("/projects/{key}/time-report")
fun report(@PathVariable key: String,
           @RequestParam from: String?, @RequestParam to: String?,
           @RequestParam groupId: UUID?): TimeReportDto
```
- from/to 解析失败 → 400(与 metrics window 口径一致);from>to → 400。

**否决的备选**:SQL `@Query` GROUP BY 聚合——行数小、进行中条目需读时折算(now),SQL 算不了;内存聚合与 metrics 惯例一致。新建 `time-report` 表/物化——过度设计。

**依赖注入新增**:`ItemGroupRepository` / `ItemGroupMemberRepository`(过滤用),其余复用。

### 前端(web/)

**`web/app/p/[key]/time/page.tsx`** — 现有按人卡片保留,卡片下方加图表区(数据同一请求:`/api/projects/${key}/time-report`,替换现 `time-summary` 调用——report.byMember 字段超集,兼容):

- 按人 `BarChart`(x=displayName,series=minutes);
- 按 type `BarChart`(x=typeName);
- 按天 `LineChart`(x=date,series=minutes);
- 空态沿用 `t.time.empty`;文案进 `web/lib/texts.ts` `time` 节(byTypeTitle/byDayTitle/byMemberTitle)。

**`web/lib/api.ts`** — 无需改(通用 `api<T>`)。

### 操作流程(状态×动作真值表)

| 页面状态 | 动作 | 行为 |
|---------|------|------|
| 无任何工时记录 | 进 time 页 | 空态文案,不渲染图表 |
| 有记录(项目全景) | 进 time 页 | 卡片列表 + 三图一次渲染 |
| URL 直达(无记录项目) | 进 time 页 | 同空态 |
| 请求失败 | — | 页顶红色错误文案,保留已加载数据 |

### 页面示意图(ASCII)

```
┌──────────────────────────────────────────────┐
│ ← 看板   YPJ · 工时        项目合计: 12h 30m │
├──────────────────────────────────────────────┤
│ 按人聚合                                      │
│ ┌──────────────────────────────────────────┐ │
│ │ 张三            8h 00m · 12 条目          │ │
│ │ ██████████████░░░░░░░░░░                 │ │
│ │ 李四            4h 30m · 6 条目           │ │
│ │ ████████░░░░░░░░░░░░░░░░                 │ │
│ └──────────────────────────────────────────┘ │
│ ┌─ 按人(分钟)─┐ ┌─ 按 Type ─┐ ┌─ 按天 ──┐  │
│ │ ▇ 张三 480   │ │ ▇ bug 320 │ │   ╱╲    │  │
│ │ ▇ 李四 270   │ │ ▇ task 430│ │  ╱  ╲__ │  │
│ │              │ │           │ │ ╱       │  │
│ └─────────────┘ └───────────┘ └─────────┘  │
└──────────────────────────────────────────────┘
```

### 测试设计

- 单元(`src/test`):report 的桶计算/类型回退/空项目(纯逻辑,仿 TimeEntryServiceUnitTest 现有 seam;Clock/now 折算沿用现有注入方式)。
- 集成(`src/integrationTest`,extends AbstractApiTest):三模录入后聚合数字断言、groupId 过滤、from/to 边界(含 from>to 400)、404(项目/组)、空项目三空数组、type 定义缺失回退 key。
- api.http:`time` 区块后新增「time-report」自含节(节内自建项目+条目)。

## 三、手工测试用例(用户 build/debug 阶段执行)

前置:test 容器起(`script/app-infra.ps1 up test`),或 dev 容器重建后手工。以下按 dev 手工(3001)写:

1. **空项目**:新项目不录工时 → time 页显示空态文案,无图表,页不报错。
2. **三类条目全计入**:item A 计时 2 分钟后停;item B 补录 60m;item C 起止补录(昨天 14:00–15:00)→ time 页:合计 ≈ 122m + 折算;按人图只有我;按天图 yesterday=60、today≈62;按 type 图随 A/B/C 的 type 分布。
3. **type 显示名**:项目类型定义改名(如 task→"杂活")→ 按 type 图显示名随动;删定义(若可)后回退显示 typeKey。
4. **跨天起止**:起止补录跨 0 点(23:00–次日 01:00)→ 全部 120m 记入**开始日**。
5. **窗口过滤**(api.http 或 URL 直调):`?from=昨天&to=昨天` 只含昨天条目;`from>to` → 400。
6. **多成员**:第二成员录工时 → 按人图两根柱,合计=两人之和。

## 四、As-built(2026-11-14 交付,PR 待开)

### 与 §二设计的偏差(均为收窄,无范围膨胀)

| 项 | 设计 | 实落 | 说明 |
|----|------|------|------|
| from/to 格式 | ISO-8601 date | **完整 ISO-8601 时刻**(`Instant.parse`) | 与 metrics `window` 同口径;api.http 节内已注明 |
| 窗口上限 | — | 未加 365 天上限 | metrics 有(防历史扫描),工时行数天然有限,未加 |
| DTO 位置 | 小切片同文件 | ✓ 顶层同文件(`TimeReportDto/TypeMinutesDto/DayMinutesDto`) | |
| 进行中折算 | 复用 `minutesOf` | ✓ 静态化进 companion(`minutesOf(e, now)`,now 参数注入) | 单测可钉时刻;instance 版保留旧签名 |
| 补零桶 | 首末日之间补零 | ✓ `dayBuckets` TreeMap 实现 | |
| type 回退 | 缺定义回退 key | ✓ `typeBuckets`;删档 item 的条目跳过不计 | |

### 落点清单

- 后端:`TimeEntryService.report()`(聚合编排,内存分组,复用现有 repo 方法)+ companion 三纯函数;`TimeEntryController` `GET /projects/{key}/time-report`;构造注入 +3(ItemGroupRepository/ItemGroupMemberRepository/ItemTypeRepository)
- 前端:`web/app/p/[key]/time/page.tsx` 重写(数据源切 time-report;按人卡片保留 + 三图卡:按人 Bar/按 Type Bar/按天 Line,@mui/x-charts,flow 页同款 grid);`texts.ts` +2 文案
- 测试:单测 Report 节 7 例;集成 2 例(三模计入+显示名 join+窗口过滤+空项目;groupId 过滤+404/400 负例);api.http §13 +6 请求
- 教训入台账 #16(relaxed mock nullable 返回须显式 stub null)/#17(有序断言先推演实现的序)

### 验证

- 编译三路绿;单测/集成全量绿(用户实证);api.http 全文件按序过(用户实证)
- 手工:dev 实测(空态/补录计入/进行中折算/跨天归开始日/多成员两柱/type 改名随动——用户逐步走查中确认)

### 遗留(均设计内非目标,勿当欠账)

- groupId 过滤 UI 未做(后端参数就绪;组级图表另行立票,D3-A)
- week 桶未做(D4-A)
- byDay 为 **UTC 日口径**:北京晚 8 点后的条目入前一日桶(与 metrics 一致;改本地日需全局一致另议)
