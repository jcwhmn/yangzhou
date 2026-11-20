# spec 0151 — YPJ-244 甘特计划层形态规则:工时填充+实际层解耦+依赖锚点消费+表单后写者为准(spec)

> 票:YPJ-244(6h,V31)。规则源:Obsidian `Thoughts.md` 2026-11-20 节(「项目开始时」渲染分支)+ 完备性补遗 H5/H6/H9/H12;243 已落(0150)。
> **工时填充 A/B 拍板:案 A**(用户 2026-11-20,「最高优先级尽快看到」——A 纯前端、零契约改动、重建 web 镜像即见效;B(TimeEntry 后端聚合)弃:动后端契约且 dogfood TimeEntry 登记少、填充不可见,B 若将来要另立票)。
> 本票纯前端(web/),无架构破坏。

## 一、需求

### 背景
- 243(spec 0150)落了后端双向互算 + `warnings` 通道 + 甘特行 `anchorDate` 字段,前端尚未消费:页面无任何可见变化。
- 用户最关心:进行中 item 在计划条上叠「已发生工时」填充(长度+颜色),现在没有。

### 范围(两切片,各自可合并/验证绿)

**切片 1 — 甘特渲染增量(用户尽快看到,先做)**:
1. **工时填充(案 A)**:进行中 item(actualStart 有 && actualEnd 无)在计划条上叠填充段,末端 = min(今天, due) 的 x 位置;今天 ≥ due 填满,≤ start 不画。颜色琥珀(warning 半透明),区别于计划蓝/实际绿。
2. **实际层解耦(H5)**:实际条不再要求计划 start 存在(现 `act` 条件含 `hasStart`,去掉);actualComplete 有而 actualStart 无(直建 FINAL)→ 在 actualComplete 处画完成点。
3. **依赖锚点位(H6 消费)**:计划层无 start 无 due 且 `anchorDate` 有 → 在锚点位置画点(与既有 start 点同形);hoverCard 补锚点行。
4. 图例补填充/锚点说明。

**切片 2 — 表单后写者为准 + warnings toast**:
5. `saveBasics` 拆 PATCH:due 与 estimate 都被改时,按编辑顺序**后改字段后发**(保 0150「后写者为准」语义;API 层双显式=双存,后发覆盖)。
6. PATCH 响应 `warnings` 非空 → 页内 Snackbar toast 逐条展示(手搓 MUI Snackbar,不加依赖)。

### 非目标
- B 案(TimeEntry 聚合)另票;实际条遮罩/偏移徽标/父行汇总/菱形/未排期排序(0147/0148 既有)不动;工作日历双休(H8 另票);占位自动排期(PRD 另票);甘特行改条交互(只读甘特不变)。

## 二、详细设计(代码级)

### 切片 1:`web/app/p/[key]/gantt/page.tsx`

**渲染真值表(状态×渲染行为)**:

计划层(上):
| start | due | anchorDate | 渲染 |
|---|---|---|---|
| 有 | 有(含铺算存储) | — | 条 start→due(既有)+ **若进行中叠填充(新)** |
| 有 | 无 | — | start 处点(既有) |
| 无 | 有 | — | due-only 菱形(既有) |
| 无 | 无 | 有 | **锚点位画点(新,H6)** |
| 无 | 无 | 无 | 不画(既有) |

实际层(下,H5 解耦):
| actualStart | actualEnd | 渲染 |
|---|---|---|
| 有 | 无 | 条 actualStart→今天 + 遮罩(既有,去掉 hasStart 门) |
| 有 | 有 | 条 actualStart→actualEnd(既有) |
| 无 | 有 | **actualComplete 处完成点(新)** |
| 无 | 无 | 不画 |

工时填充(案 A):
| 条件 | 填充 |
|---|---|
| 计划条在 && actualStart 有 && actualEnd 无 && due 有 | `[条left → xOf(min(now,due))]`,clamp 到条宽;≤0 不渲染 |
| 今天 ≥ due | 填满整条 |
| 其余(无条/已完成/无 due) | 不填充 |

**修改点**:
- `GanttRow` 类型加 `anchorDate: string | null`(后端 0150 已返回)。
- L1041 `act` 条件:`drawOwn && hasStart && r.actualStart !== null` → 去掉 `hasStart`;actualStart 无 && actualEnd 有 → 完成点(9px 圆,实际条绿色系,`drawOwn` 门同现状)。
- 计划条 Box(L1145 分支内)叠子 Box:absolute 填充层,`bgcolor: warning.main` opacity 0.45,`data-testid={gantt-fill-${r.number}}`;now 取渲染时刻 `Date.now()`(与今日线同源)。
- 锚点分支:计划层无 start 无 due && anchorDate 有 → 点 `xOf(anchorDate)`;`hoverCard` 补「锚点」行;图例(L1305)补「琥珀填充 = 已流逝工时(今天 vs 计划)· 锚点 = 依赖落位」。
- **shortcut 注释**:案 A 填充按日历时间位置画,等价于工时比例仅在「周六日照算」现状成立;双休日历(H8)落地时改 `hoursBetween` 比例换算。

**否决的备选**:
- 填充按 estimate 百分比画在条上(工时比例)——与 x 位置法等价(H8 现状)但要多算比例,x 位置法直接复用 xOf,弃。
- 填充画在实际条上——用户语境「计划条上叠已发生工时」,且实际条已有完成率遮罩,再叠语义混,弃。

### 切片 2:`web/app/p/[key]/i/[itemId]/page.tsx`

**表单编辑顺序×保存行为真值表**(本 session dirty 跟踪:`lastEdited: 'due' | 'est' | null`,onChange 更新):

| due dirty | est dirty | lastEdited | 保存行为 |
|---|---|---|---|
| ✓ | ✗ | due | 单 PATCH{startDate…, due}(现状形状) |
| ✗ | ✓ | est | 单 PATCH{startDate…, estimate…} |
| ✓ | ✓ | due(后改) | PATCH1{startDate…, estimate} → PATCH2{due}(due 后发=为准) |
| ✓ | ✓ | est(后改) | PATCH1{startDate…, due} → PATCH2{estimate} |
| ✗ | ✗ | null | 单 PATCH 照发全量(现状) |

- start 恒随 PATCH1(先发);两 PATCH 顺序 await,第二个失败时前端提示(现 alert 通道)。
- **warnings toast**:响应体 `warnings` 数组非空 → Snackbar(底部,`severity=warning`)逐条文案;无机制新增,MUI Snackbar 手搓一个 `useState+<Snackbar>`(不加 notistack 依赖)。保存成功提示(saved)照旧。
- 修改点:`saveBasics()`(L655)+ due/estimate 输入 onChange 处记 `lastEdited`;`texts.ts` 无需新键(toast 文案=后端返回原文)。

### 测试
- 单元:渲染层无纯函数可单测(xOf/min 逻辑内联),靠 e2e。
- e2e(`web/e2e/`,切片 1 各 1 例 + 切片 2 各 1 例,进既有甘特/item 用例文件):进行中行存在 `gantt-fill-*` 且宽 > 0;FINAL 直建行存在完成点(testid `gantt-complete-*`);锚点行画点;表单先改 est 后改 due 保存 → 详情 due 被反推(warnings toast 可见)。数据准备走 API helpers;MUI 对话框淡出先 `toBeHidden()`。

## 三、手工测试用例(dev 3001,用户 build/debug)

前置:dev 项目(estimateRequired 开)建票 start=今天 09:00、estimate=8h(→due 17:00 铺算);另备依赖链 A(due 昨天)→ B(无 start 无 due)。
1. B 行甘特:计划层点画在 A 的 due 之后(锚点位);hoverCard 显示锚点日期。
2. 给票1 登记工时(actualStart=今天)→ 刷新甘特:蓝条上琥珀填充到今天位置;把 estimate 改 16h(243 重算 due=明天)→ 填充变短(比例随条总长变化)。
3. 今天 17:00 后看:填充满条。
4. 直建 FINAL item(actualComplete=昨天,无 actualStart)→ 甘特实际层画完成点。
5. 无 start 的进行中 item(estimate 未强制项目,actualStart 有)→ 下条绿条照画(H5,现状不画)。
6. 表单:先改 estimate=4h 再改 due=后天,保存 → 详情 estimate 显示按 start→due 反推的小时数(后改 due 为准)+ toast 警告;反向先改 due 后改 estimate → estimate=4h 生效、due 重算。
7. 清 due 保存 → due 回填 + toast(0150 H2 消费)。

## 五、渲染规则 v2(2026-11-20,用户 Obsidian 追加节;本节推翻 §二 部分渲染设计)

> 规则源:Obsidian Thoughts.md 2026-11-20 追加(用户原文)。分型 2×2(有无 estimate × 有无可确认开始时间[startDate 或依赖锚])× 3 状态(未开始/进行中/final)× 3 色(蓝=计划 / 黄=进行中实际 / 绿=完成实际)。

### 5.1 真值表(12 格)

| case | 未开始(蓝) | 进行中(蓝+黄) | final(蓝+绿) |
|---|---|---|---|
| 1 三无(无 est 无锚) | group 开始时间圆点 | 黄条 actualStart→今天 | 绿条 actualStart→actualEnd(仅一条) |
| 2 有 est 无锚 | 蓝条 @ group 开始,长=estimate 铺算 | 蓝条 @ actualStart(estimate 铺算长)+黄覆盖;下条黄 | 上蓝条 @ actualStart(estimate 长)+下绿条 actual 长 |
| 3 无 est 有锚 | 蓝点 @ start/锚 | 蓝点 @ start/锚 + 黄条 actualStart→今天 | 蓝点 @ start/锚 + 绿条 actualStart→actualEnd |
| 4 有 est 有锚 | 蓝条 start→due | 蓝条 start→due + 黄覆盖(已流逝);下条黄 | 蓝条 start→due + 绿条 actualStart→actualEnd |

补充判定(与 0150 语义衔接):
- 计划条长度权威序:due 显式 > estimate 铺算;有长度来源(due 或 estimate)→ 条,仅锚无长度 → 点(否则 start+due 无 estimate 的存量行会被误画成点)。
- due-only 行(无 start 有 due,里程碑)保留菱形,不进本矩阵。
- 真未排期 = case1 且全链(父链)无 group 开始锚。

### 5.2 完备性分析(用户要求)

- 12 格全覆盖,分型互斥完备 ✓。
- 5 处含糊/缺口,读法如下(**⚠请复核**):
  1. 「group 开始时间」=父链上溯最近祖先的 startDate;全链无 → 真未排期(折叠区)。
  2. 进行中/final case2 的蓝条锚=actualStart(无计划锚借实际锚,长度=estimate 按工作日历铺算)。
  3. 进行中 case3 除黄条外保留计划位蓝点(用户字面只提黄线段;蓝点=start 计划位延续)。
  4. **下条(实际条)进行中恒画黄、final 恒画绿**——用户 case2/4 进行中字面只描述上条(蓝+覆盖),下条由「颜色2=工作中实际工作时间」定义隐含;若意图=进行中单条覆盖模型(下条取消),验收时说一声即改。
  5. final 的 diff 徽标(±Nd)**建议保留**(实际完成 vs due 一眼量偏差);待用户拍板,默认保留。
- estimate 铺算前端复刻:dueFromJs(每日窗线性,周六日照算,无午休;窗取 project API workdayStart/End,默认 09:00-18:00);estimateHours 口径 hour=v / day=v×窗 / week=v×5×窗(与后端 estimateHours 同源)。
- **轴刻度 = 24h 钟表线性(A28,2026-11-20 用户拍板「满辐 48h、8h=1/6」;同日曾试工作窗轴,实锤夜间排期票(237 02:00–05:00/240 05:00–06:00)整条被折叠成 3px 而否决回退)**:条长 ∝ start→due 钟表跨度,全部条随缩放线性增长;跨夜条含夜间钟表时长(用户接受);estimate 铺算仍按工作窗算 due(铺算语义不变)。e2e 20 绿(新增同起点 8h:4h=2:1 用例——钟表轴下同样成立)。

### 5.3 实现(纯前端,后端零改动)

- `groupStartOf`:rows 树内父链上溯;`dueFromJs`/`estimateHoursOf` 模块级 JS 移植。
- gantt 页 load 并行拉 GET /api/projects/{key} 取 workday 配置。
- 分流:case1/2 借 group 锚 → scheduled;轴锚定加 gStart/estimate 铺算末端。
- hover 卡:起止 fmtDateTime(含时分);预估/实际工时按小时(实际=状态史 actualStart→actualEnd 时长)。
- 轴映射:xOf/xOfT/xEndOf/todayX = 24h 钟表线性((t-start)/DAY);小时刻度梯 0–24 墙钟。
- **行序:排序锚=渲染锚(0148 修订,后端 ganttWalkOrder)**:叶行自有 startDate(due-only 仍落未排期组);父行取严格子孙并集最早端(子孙逐点以 startDate ?? dueDate 贡献,含中间层父行自有日期,与 0139 汇总条同口径)——207 自有 11-18 但 208 子孙起 11-15,修前 207 误居首;217 借中间层 219 自有 11-18 09:00 锚定。单测 GanttRowOrderTest 7 例 + 集成 GanttApiTest 排序用例同步更新。
- 色:下条 doing=warning.main / final=success.main;填充 warning.main;遮罩同态;菱形/汇总条/偏移徽标不变。
