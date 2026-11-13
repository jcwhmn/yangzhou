# 0122 — YPJ-178:type 维度图表:Flow 页渲染 workload byType

> 日期:2026-11-14。用户期望:有 item type 相关 chart。后端 `byType` 数据已备(Y45),前端从未渲染——本票补齐渲染,与 YPJ-177(type 动态化)解耦可先行。

## ① 需求

**现状实锤**:`GET /api/projects/{key}/metrics/workload` 已返回 `byType: [{type, count}]`(MetricsService L297-301,按 item.type 计数);Flow 页 `Workload` 前端类型只声明了 `byStatus`/`byAssignee`,page.tsx 全文无 byType 引用。仓图组件统一 `@mui/x-charts`(BarChart/LineChart/ScatterChart),卡片 `Card2` + 空态 `Empty`。

**范围**:Flow 页新增「按 Type」柱图卡片 + texts 文案;仅前端两文件。**非目标**:type 动态化(YPJ-177,落地后本图自动随定义显示)、后端任何改动、其它页复用。

**真值表**(数据 × 行为):

| 数据 | 行为 |
|---|---|
| 项目无 item(byType 空) | Empty 空态文案,不渲染图 |
| 有 item(任意 type 分布) | BarChart:x 轴=type 名,单系列=count |

**页面示意图**(Flow 页两列网格,WIP 卡下追加,ASCII):

```text
┌─ WIP(按负责人、状态堆叠)─────┬─ 吞吐(每日完成)──────┐
│  ▇▇▇▇ 堆叠柱图               │  ▇▇▇ 每日柱          │
│                              │                      │
├─ 按 Type(数量)─────────────┼─ (既有其余图)────────┤
│  task   ▇▇▇▇▇ 5              │                      │
│  bug    ▇▇ 2                 │                      │
│  story  ▇▇▇ 3                │                      │
└──────────────────────────────┴──────────────────────┘
```

## ② 详细设计

**文件 1:`web/app/p/[key]/flow/page.tsx`**(单文件三处)

1. `Workload` 类型补字段:`byType: { type: string; count: number }[]`
2. 新增 `typeSeries` useMemo(与 wipSeries 同型):
   ```tsx
   const typeSeries = useMemo(
     () => ({
       xAxis: [{ scaleType: "band" as const, data: (data.workload?.byType ?? []).map((x) => x.type) }],
       series: [{ label: t.flow.typeCount, data: (data.workload?.byType ?? []).map((x) => x.count) }],
     }),
     [data.workload],
   );
   ```
3. 渲染:WIP `Card2` 之后插一张 `Card2 title={t.flow.byType}`,空判 `(data.workload?.byType ?? []).length === 0` → `Empty`,否则 `<BarChart height={280} {...typeSeries} />`

**文件 2:`web/lib/texts.ts`** flow 段两键:`byType: "按 Type(数量)"`、`typeCount: "数量"`(zh 对象;en 对象若有则同步)。

**否决备选**:饼图(仓内 x-charts 无 Pie 用例,柱图与既有图统一);后端加独立聚合端点(byType 已在 workload,零必要)。

**测试设计**:
- 后端零改动,无新测试;既有 metrics 单测覆盖 byType 计算
- e2e(flow.spec.ts 新例「byType 图:双 type 计数入图」):API 预置 bug+task 各一 item → goto flow → 断言「按 Type(数量)」卡题 + x 轴 type 名可见 + svg 存在(拦 400/空数据,与首例同口径)

## ③ 手工测试用例(用户 build/debug,dev 3001)

1. 项目建 ≥3 种 type 的 item(task/bug/story 各若干)→ Flow 页:出现「按 Type」卡,柱数=type 数,高度=计数;与 WIP 卡同宽列
2. 只有一种 type:单柱;新建空项目(0 item):显示空态文案,无图
3. type 动态化联动预演:改 item type 后刷新 Flow,计数随迁

## 四、As-built(2026-11-14)

- 落地与设计 1:1:`Workload.byType` 类型补齐、`typeSeries` useMemo、WIP 卡后追加「按 Type(数量)」Card2(空态 Empty / BarChart 280);texts 两键;否决项维持(饼图/新端点未做)
- e2e 新例一役一修:首跑 strict mode 红于 svg 断言(卡内天然双 svg=图例标记+图层,报错内容即证明图已渲染)——取 `.first()` 并注释缘由;非产品 bug
- 验证:prettier ✓ / tsc ✓;**用户全量 e2e + 手工 3 例全过**(dev 3001)
- 过程无台账条目(唯一红为例内断言修正,见上)
