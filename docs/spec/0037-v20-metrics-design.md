# 0037 — V20 Flow Metrics 整体设计(指标与图表)

> V20 伞设计。票:YPJ-45(指标 API)/ YPJ-46(图表面板)/ YPJ-54(scope 留痕 XS,待立)。
> 需求源:PRD §1 + §1.5(Tier 分级);对标材料 Obsidian《Metrics Chart》29 节(已迁 vault,repo 不存)。
> 数据基座:YPJ-44 已交付 `item_status_history`(spec 0032/V31)——任意路径迁移留痕、id 级引用(状态改名不破数)。

## 一、范围与原则

**做什么**:项目级 Flow Metrics 只读 API(YPJ-45)+ 项目 Flow 页图表与 sprint burndown(YPJ-46)+ sprint 成员变更留痕与 scope/reopen 指标(YPJ-54,待批)。

**设计约束**(承 PRD §1.5 /《Metrics Chart》§29):

1. metrics 回答「系统哪里有问题」,不做 KPI/个人排行;**每个指标端点响应自带口径说明**(中文,前端 info 可见)。
2. 对 group type 中性——不 hardcode sprint;sprint 维度指标一律以 `groupId` 参数表达。
3. 纯 SQL 聚合,不建视图/物化(数据量上来再议,见 §六)。
4. 本产品无 story points——velocity/ burnt 一律 **count 口径**(口径注明,防 Point Inflation 讨论误植)。

**非目标**(Tier 3 挂账不进 V20):Forecasting/Monte Carlo、Quality 面板(DORA/缺陷分布)、Blocked/Dependency 指标、跨项目对比、导出、实时推送。

## 二、指标清单与口径(Tier 1 + Tier 2)

语义锚点(从现有 schema 推导,不新增列):

- **start 状态** = `status.is_start` 显式列(V3 起;默认 workflow 的 To Do;口径写入响应)。
- **完成时刻** = item 首次进入任一 final 状态的 history 时刻(重开后再完成不重复计);**重开** = final → 非 final 的 history 行。

| # | 指标 | 口径(写进响应) | 数据源 | 归属 |
|---|---|---|---|---|
| M1 | Workload / WIP 分布 | backlog = 现处 start 状态数;WIP = 非 start 非 final;done = final。按状态 × assignee × type 拆分 | item ⋈ status 现态直查 | 45 |
| M2 | Throughput | 窗口内按 bucket(week 默认/day)首次进入 final 的 item 数;可选 `groupId` 过滤成员 | item_status_history | 45 |
| M3 | Cycle Time | 首次离开 start 状态 → 首次进入 final;从未离开 start 者无 cycle(排除并计数) | history 时点差 | 45 |
| M4 | Lead Time | item.created_at → 首次进入 final | item + history | 45 |
| M5 | 百分位 P50/85/95 | 对窗口内已完成 item 的 cycle/lead 分布取百分位(拒绝平均数) | 同上,内存计算 | 45 |
| M6 | CFD | 各状态存量按天重放:history 逐行推进 item 所处状态,日界快照;窗口默认 90d、上限 365d | history 重放 | 45 |
| M7 | Aging WIP / Bottleneck | 每 WIP item:进入当前状态时刻(最后一条 history,无则 created_at)+ age 天数;queue width = 各非 final 状态在计数 | history 尾行 | 45 |
| M8 | Burndown(sprint) | remaining(t) = 当前成员数 − t 前已完成数;理想线 start→end 线性;**口径注明:成员中途变动不回放(等 Y54 留痕)** | group member + history | 45 |
| M9 | Burnup(sprint) | done(t) 同上累计;scope(t) = created_at ≤ t 的成员累计——**离组不可回填,t=now 起精确(Y54 后转准)** | group member.created_at + history | 45(精确化随 54) |
| M10 | Velocity | 项目各 sprint(有 start/end 日期者)内完成 item 数分布;count 口径;carry-over item 计入完成时所在 sprint | history + group member | 45 |
| M11 | Scope Change | sprint 期间成员加/离次数与净变(Original → Final scope) | **Y54 新留痕表** | 54 |
| M12 | Reopen Rate | 重开 item 数 / 完成过 item 数(final→非 final 行) | history(已有,无 gap) | 54 |

已知口径妥协(M8/M9/M10):`item_group_member` 只有 created_at,离组即删行——中途换人/挪票的历史不可回放;Y54 留痕落地前,相关图配口径说明,不装准。

## 三、图表清单(YPJ-46)

**图库选型:`@mui/x-charts`**(同生态、MIT、MUI 7 兼容;bar/line/area/scatter 全覆盖;具体版本票内核对 peer deps)。

**项目 Flow 页**(`web/app/p/[key]/flow/page.tsx`,sidebar 与 gantt/table 同级入口):

| 图 | 形态 | 数据(M→图) |
|---|---|---|
| WIP 分布 | 堆叠条形(assignee × 状态) | M1.byAssignee |
| Throughput | 直方(按周) | M2 |
| Cycle/Lead | 散点 + P50/85/95 参考线(两组 series) | M3/M4/M5 |
| CFD | 堆叠面积图(各状态存量 × 日) | M6 |
| Aging / Bottleneck | 表(item/状态/age/进入时刻)+ queue width 内联条 | M7 |
| Velocity | 条形(每 sprint 完成数) | M10 |
| Burnup | 折线(done 累计 + scope 线) | M9 |

**Sprint 详情页**:`/p/[key]/sprint/[id]` 加 burndown 卡(剩余 vs 理想线,LineChart)——sprint 页保持表格+图、无看板(与 YPJ-41 一致)。

**通用件**:时间范围选择器(默认 90d,30/90/180/365);每图卡片标题带 info icon,tooltip 直接渲染端点返回的口径文案。

## 四、实现设计

**API 形状**(全部只读 GET,挂 `/api/projects/{key}/metrics/*`(key = 项目 key,沿 sprint 切片惯例);OpenAPI + api.http 同步新增区块,负例区补坏 bucket/from>to):

```text
GET /metrics/workload                              → M1(含 byStatus/byAssignee/byType)
GET /metrics/throughput?from&to&bucket=week|day&groupId?   → M2
GET /metrics/flow-times?from&to                    → M3/M4/M5(items 散点 + percentiles)
GET /metrics/cfd?from&to                           → M6(statuses[] + series[])
GET /metrics/aging                                 → M7(items + queueWidth)
GET /metrics/burndown?groupId                      → M8(ideal[] + actual[])
GET /metrics/burnup?groupId                        → M9(done[] + scope[])
GET /metrics/velocity                              → M10(sprints[])
```

每个响应带 `definitions`(口径字段 map,中文);itemId 对外仍走公共 id,永不暴露内部数字 id(ADR-0004 惯例)。

**后端落点**:api 模块新垂直切片 `metrics/`(controller + service + 线型 DTO 同包);聚合 SQL 走 Spring Data JDBC `@Query` 自定义查询(persistence 仓储接口,仓内首个聚合投影;**不引入第二套数据访问 API**——ADR-0004 只授权 Spring Data JDBC,不建 JPA/物化)。domain 模块零涉及(引擎无关,domain.puml 不动)。

**前端落点**:`flow/page.tsx` + `web/lib/` 加 metrics API client;导航入 sidebar(sprint/gantt/table 同级);图组件不做投机抽象,图多了再提。

**Y54 留痕方向**(其自身 spec 细化):新表 `item_group_member_history`(added/removed 事件或 added_at+removed_at 双列,迁移票内定);写入点 = 加组/离组全部服务路径;存量迁移只能回填 added_at(created_at 已有),removed 不可回填——口径注明;**动 schema 后同步 entities.puml**。

## 五、票务拆分与顺序(待批)

1. **YPJ-45**(指标 API):Tier 1 六组 + burndown/burnup/velocity 端点(M8/M9/M10)。**票面补一行**:velocity 聚合 + burndown/burnup 端点(原票面只列四组)。此为 PRD §1.5「45 不动」与 Tier 2 表「票面各补一行」矛盾的裁决:velocity 数据侧归 45,否则 46 无源。
2. **YPJ-46**(图表面板):§三全部图表;依赖 45。
3. **YPJ-54**(XS,新立):留痕 + M11 scope-change + M12 reopen + burnup scope 线转精确。
4. 每票开工按惯例出三件套增量节(spec 0038 起);收尾跑全量验证;`gradle build` 绿 + 临时端口 bootRun 冒烟。

## 六、风险与边界

- **口径歧义集中区**:start 推导、WIP 定义、完成去重、周桶时界(UTC)——全部落响应 definitions,宁可啰嗦不装准。
- **payload**:CFD/散点随项目规模线性涨——窗口上限 365d 兜底;超限再议物化/预聚合(挂账,不预做)。
- **velocity 误用**:count 口径 + 页面明示「非生产力 KPI」(《Metrics Chart》§29 原则)。
- **成员历史缺口**:M8/M9/M10 的已知妥协,见 §二尾注;Y54 是修复点不是阻塞点。
