# spec 0139 — YPJ-218 甘特 S1:P0 修复(B1/B2/B3)+ 父汇总条 + FINAL 置灰

> 需求源:`docs/requirement/甘特图 PRD.md` §二R6/§三切片1/§决策3/§九。前置:YPJ-208 TIME 迁移已合(日期含时刻)。

## ⚠ 架构影响声明

无破坏。纯展示层修复 + 增量字段(GanttRow 增 `statusType`),不改契约既有字段语义。

## ① 需求(范围/非目标)

**范围**(PRD 切片1):
- **B1 孤儿 item 兜底为根**:父 item 已删/不存在(item 在,gantt 树走不到)的 item 在甘特上按根节点显示(depth 0),不消失。
- **B2 日期统一日界**:前端日期解析统一按本地墙钟。现状混用:`new Date("2020-01-15")`(date-only)按 **UTC 零点**解析、`new Date("2020-01-15T18:00")`(datetime,YPJ-208 后新形态)按**本地时区**解析,两者相差 8 小时,刻度/条位/今日线错位。
- **B3 条宽含 due 当天**:条宽 = due − start + 1 天(通例「含当天」);现状右端 = due 当刻度,少一天。
- **父汇总条**:有子孙的父行画汇总条 = 直接+间接子孙排期区间的**并集**;父无自有日期也能画(全靠子孙)。
- **父自有时间线定案(2026-11-17 用户拍板,替代 PRD §决策3 的两个显示方案)**:领域规则 = **父 item 不持有自有时间线**。落实为两层:
  - **入口拦截**:详情页加子 item(`addSubitem`)时,若父已有 startDate/dueDate → 弹选择:①忽略——清空父的 start/due 后建子;②迁移(默认选中)——自动创建子 item「{父标题}·原排期」承接父原时间线,父时间线清空,再建新子。
  - **甘特兜底**:父行只画子孙并集,父自有日期一律忽略不画(存量/绕过前端 API 直调的数据由这里兜底;API 硬校验不做,留后续票)。
- **父时间线输入禁用(用户补充,2026-11-17)**:已有子 item 的父 item,详情页开始/截止日期与时刻输入框 **disabled**(提示「已有子项,时间线由子项汇总」);加子拦截弹窗相应只在「无子且父有时间线」时出现——有子的父时间线框已禁用,不存在「再加子时父留时间线」的场景(存量脏数据仍由甘特兜底)。
- **FINAL 置灰**:statusType=FINAL 的行,任务条/菱形置灰(灰阶色 + 降透明度),红标(超期)不显示——后端 overdue 已按 FINAL 排除,前端条色也要脱离 primary/error 语义。

**非目标**(后续切片):范围过滤(S3)、六级缩放/视口滚动(S2)、依赖连线、milestone/release 泳道(S4)、计划/实际线(S5)、拖拽编辑。

~~待拍板(动工前必须定,PRD §决策3)~~:**已拍板**,见上「父自有时间线定案」;原显示层方案 A/B 作废——冲突在入口处预防,不在显示层处理。

## ② 详细设计(代码级)

### 后端(小改)

`backend/api/src/main/kotlin/yangzhou/api/business/project/ProjectService.kt` `gantt()`:
1. **B1**:`byParent` 分组前,把 `parentObjectId ∉ all` 的 item 归入根组(`parentObjectId → null` 的兜底 map);`depthOf` 同步兜底(父不在集内 → depth 从 0 起)。
2. **GanttRow 增字段 `statusType: String?`**(FINAL 置灰判定用;前端不再猜状态名)。响应字段追加,向后兼容。

不改 DTO 形状/不改端点。

### 前端 `web/app/p/[key]/gantt/page.tsx`(主改)

1. **B2**:统一解析函数 `parseDate(s: string): Date`——date-only(`^\d{4}-\d{2}-\d{2}$`)补 `T00:00` 后 `new Date(s)`(得本地零点);datetime 直接 `new Date(s)`(本地墙钟)。所有 `new Date(date字符串)` 处(axis/xOf/todayX)换用。今日线用 `new Date()` 本地,与解析口径一致。
2. **B3**:条宽计算 `right = xOf(dueDate + 1天)`(仅条,菱形不变);xOf 处对 due 侧加 DAY。日粒度轴不属本票(S2),不动刻度生成。
3. **父汇总条**:行渲染前算 `summaryOf(itemId): [start,end] | null`——递归收集全部可见子孙的 `[startDate??dueDate, dueDate??startDate]`,取 min/max;父行有子孙时画汇总条(高 8px 圆角、色 `text.secondary` 半透明);**父自有日期不画**(定案见①)。
4. **加子拦截**(详情页 `addSubitem`):父有 startDate/dueDate 时,quick-add 前弹 MUI Dialog 两选(默认②迁移):①忽略——PATCH 父清空日期后照建;②迁移——先建子「{父标题}·原排期」承接父原 start/due(PATCH 父清空)再建新子。对话框状态×动作真值表:

   | 父情况 | 动作 | 行为 |
   |---|---|---|
   | 无子、无时间线 | 加子 | 直接建,无弹窗 |
   | 无子、有时间线 | 加子 | 弹窗两选(默认②迁移):①忽略——PATCH 父(start/due=null)→ 建输入标题的子 → 刷新;②迁移——建「{父标题}·原排期」子(承接父 start/due)→ PATCH 父清空 → 建输入标题的子 → 刷新 |
   | 无子、有时间线 | 取消 | 不建,不改任何数据 |
   | 已有子 | 加子 | 直接建(时间线框已禁用,不存在父留时间线场景) |

   「已有子」判定用详情页已加载的 subitems(`subitems.length > 0`)。
4. **FINAL 置灰**:`r.statusType === "FINAL"` → 条/菱形 `bgcolor: "text.disabled"`、opacity 降;红超期色不生效(后端已保证 overdue=false)。
5. `web/lib/dates.ts`(YPJ-208 已建)增 `parseLocal(s)` 供甘特与其余页面后续复用,甘特先行。
6. **父时间线输入禁用**:详情页渲染时若 `subitems.length > 0`,start/due 的 DateInput 与两个时刻 TextField 设 `disabled` + 下方 caption「已有子项,时间线由子项汇总」;保存体仍原样回传时间线字段(不动数据,后端无校验)。

### 测试

- 后端单测/集成:孤儿 item 出现在 gantt rows 且 depth=0、parentItemId 保留原值;statusType 字段在(ItemDateApiTest 同文件或 gantt 相关测试处扩)。
- e2e(`gantt.spec.ts` 或 board 现有甘特用例扩):FINAL 行条置灰(断言样式类/透明度难断言,改断言 DOM 上 `data-final` 属性——条 Box 加 `data-final={r.statusType==="FINAL"||undefined}`);父行汇总条存在(`data-summary`);条宽含当天(两日期 item 断言条宽百分比 ≥ 手算值)。
- 手工用例见 ③。

### 否决的备选
- 后端算并集下发 summary 字段:并集是纯展示推导,前端已有全量树,后端加字段徒增契约面。
- B2 用 UTC 全线统一:排期语义本地墙钟(spec 0138 拍板),UTC 反而在今日线/显示上错位。

## ③ 手工测试用例(用户 build/debug)

1. 建父 P(自有 11-01~11-05)+ 走加子流程加 C:弹窗出现;选②迁移 → P 时间线清空,多出「P·原排期」(11-01~11-05)与 C;甘特父行画并集汇总条;
2. 同场景选①忽略 → P 清空、直接出 C;
3. 取消 → 无任何变化;
4. 绕过前端用 API 给有时间的父直挂子 → 甘特父行仍只画子孙并集,父自有日期不显示(兜底);
5. 孤儿 item(父被删)出现在甘特根部,不消失;
6. item 走到 Done → 条变灰,无红色;
7. date-only 与 datetime 混排(如 11-20 与 11-20T18:00)→ 条起点差对应小时比例,不再差 8 小时跳变;
8. 给已有子的父看详情:起止/时刻四框 disabled,提示「已有子项,时间线由子项汇总」;保存不动时间线;
9. 11-01~11-03 的条视觉覆盖 3 个日格(含 03 当天)。

## 验收清单
- [x] B1 孤儿兜底为根(集成绿)
- [x] B2 日期解析统一本地墙钟
- [x] B3 条宽含 due 当天
- [x] 父汇总条(并集;父自有日期忽略——入口拦截+甘特兜底)
- [x] FINAL 置灰 + statusType 字段
- [x] 父时间线输入禁用(有子 → disabled+提示)
- [x] e2e 扩断言绿;全量验证绿;As-built

## As-built(2026-11-17,PR #227)

一票一支单 PR 交付(本票无切片拆分)。与设计的偏差:
- 无日期的父归入「已排期」分桶(靠汇总条画),真未排期 = 无日期且无子——设计未明说,实现时补的判定;
- DateInput 新增 `disabled` prop(pointerEvents:none + 半透明 + data-testid=date-disabled),复用组件而非外层包 disabled 容器;
- 加子迁移因 POST /items 不带日期,流程为「建『·原排期』子 → PATCH 补父原日期 → PATCH 父清空 → 建新子」;
- e2e 断言走 data-summary/data-final 属性与几何宽度(sx 宽度走 emotion class,inline style 拿不到)。

验证:integrationTest 全量绿、e2e 全量绿、api.http 全绿(用户本机);GanttApiTest 单类+新 e2e 3 例(agent 本机)。CI build+e2e 双绿。
顺带修复:ItemDateApiTest 切片 b 遗留坏断言(断言 detail 不存在的 workdayStart,台账 A11)。
