# 0044 — V20 / YPJ-46:项目级 Flow 页五图一表 + Sprint burndown 上线

> 需求源:YPJ-46(2026-10-14 立票;2026-10-15 补行已批:velocity 条形 + burnup 折线并入本票,图库定 @mui/x-charts)。依赖的 API 票(Y54/Metrics)全 Done。分支 `task/YPJ-46`。

## 一、需求

### 背景
Flow Metrics 端点(M1–M12 + workload/cfd/aging/burndown/burnup)全部就绪,但前端只有数字概览:项目级 Flow 页不存在,sprint 页只有表格。数据可见性断在最后一公里。

### 范围
1. **项目级 Flow 页** `/p/{key}/flow`(侧栏 overview 之下新入口),五图一表:
   - WIP 条形:按 assignee 分组、按 status 堆叠(**需后端微扩**:`AssigneeCountDto` 加 `byStatus: Map<String, Int>`)
   - throughput 直方:`buckets`(BarChart)
   - cycle/lead 散点 + 百分位线:`items` 散点(x=完成日,y=天数)+ `percentiles` 参考线(ReferenceLine)
   - CFD 面积:`points[].counts` 按 status 堆叠面积
   - aging/bottleneck 表:`aging` 列表 + `queueWidth`(表格,非图)
   - 并入:velocity 条形(近若干 sprint completed)、burnup 折线(scope + done 双线)
2. **Sprint 详情页加 burndown 图**:现有表格保留,上方加「剩余 vs 理想线」折线(理想线 = 前端按起止线性插值)。
3. 依赖:web 新增 `@mui/x-charts`(票内已批)。
4. 每图配口径说明:各响应自带 `definitions`,图下 caption 展示;hover title 兜底。
5. 后端微扩带集成测试(workload byAssignee.byStatus)。

### 非目标
- reopen-rate / scope-change 不做图形化(scope 已作 burnup 内 scope 线;reopen 留口径数字,后续票再议)。
- 不做图内 drill-down/点击跳转 item。
- 不做全局窗口联动控件:各图用端点默认窗口(throughput/flow-times 需 from/to 的,页面用固定合理默认:近 30d,bucket=day;后续票再加控件)。
- sprint 页不做看板(呼应 Sprint 表格票)。

### 验收(票面)
- [ ] Flow 页五图一表;burndown 上线
- [ ] velocity/burnup 并入(已批)

## 二、详细设计

### 1. 后端微扩(MetricsService.workload)
- `AssigneeCountDto` 加 `val byStatus: Map<String, Int>`(key=status name;只统计 WIP=非 start 非 final)。
- 实现:byAssignee 分组处对每 assignee 的 WIP items 再按 status name 分组。DTO 是响应线型,直接加字段(旧客户端不受影响,加字段向后兼容)。
- 测试:`MetricsApiTest` workload 区段 + 1 例:两 assignee、跨 status 的 WIP items → byAssignee[].byStatus 断言;api.http workload 正例照旧(响应自解释,不加负例)。

### 2. web 依赖与页面骨架
- `web/package.json` + `@mui/x-charts`(与 MUI 7 兼容的 ^8.x,npm 解析最新兼容版)。
- 新页 `web/app/p/[key]/flow/page.tsx`;侧栏入口:`layout.tsx` List 在 overview 之后插 `<ListItemButton href={/p/${key}/flow}>` + `t.projectNav.flow`(新文案「Flow」)。
- 数据获取:页面 use client,`api<T>()` 并行拉 7 个端点(workload/throughput/flow-times/cfd/aging/velocity/burnup);burnup 需 sprint 区间——用项目 active sprint 的 startDate/endDate(无 active sprint 时 burnup 卡片显示「无进行中 sprint」占位)。
- texts.ts 新增 `t.flow` 区块(图题/图例/空态文案,中文)。

### 3. 图表选型(@mui/x-charts)
- BarChart(WIP 堆叠:series=各 status,data=byAssignee;BarChart(velocity);LineChart(burndown 理想线用 `Area`/第二 series 虚线;burnup 双 Line);ScatterChart(cycle/lead 两系列)+ `ReferenceLine`(p50/p85)。
- CFD:BarChart stack offset="expand"?——**用 LineChart 多 series area?** x-charts 无原生 StackedArea → 用 BarChart stacked(offset="none")近似阶梯,或 LineChart + Area。裁决:**BarChart stacked**(CFD 本质离散日快照,条形堆叠读数准确,x-charts 支持成熟)。
- 散点百分位线:flow-times p50/p85 两系列 ScatterOverlay?简化:两系列散点 + 图上以 caption 列出 p50/p85/p95 数值(x-charts ReferenceLine 仅支持垂直/水平于轴值——水平线可用,画 cycle p50/p85 两条)。裁决:画水平 ReferenceLine(cycleP50/P85),lead 百分位仅 caption。
- 所有图 `height` 固定(~280),网格统一;空数据(空数组)渲染空态 Typography,不渲染空图。

### 4. Sprint 详情 burndown
- `sprint/[id]/page.tsx` 表格上方插 BurndownChart(LineChart:remaining 实线 + 理想线虚线[起 remaining → 0]);数据端点 `?groupId=` 传本 sprint objectId。

### 5. 测试设计
- 后端:见 §1(1 集成用例);`gradle build` + 本地 integrationTest。
- web:`npx tsc --noEmit` + `next build`;E2E:flow 页冒烟 1 例(@smoke 之外):造 1 done item + 1 wip item → flow 页五图一表容器可见、burndown 图可见(不断言图形像素,断言卡片标题与空态/数据态切换)。
- 手工用例见 §三。

## 三、手工测试用例(用户 build/debug 执行)

1. 侧栏出现 Flow 入口;页面七卡:WIP 条形 / throughput / cycle-lead 散点 / CFD / aging 表 / velocity / burnup,各图下有口径 caption。
2. WIP 条形:同 assignee 多状态 item 时堆叠段颜色区分;无 assignee 归「未指派」。
3. 散点:完成若干 item(拖 status 制造 cycle 天数差异)→ 点分布 + p50/p85 水平线;caption 有 p95。
4. CFD:各 status 面积/条形堆叠随日期增长;final 列叠加最上。
5. aging 表:长期未动 item 靠前;queueWidth 有值。
6. active sprint 的详情页:burndown 实线 + 理想虚线;无 active sprint 时 Flow 页 burnup 卡显示占位文案。
7. 空/新项目:Flow 页各卡空态文案,不白屏。
8. 回归:overview 数字与 Flow 图口径一致(如 wip 计数);smoke e2e 绿。

## As-built(2026-10-16,PR #114 合入)

- **按票交付**:Flow 页七卡(WIP 堆叠条形/throughput/cycle-lead 散点+cycle p50/p85 参考线/CFD 堆叠条形/aging 表+queueWidth caption/velocity 近8/burnup 双线,无 active sprint 占位);sprint 详情 burndown;侧栏 Flow 入口;@mui/x-charts@9;workload 微扩 AssigneeCountDto.byStatus + 集成用例(MetricsApiTest 16 例)。
- **偏差(2 处)**:①CFD 用 BarChart stacked(x-charts 无原生 StackedArea,与 spec 裁决一致);②散点只画 cycle 水平参考线,lead 百分位走 caption。**理想线没手算**——burndown 后端本就返回 ideal/actual 双线,直接消费(spec §4 的前端插值方案作废)。
- **实现期记坑(3 条)**:
  1. **窗口日期须完整 ISO Instant**:date-only(`2026-10-16`)被 `MetricsService.parseInstant` 400 → `Promise.all` 整体 reject → `.catch` 静默吞掉 → 七卡全空态。e2e 只断言卡片标题拦不住(空态也显示标题)。修复:发完整 instant + 失败 `console.error` + e2e 补数据态断言(图例状态名 + svg)。
  2. **Jackson3 `JsonNode` 自带 `map` 遮蔽 Kotlin stdlib `Iterable.map`**(取 `values().map`,详见 0043 同源坑)。
  3. x-charts v9 适配:`ReferenceLine` 在 `@mui/x-charts/ChartsReferenceLine` 且无 `strokeDasharray`;legend slotProps 无 `hidden`。
- 测试断言细节:e2e 卡片标题断言一律 `.first()`(图例文案与标题重复触发 strict mode)。
- 验证:`gradle build` + 全量 integrationTest 绿;`tsc`/`next build` 绿;全量 e2e 两趟通过;手工用例 §三 用户通过(数据准确性暂不 verify,票面验收以容器/图形可见为准);ijhttp api.http 全过(8080)。
- 插曲:commit 时 `git add -A` 误扫仓库散落的 `audit-report.md`/`audit-screenshots/`(既有未跟踪审计遗留),已从提交剔除,文件仍在工作区,处置归用户。
