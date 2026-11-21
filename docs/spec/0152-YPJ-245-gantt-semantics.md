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

## 四、实现增量(2026-11-21 编码期;As-built 合并回合补)

1. **存量 bug 修复(计划外,探针实锤)**:PATCH 只带 due 会把存量 start 抹成 null(copy(startDate=newStart) 不传即抹)——§12.5 实现前置修复,见台账 A42;400 改**结果态**判定(resultStart)。
2. 借锚排序波及既有期望:GanttApiTest YPJ-233 用例 c2 借父链锚 → C 组锚=12-01 组序变更(排序锚=渲染锚的正确结果,期望更新);ItemDateApiTest 超期用例补 start。
3. e2e:FINAL 行默认入组 → 3 个既有用例补 expandCompleted(waitFor 版,A43 竞态实锤后修);overview fixture due-only 补 start(新契约);新增徽标三档/返工/折叠组三用例。
4. api.http §17(due-only 负例+回归钉+清场自含节);手测清单 245 节(A-E 四组)。
5. A41:容器→GitHub TLS 断,yz items branch 延后——push 前必须补关联。
6. **存量布局 bug 修复(计划外,e2e 三红实锤,台账 A44)**:甘特条区 Box(flex:1、子元素全绝对定位)在 `alignItems:center` 行容器里零高度居中——绝对定位几何整体下移 22px(徽标/完成点溢出到下一行)、行内今日线(top:0/bottom:0)高 0 不可见、整行点击链接剩 0 高条带。修复 = 条区 `alignSelf:"stretch"` 恢复整行高(设计几何 top5 计划条/top24 实际条本按整行高标注);YPJ-229 今日线 e2e 补高度>8px 断言作回归钉。main 同结构,存量缺陷非本票引入,但徽标(本票特性)直接撞上。视觉变化:计划/实际条上移回设计位,今日线重现——手工验收 A/B 时请留意。
7. e2e 测试侧修正(台账 A45):fixture `dtH` 小数小时被 setHours 截断(-0.5h→-1h)改毫秒偏移;返工例 hover 目标改 Tooltip 命中区(补 testid `gantt-hover-plan/act-N`,fill 被命中区覆盖非可 hover 面)。

## 五、As-built(2026-11-21,PR #239 squash 8e9dbbf 合入 main)

> 验收:用户 `gradle clean build` + 全量 `integrationTest` 绿、`api.http` 全过、手测 245 节 A–E 15 条全过(当面确认「可以收了」);agent 本地全量 e2e 121/121 绿;CI checks build+e2e pass。用户 e2e 轮 120 过 1 红(table.spec 分页超时)= F9 flake 桶复现 1/3,拍板忽略,242 落地后观察是否自愈。yz:245 Done(sprint V31 甘特图完善,无父票)。

- §二 六项全落地;§12.5 存量拍板 = 默认**方案 A**(契约只拦新写入,前端菱形分支保留渲染 YPJ-105/144 存量,零数据风险)。
- 计划外修复两处(均探针/e2e 实锤,台账 A42/A44):① PATCH 只带 due 抹存量 start → §12.5 的 400 改**结果态**判定;② 甘特条区 Box 零高度居中缺陷(存量,非本票引入)→ 条区 `alignSelf:"stretch"` 复位设计位,行内今日线重现、徽标/完成点不再溢出行——**本票像素级视觉与 spec 示意图即按修复后形态验收**。
- 期望更新(借锚排序的正确波及,非回归):GanttApiTest YPJ-233 c2 组序变更(排序锚=渲染锚);ItemDateApiTest 超期用例补 start;overview fixture due-only 补 start(新契约)。
- e2e 测试侧修正(台账 A45):fixture 小数小时截断改毫秒偏移;hover 目标改 Tooltip 命中区(testid `gantt-hover-plan/act-N`)。
- 交付形态:13 文件 +1280/−515(backend 7:ProjectService/ItemService/RowOrder/DateApi/GanttApi/单元×2 + api.http;web 3:gantt/page.tsx + gantt/overview spec;docs 3:spec/手测/台账);本地分支保留(014422a,过程考古),远程已删。
- 验收遗留(非本票):overview N+1 性能根因归 **242**(A46 实锤:能力 PUT 尾部 recompute 全 workspace 逐 item,~120 项目库单发 10.8-12.7s);甘特视觉打磨归 **246**(今日线贯穿全高为 A44 副产品,正式验收归 246 需求域 #2)。
- 流程留痕:台账 A39–A46;手测 245 节留作回归基线勿删。