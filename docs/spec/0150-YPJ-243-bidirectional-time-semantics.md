# spec 0150 — YPJ-243 时间语义双向互算:estimate↔due 后写者为准 + warnings 通道 + 甘特行依赖锚点

> 票:YPJ-243(8h,sprint V31,排期 11-20 09:00~17:00)。规则源:Obsidian `projects/yangzhou/Thoughts.md` 2026-11-20 节(用户拍板:due 与 estimate 二选一互算、后写者为准、重算+警告)+ 同节完备性补遗 H1–H12。
> **架构破坏显要标注**:本票显式推翻 spec 0146 案①的两处既有拍板——①「只改 estimate 不触发重算」→ 改为触发;②「due 显式清除生效,不回填」→ 改为 estimate 有值时铺算回填。0146 真值表作废,以本 spec §2.1 为准。其余 0146 语义(date/datetime 解析、dueFrom 铺算、显式 due 覆盖、start>due 400)不变。
> **拍板确认(2026-11-20,用户)**:①推翻 0146 清除不回填——确认;②改 due→estimate 反推**无条件**(无预估填预估、有预估改预估);③同请求双显式=双存+冲突警告(保守默认),「后写者为准」在 UI 层由前端保证(表单对 due/estimate 后改的字段单独/后发 PATCH,spec 0151 落);④改值即警(warnings 字段+前端 toast)。

## 一、需求

### 背景
- 用户 2026-11-20 拍板(Thoughts 原文):`dueDate 和 estimate 应该是二选一的,有其中一个,另一个可以自动计算。修改另一个,原来的那一个就需要重新计算并录入,当然,可以加警告提醒`。
- 已存在无需实现(补遗【已存在】节):estimateRequired 项目开关、start+estimate→dueFrom 铺算、甘特行 actualStart/actualEnd、due-only 菱形、未排期排序。

### 范围
1. `PATCH /api/items/{id}` 日期×预估互算重构:后写者为准真值表(§2.1)。
2. PATCH 响应(ItemDto)加可选 `warnings: List<String>`(契约向后兼容:缺省空数组;不加全局错误形状)。
3. `GET /api/projects/{key}/gantt` 行(GanttRow)加 `anchorDate: String?`(依赖锚点,H6),供前端点定位(消费在 YPJ-244)。

### 非目标
- 前端渲染/toast(YPJ-244,spec 0151);工作日历双休/节假日(H8 另票);占位自动排期(PRD §八-146 另票);estimate 档位管理(YPJ-180 既有)。
- actual 层解耦是前端渲染规则(H5),归 YPJ-244;后端 rows 字段已齐(actualEnd 已有)。

## 二、详细设计(代码级)

### 改动 1:`backend/api/.../business/item/ItemService.kt` — update() 日期块重构(现 L404–414)

**通用门(防表单全量保存噪声)**:互算仅在「显式携带的值相对存量发生变化」时触发;同值重发不触发、不警告。

**后写者为准真值表**(`estimate`=合并后 item 预估;`hours`=estimateHours(estimate);三态:缺省=未携带、`""`=显式清除、值=显式设置):

| start 携带 | due 携带 | estimate 携带 | 行为 |
|---|---|---|---|
| 任意 | 未携带(null) | **显式设置且变化**(H4) | **estimate 后写**:start(新/旧)有 → due=dueFrom(start,hours) 重算;start 无 → 只存 estimate,警告「无开始日期,due 未铺算」。原 due 被覆盖 |
| 任意 | **显式设置且变化,且 start 未同发** | 未携带(H3,已拍板无条件) | **due 后写**:start(存量)有 → estimate=workHours(start,due) 反推写回(value=hours,unit=`hour`)+警告;start 无 → 只存 due,警告「无开始日期,estimate 未推算」。无预估→填预估;有预估→改预估(用户 2026-11-20 拍板)。**start+due 同发=排期窗口**:双存,estimate 不动(否则排期每存一次都重写预估,且 start>due 400 被 C 分支滑走失效——集成用例实锤后收窄) |
| 显式/未携带 | 未携带 | 未携带 | 0146 既有:start+存量 estimate → due 铺算(estimate 未变,果随因) |
| 任意 | **`""` 显式清除**(H2) | 任意 | estimate 有且 start 有 → due=dueFrom 重算回填(清除被推算覆盖)+警告;estimate 无 → due=null 生效(推翻 0146「清除不回填」) |
| 任意 | 显式设置 | 显式设置(H1) | **双存**:两值都生效存储;若 start 有且 \|workHours(start,due)−hours\|>0.01 → 冲突警告,不互算。注:UI「后填者改先填者」由前端发送顺序保证(0151),API 层双显式无先后语义 |
| 仅其他字段 | — | — | 不触发互算(现状) |

- `workHours(start, due)`:WorkdayCalculator 新方法 `hoursBetween(start, due)`——dueFrom 的逆:工作时段内线性时长(逐日 `[max(start, ws), min(due, we)]` 累加;周六日照算同现状;due<start 返回 0,校验层另有 400)。
- 警告文案常量集中(ItemService 顶部,中文;`logActivity` 追加 `estimate_recalc`/`due_recalc` 动作留痕)。
- `ItemDto` 加 `val warnings: List<String> = emptyList()`;update() 返回时填充,其余查询恒空数组。

**否决的备选**:
- warnings 走 header/独立端点——契约复杂化,否决。
- 双显式时 due 赢覆盖 estimate——用户原则「后写者为准」是交互层概念,API 双显式无先后,取双存+警告最保守。

### 改动 2:`backend/api/.../business/project/ProjectService.kt` — GanttRow 加 anchorDate(H6)

- `GanttRow` 加 `val anchorDate: String? = null`。聚合在 gantt() 现有装配处:一次查全项目依赖(`dependencies.findByItemObjectIdIn`,模式同 blockedItemObjectIds L232),按 successor 分组,`anchor = max(pred.dueDate ?? pred.startDate)`;无前置或前置全无时间 → null。不逐行查询(防 N+1)。
- 语义:FS 锚点=前置完成点(最晚 due,无 due 退 start);显式 start 永远优先于锚点(前端规则,票 244)。

### 改动 3:`backend/script/api.http` — 互算区 + 负例区

- item 区追加互算正例:改 estimate → 响应 due 变 + warnings 空;改 due → estimate 变 + warnings 含推算提示。
- 负例区追加:清除 due 回填警告;双显式冲突警告;无 start 改 estimate 的「未铺算」警告。

### 测试(先读 `.agents/skills/backend-testing/SKILL.md`;双轨)

- 单元 `src/test`:`WorkdayCalculatorUnitTest` 加 `hoursBetween` 边界(同刻 0/跨天/收工后起点/跨周末仍照算)。
- 集成 `src/integrationTest`(extends AbstractApiTest):真值表逐行(6 行×断言 due/estimate/warnings 三元);gantt rows anchorDate(双前置取最晚 due/无 due 取 start/无前置 null)。**既有 0146 用例受影响处按新真值表更新**(改 estimate 现在触发重算;清除现在回填)。
- 时钟纪律:日期断言用注入 Clock/固定 project,不依赖今夜时刻。

## 三、手工测试用例(用户 build/debug 阶段执行,dev 3001)

前置:测试项目开 estimateRequired=开(项目设置),另开一个未强制项目对照。
1. 建票填 estimate=2h、start=今天(不填 due)→ due 自动=11:00,甘特条 2h 宽;改 estimate=4h → due 变 13:00(重算生效)。
2. 改 due=明天 17:00 → estimate 自动=17h(9h+8h),详情页显示 17 hour,toast 警告「estimate 已按 start→due 重算」。
3. 清除 due 保存 → due 回填为 estimate 铺算值 + 警告(0146 行为已变)。
4. 表单同时改 due+estimate(值冲突)→ 双存 + 冲突警告;值一致 → 无警告。
5. estimate 强制项目建票不填 estimate → 400(既有);填 start 不填 due → 条必有(数据驱动)。
6. 甘特 API(本票):行含 anchorDate;前端点定位等票 244 完成后验。
