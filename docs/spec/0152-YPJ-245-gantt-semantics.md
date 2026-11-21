# spec 0152 — YPJ-245 甘特语义统一:返工渲染/过滤后包络/徽标三档/借锚排序/due-only 拒绝/已完成折叠组

> 票:YPJ-245(8h,V31;前置 YPJ-244 已合入)。范围=票面 6 项,对应 SRS v4 §17.3 前六条。
> **不在本票**(§17.3 其余三项,待用户确认另立票):fit 同源(§9.4)、实际条长=实际工时接线(TimeEntry 合计,§3.6)、case1/2 借锚降兜底的代码清理。

## 一、需求(逐项;权威=SRS 对应节)

1. **返工渲染(§6.6)**:状态非 FINAL 一律按 §6.2 进行中渲染(黄条延伸到今天);上次完成时刻保留在 Hover 卡,不参与渲染。
2. **过滤后包络与排序(§10.5)**:过滤后祖先链完整(已实现);**父行包络与排序按过滤后可见子孙计算**——现状缺口:前端包络(summaryOf)已按 rows(过滤后)计算,但**后端排序锚 `ganttWalkOrder` 用未过滤树**(`keep` 只过滤输出行,`childrenOf` 仍是全量)。
3. **徽标状态分诊(§11.1)**:偏移徽标**仅 FINAL 行**显示(进行中零徽标,超期由红计划条表达);三档 \|Δ\|<1h On time(中性)/ 1h≤\|Δ\|<24h ±Nh / ≥24h ±Nd;提前绿/延期红;Δ=actualEnd−due(真实完成时刻);due 无值不显。
4. **借锚行排序(§8.2)**:叶锚 = min(计划层左端 **startDate ?? dueDate ?? 借锚位**, 实际层左端 actualStart ?? actualEnd)——现状缺口:后端叶锚不含借锚位(依赖锚/父链 group 借锚),借锚行错落未排期组。
5. **due-only 契约拒绝(§12.5)**:dueDate 有而 startDate 无 → **400「须先填开始日期」**(无论 estimate;废除 due-only 新增)。**存量拍板点**:prod 有 2 条 due-only(YPJ-105/YPJ-144)——本票默认**方案 A:契约只拦新写入,前端菱形分支保留渲染存量**(零数据风险);B=存量回填 start(数据变更须拍板)、C=用户 UI 手工补 start 后删菱形分支,待用户选。
6. **已完成折叠组(§10.6)**:FINAL 行默认折叠进「已完成(N)」组,可展开。

### 非目标
- fit 同源、工时接线、借锚代码清理(见票头);徽标结构化 warnings;拖拽编辑;虚拟列表。

## 二、详细设计(代码级)

### 后端

**B1(item2)`ProjectService.gantt()`**:walk 改用 keep 过滤后的树。新增 `byParentKept = all.filter { it.objectId in keep }.groupBy { effectiveParent(it) }`,`walk(null)` 的 siblings 与 `childrenOf` lambda 均用 `byParentKept`;`byParent`(全量)仅保留给 keep 计算。效果:父行排序锚=可见子孙最早端;未排期判定也随可见集(§15.4)。

**B2(item4)`ganttWalkOrder` 加参**:`borrowedAnchorOf: (Item) -> LocalDateTime?`(纯函数第 4 参)。叶行 own = `listOfNotNull(item.startDate ?: item.dueDate ?: borrowedAnchorOf(item), aS ?: aE)`;`isUnscheduled` 同步纳入借锚位(借锚点=可渲染元素,SRS §5.4)。调用点 `gantt()` 内:借锚位 = `anchors[item.objectId]`(依赖锚)?: 父链上溯最近祖先 `startDate`(与前端 `groupStartOf` 同口径;父链无 start → null)。GanttRowOrderTest 同步加例。

**B3(item5)`ItemService.update()` 日期块**:L476 `start > due` 校验前加 `if (due != null && start == null) throw BadRequestException("须先填开始日期")`(结果态判定:显式设 due 无 start、H1 双显式无 start、start 显式清除后 due 残留,三路全拦)。H3 分支 `else { warnings += W_NO_START_DUE }` 成死代码 → 删除;`W_NO_START_ESTIMATE` 保留(estimate 变更无 start 仍为合法警告路径)。create() 不带日期,无需改;全写路径经 update 覆盖(含 CLI import)。

### 前端 `web/app/p/[key]/gantt/page.tsx`

**F1(item1)返工渲染**:行渲染区(L1200-1460)
- `const rework = r.actualEnd !== null && r.statusType !== "FINAL";`
- `doing` 定义并入:`r.actualStart !== null && (r.actualEnd === null || rework)`(黄覆盖/黄条复用 §6.2 全部分支)
- `actColor`:`isFinal ? "success.main" : "warning.main"`(返工行黄,不再绿)
- 下条端点:`isFinal && r.actualEnd` 才用 actualEnd,否则延伸到 todayX(与 doing 同)
- `completeDot` 加 `isFinal` 门
- hover 命中区条件补 rework(actualStart 有且非 FINAL 即命中)
- `hoverCard` 补行:`{rework && <Typography>上次完成:{fmtDateTime(r.actualEnd)}</Typography>}`

**F2(item3)徽标三档**:`offsetDays` → `offsetBadge(r): {label,color} | null`:
- 门:`statusType !== "FINAL" || !dueDate || !actualEnd` → null
- Δh = (actualEnd − due) 毫秒/3.6e6;\|Δh\|<1 → `On time`(text.secondary 中性);<24 → `±{round(\|Δh\|)}h`(提前绿/延期红);否则日历日差(现有 day0 口径)`±{days}d`
- 渲染门 L1516 改 `off !== null && isFinal`;色取 badge.color

**F3(item6)已完成折叠组**:
- `isCompletedRow(r) = r.statusType === "FINAL" && (叶 || 全部子孙 statusType===FINAL)`(父行仅当整子树完成才摘除,防孤儿)
- `scheduled` 拆 `mainRows` / `completedRows`;渲染序列 = mainRows.map(renderRow) → **组头行** → 展开时 completedRows.map(renderRow)
- 组头行 = 面板内全宽行(title 列「▶ 已完成(N)」按钮 + 条区空),默认收起,useState 会话态;testid `gantt-completed-group` / 展开行沿用原 testid
- **渲染顺序拍板:主树 → 已完成(N) → 未排期组**(已完成=刚做完可回看,未排期=待办池置尾不变)
- 360 行行渲染 JSX 抽为局部闭包 `renderRow(r)`(机械提取,两处复用,零语义变化)
- 包络/轴不变:summaryOf/axis 走 `rows`,被摘除行仍可见(渲染于组内)→ 仍参与父行包络与轴范围(§10.5 口径不破)

### 状态×渲染真值表(前端;SRS §6/§11.1 全分支)

| statusType | actualStart | actualEnd | 计划层 | 实际层 | 徽标 |
|---|---|---|---|---|---|
| FINAL | 有 | 有 | 蓝条/点(按 case) | 绿条 aS→aE(双条模型) | 三档 |
| FINAL | 无 | 有 | 同上 | 完成点(直建 FINAL) | 三档 |
| 非 FINAL | 有 | 有 | **返工**:蓝底+黄覆盖(有计划条时) | **黄条 aS→今天**(无计划条时下条黄) | **零** |
| 非 FINAL | 有 | 无 | 同上 | 同上(常规进行中) | 零(超期=红计划条) |
| 非 FINAL | 无 | 有 | 同上 | 完成点(防御分支,数据上=直建 FINAL 后重开必有 aS,不可达) | 零 |
| 任意 | — | — | due-only 菱形(**存量兼容保留**) | — | — |

### ASCII 页面示意

```text
┌──────────────┬──────────────────────────────────┐
│ 标题列        │  甘特区(今日线贯穿)              │
├──────────────┼──────────────────────────────────┤
│ ▼ 父任务A     │   ▓▓▓蓝条+黄覆盖▓▓░░  [徽标?]     │ ← 主树(进行中/未开始)
│   叶任务A1    │   ▓▓▓░░░黄覆盖                   │
│ ▶ 已完成(3)  │                                  │ ← 新:默认收起(组头行)
│   (展开后:   │   绿条+徽标 On time/±Nh/±Nd      │
│    FINAL行)  │                                  │
└──────────────┴──────────────────────────────────┘
  ▶ 未排期(2)                                     ← 置尾不变(标题列表)
```

### 否决的备选
- 徽标三档在后端算好随行下发(避免前端重复)——badge 是纯展示且依赖渲染时刻今天,无跨端一致性问题,前端算最小改动,弃。
- 已完成组放未排期组之后——已完成工作比未排期待办更"热",且未排期置尾是 §8.2 既有规则,不再往下压,弃。
- 折叠组持久化到 savedView(localStorage)——票面无此需求,会话态最小实现,要持久化另说。

## 三、手工测试用例(prod 3000,镜像重建后;用户 build/debug)

前置:prod 建测试票(见各条);已有 244 手测环境。
1. **返工**:票走 To Do→FINAL→重开 In Progress → 甘特:黄条延伸到今天(无绿条/无徽标);hover 显示「上次完成:…时刻」。
2. **徽标三档·On time**:FINAL 且 actualEnd≈due(差<1h)→ 条尾「On time」中性色。
3. **徽标三档·±Nh**:due 改早 5h 后 FINAL → 「−5h」绿;due 改晚 3h → 「+3h」红。
4. **徽标三档·±Nd**:due 早 3 天 FINAL → 「−3d」绿。
5. **进行中零徽标**:进行中超期票(due 昨天)→ 计划条红,**无徽标**。
6. **过滤后排序**:milestone 过滤只挂一个子;父行汇总条/排序按可见子计算(被过滤的早日期兄弟不影响父行位置)。
7. **借锚行排序**:依赖链 A(due 明天)→B(无 start/due);B 行锚点画在 A.due 后,**不落未排期组**,按锚位排序。
8. **due-only 400**:PATCH `{"dueDate":"2026-12-01"}`(无 start)→ 400「须先填开始日期」;toast 透出文案。
9. **已完成折叠组**:3 条 FINAL 叶 → 树尾「▶ 已完成(3)」;展开 → 三行绿条+徽标;收起 → 隐藏;汇总/未排期组不受影响。
10. **存量菱形**:YPJ-105/144(due-only 存量)→ 仍画菱形,不报错。

### 测试清单(自动化)
- 单元:`GanttRowOrderTest`(借锚锚位/实际左端 min/未排期判定);`ItemServiceUnitTest`(due 无 start 400:显式 due/双显式/清 start 残 due)
- 集成:`GanttApiTest`(+过滤后排序 case/借锚排序 case);`ItemDateApiTest`(+400 负例)
- `api.http`:负例区补 due-only 400
- e2e:`gantt.spec.ts`(徽标三档断言/返工例/折叠组例);board 涉 ±Nd 断言同步
