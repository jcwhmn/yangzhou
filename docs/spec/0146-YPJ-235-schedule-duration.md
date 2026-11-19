# spec 0146 — YPJ-235 排期时长换算:due 未填系统按 estimate 算

> 状态:三件套待用户过目,过目后编码。2026-11-19 用户拍板案①(PRD §八原案);否决案②(条端 max,与 222 计划条定义打架)。

## 一、需求

**背景**:用户报 5h 与 13h 的 item 甘特条长不成比例——date-only 排期把两者都排成同一工作日窗口(起 09:00 止 18:00),条=start→due 必然同宽。

**拍板语义(案①)**:estimate 是因,due 是果。**排期请求未携带 due 时,系统按 estimate 用工作日历铺算出 due;请求显式携带 due(含空串清除)时显式值生效。**甘特条=start→due 不动,条端单自由度归 due,estimate 信息内含其中——5h/13h 比例自动成立。

- **范围**:后端 `PATCH /api/items/{id}` 的日期处理(update() 日期块;ys `items schedule` 与详情页日期编辑都走它)。estimate→hours 换算。
- **非目标**:占位自动排期(PRD §八-146,未实现,另票);存量 date-only 数据批量重算(用户重排一次即生效);前端改动(契约形状零变化);甘特渲染;节假日历(WorkdayCalculator 现状周六日照算,双休/节假日历另票)。

**语义真值表**(请求打到 update(),`estimate` 指合并后 item 的预估):

| 请求 | estimate | 现状 due | due 结果 |
|---|---|---|---|
| 设 start,缺省 due | 有值 | null | **系统算**:start+estimate 工作日铺 |
| 设 start,缺省 due | 有值 | 有值 | **系统算重算覆盖**(案①:due 是果;旧 due 不保留) |
| 设 start,缺省 due | 无 | 任意 | 旧行为:due 不变(null 保持 null) |
| 设 start,带 due | 任意 | 任意 | 请求 due(显式覆盖) |
| 设 start,due="" | 有值 | 有值 | **null(显式清除生效,不回填)** |
| 只设 due / 只改 estimate | 任意 | 任意 | 不触发系统算 |

**estimate→hours 换算**(供 `WorkdayCalculator.dueFrom(start, hours)`):
- `hour` → 值;`day` → 值 × 工作日窗(分钟/60,默认 540/60=9h);`week` → 值 × 5 × 工作日窗(5 工作日,周内不跳周末同口径);unit 缺失按 `hour`。

**已知语义边界(如实告知)**:条长=日历跨度。同日条按工作日铺后成比例(5h→09:00~14:00,13h 跨到次日 13:00);跨日条含夜间,像素比例是日历比而非工作比——与 MS Project 同口径,PRD 条语义如此。

## 二、详细设计

**单文件改动:`backend/api/src/main/kotlin/yangzhou/api/business/item/ItemService.kt`**

现状日期块(update(),~L396):parse(date-only→ws/we 填充)→ newStart/newDue → start>due 校验 → save。接线:

1. parse 后插入系统算分支:

```kotlin
val newStart = parse(startDateText, "startDate", ws)
val newDue = parse(dueDateText, "dueDate", we)
// YPJ-235(拍板案①):due 缺省(未携带)且设了 start 且 estimate 有值 → 系统按工作日铺算 due;
// 空串=显式清除不回填;显式 due 覆盖
if (startDateText?.trim()?.ifEmpty { null } != null && dueDateText == null) {
    estimateHours(current)?.let { h ->
        newDue = WorkdayCalculator(ws, we).dueFrom(newStart!!, h)
    }
}
```

   (`newDue` 需改 var 或提为局部函数返回; estimate 读 `current`——estimate 合并在日期块之前已 save,**同请求改 estimate+start 时新值生效**,顺序现状已保证,编码时核对。)

2. 换算 helper(放 `workdayBounds` 同区,internal 供单测):

```kotlin
internal fun estimateHours(item: Item): Double? {
    val v = item.estimateValue ?: return null
    val (ws, we) = 默认窗  // item 无 project 上下文,helper 收 (ws,we) 参数更纯——编码时定
    val winMin = Duration.between(ws, we).toMinutes().toDouble()
    return when (item.estimateUnit ?: "hour") {
        "hour" -> v
        "day" -> v * winMin / 60.0
        "week" -> v * 5 * winMin / 60.0
        else -> v // 未知单位按 hour,不抛(信任边界已在 EstimateInput)
    }
}
```

3. start>due 校验保留(computed due ≥ start 恒成立,显式冲突仍抛);dates_changed 活动日志自动带算出的 due,无需新类型。

**否决备选**:①条端 max(due, start+estimate)——用户 2026-11-19 拍板否决;②仅在结果 due==null 时算(保守案)——重排要清两次才生效,弃;③前端算 due——信任边界在后端,弃。

**测试影响**(规矩:单测优先,集成留关键路径):

- 单元 `ItemServiceUnitTest`(镜像既有风格):系统算(5h→当日 14:00;13h→次日 13:00)/已有 due 重算覆盖/无 estimate 不动/due=""清除不回填/day·week 换算/未知单位兜底。
- 单元 `WorkdayCalculatorUnitTest` 已存在,不动。
- 集成(端点 seam,`ItemsApiTest` 追加):PATCH start(date-only)+estimate 无 due → 响应与库内 dueDate=算值;显式 due 覆盖;清空不回填。
- e2e(web):无(既有用例均显式带 due,不受影响)。
- `api.http`:排期区块补一条「不带 due 系统算」用例(行为契约,供手测)。

## 三、手工测试用例(bootRun/build 后)

1. `yz items schedule YPJ-N 2026-11-23`(票 estimate 13h)→ 详情页 due=**2026-11-24T13:00**(当日铺 9h,次日 4h);甘特条跨两日,与 5h 条(当日 14:00)同日尺度成比例;跨日条含夜间属预期。
2. `yz items schedule YPJ-N 2026-11-23 --due 2026-11-25T18:00` → due=手填值(覆盖)。
3. 详情页清空 due → 保持空,不回填。
4. 无 estimate 的票 schedule start → due 保持 null(现状不变)。
5. 甘特回归:5h 与 13h 两条重排后同日起点,条长比 ≈ 5:13(当日窗内)。
