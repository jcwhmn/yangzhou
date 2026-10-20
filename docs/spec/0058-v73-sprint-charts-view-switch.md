# 0058 — V75:sprint 页 charts 视图切换(YPJ-73)

> 状态:**草案(等用户过目后编码)**
> 票:YPJ-73(V22);关联:YPJ-74(sidebar 同步,单独票)、#1 发现的「无 chart 空态」并入本票

## 需求

### 背景

sprint 页(YPJ-46 引入)现有唯一图表 = 燃尽 LineChart,渲染条件 `bd.actual.length > 1`。用户认可图表价值,要求:

1. **视图切换**:同一区域可在多种图表间切换,而非只有燃尽。**用户拍板(2026-10-19):成员负载图本票做(方案 a)**。
2. **空态可见**(本会话发现):V22 刚开始时 `bd.actual.length ≤ 1`,图表整个不渲染、无任何提示,用户以为功能丢了。

### 范围

- sprint 页头部下方图表区改为可切换视图(纯前端,零 API 变更):
  - **燃尽**(现状迁移):remaining vs ideal 折线;
  - **状态分布**:当前 sprint items 按 status 计数的横向条形图(数据来自页内已有的 `items`,不发新请求);
  - **成员负载**:按 assignee 计数的横向条形图(同数据源;单人项目下即一张全是自己的图,用户明知仍要做,为将来多人即用)。
- 空态:燃尢单点/零点时显示一行说明文案(「Sprint 开始次日才有燃尽数据」),不再整块消失;状态分布在 items 为空时沿用页内已有空态,不重复提示。
- 切换器:ToggleButtonGroup(小号),默认选中燃尽;选择不持久化(会话级 state 即可)。

### 非目标

- 不加新 metrics 端点、不做历史状态分布(需要新快照数据,另票);
- 不做图表配置面板/持久化用户偏好;
- 不动 sidebar 不同步问题(YPJ-74);
- 不引入图表库依赖(沿用 `@mui/x-charts`)。

## 详细设计(代码级修改计划)

只改一个文件:`web/app/p/[key]/sprint/[id]/page.tsx`

1. **新增状态**:`const [chartView, setChartView] = useState<"burndown" | "byStatus">("burndown")`。
2. **状态分布数据**(页内派生,useMemo):
   ```ts
   const byStatus = useMemo(
     () => (items ?? []).map(i => ({ name: i.status, count: 1 }))  // 聚合为 {status, count}
       .reduce(...按 status 计数...),
     [items],
   );
   ```
   渲染 `@mui/x-charts` 的 `BarChart`(layout="bar",横向);status 顺序按项目 `statuses`(页内已有该请求)排列,复用现有 status 色(若有)或默认色板。
3. **切换器**:图表区顶部右侧 `ToggleButtonGroup size="small" exclusive`,两个选项文案走 `texts.ts`(中文优先:`燃尽` / `状态分布`;`web/lib/texts.ts` 的 flow 段补 key)。
4. **空态改造**:
   - `chartView === "burndown"` 且 `!(bd && bd.actual.length > 1)` → 渲染 `<Typography variant="caption">Sprint 开始次日生成燃尽数据,当前点数不足。</Typography>`;
   - 状态分布在 `items.length === 0` 时不渲染(页内已有整体空态)。
5. **文案外置**:全部新增中文文案进 `web/lib/texts.ts`,不硬编码在组件里(领域硬规则)。

### 否决的备选

- **吞吐量/历史状态分布图**:需要新快照表或新端点,超本票范围(记入非目标);
- **Recharts 等新依赖**:违反「不加依赖」约定,x-charts 够用;
- **图表偏好持久化(localStorage)**:无人要求,yagni。

## 手工测试用例(用户 build/debug 阶段执行)

1. 打开 V21(已完成,数据足)→ 默认显示燃尽图,切「状态分布」出现条形图,切回正常;
2. 打开 V22(刚开始)→ 燃尽位显示空态文案,不再是空白;切「状态分布」有 items 计数;
3. 空 sprint → 两个视图都不出图表错误,整页空态文案仍在;
4. 切换过程无控制台报错;页面其它功能(拉入 item、状态迁移)不受影响。

## 验收(对照票)

- [ ] 图表区可在燃尽/状态分布间切换
- [ ] 燃尽数据不足时有可见空态
- [ ] 新文案全部外置 texts.ts
- [ ] `npx next build` 绿;web 相关 e2e 不红(sprint 页现有用例不回归)

## As-built

实现与 spec 一致:单文件改动(page.tsx)+ texts.ts 文案段。微调:状态分布与成员负载合并为单个 `ChartBy` 组件(view 参数派生行数据),未分两个组件;切换器置于图表区上方右侧独立行。手工 4 例过(用户 build/debug),e2e 全量过。美观度欠账已记 PR,归全站 UI 调优。
