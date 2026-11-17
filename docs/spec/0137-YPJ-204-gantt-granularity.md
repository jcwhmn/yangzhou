# 0137 — YPJ-204 甘特图改进:时间线粒度与数据源范围

> 草稿(开工三件套阶段,待用户过目)。前置:Release 主线(191/200~203)已完成。

## ⚠ 待用户拍板(动工前必须先答)

### Q1 数据源范围:要不要支持按看板(board)/视图过滤?

现状:甘特读 item 全集(树序,无过滤)。票面问「要不要按看板/视图过滤」。

- **方案 A(推荐,本票默认)**:保持 item 全集,不加过滤。理由:甘特的用途是排期总览,全集是正确语义;看板过滤是视图层关切,PRD 排期域后置。加过滤 = 新增端点参数 + 前端选择器,面积不小。
- 方案 B:加 `?board=` 参数,按看板成员过滤。面积:+1 参数 + repository 查询 + 前端下拉。

### Q2 「日 24 线」小时粒度:数据模型不支持,怎么办?

现状:items 表 `start_date`/`due_date` 是 **DATE(无时分)**——`estimate 2h` 的任务在纯日期模型里没有小时位置,24 线网格即使画出来,条也只能落在「日」上。这是**数据变更/schema 问题**,不属于本票可自主决定范围。

- **方案 A(推荐)**:本票不做小时粒度,做**粒度切换(月/周/日)**——纯前端渲染层,`LocalDate` 语义不动;2h 任务在日粒度下画最小宽条(~1.2%),配合 estimate 标签提示。**小时粒度若真需要,另立票做 DATE→TIMESTAMP 迁移**(涉及 schema + 全下游序列化,面积大)。
- 方案 B:本票一并做 DATE→TIMESTAMP 迁移 + 小时粒度。面积:迁移 V66 + Entity/DTO/API 契约 + 全部排期相关 UI/e2e,估计 2~3 张票的量,建议拆开。

## ① 需求

- 甘特时间轴支持粒度切换:月 / 周 / 日(刻度密度变化,条位置不变,均百分比定位,天然适配)。
- 排期条渲染依赖:被依赖未完成的 item(barred/blocked)在行上显示标记(如 🔗 图标或色边)。
- estimate 可见性:2h 级任务在甘特上不可见的问题——条加最小宽 + 行标题区显示 estimate(如 `2h`)。
- 范围内:以上全部为只读展示,不改排期编辑。
- 非目标:小时粒度(见 Q2)、拖拽调排期、看板过滤(除非 Q1 选 B)、关键路径计算。

## ② 详细设计

**后端**(小改,`ProjectService.gantt`):
- `GanttRow` 增 3 字段:`blocked`(Boolean,复用 `DependencyService.blockedItemObjectIds`)、`estimateValue`(Double?)、`estimateUnit`(String?)。一次遍历取 blocked 集合,行构造时查集合,零新查询模式。
- 契约变更为**纯增量**(新增可空/布尔字段),不破坏现有客户端。
- 同步 `backend/script/api.http` gantt 节;`GanttApiTest` 补断言(blocked=true / estimate 字段)。

**前端**(`web/app/p/[key]/gantt/page.tsx`):
- 粒度切换:`Button` 组(月/周/日),切换只改 `axis.ticks` 生成逻辑(月=每月 1 号,周=每周一,日=每日;跨度大时日粒度刻度隔 N 个显示避免重叠)。
- 行标题区:estimate 非空时显示 `<Chip label="2h">`;blocked=true 时条/菱形加左侧 `borderLeft` 或 🔗 Chip。
- 文案外置进 `web/lib/texts.ts`(用户可见文案中文优先)。
- e2e:`web/e2e/board.spec.ts` 或新 `gantt.spec.ts` 补粒度切换与 blocked 标记各 1 例。

**否决的备选**:引入甘特库(mui-x charts / d3)——现有自绘实现 ~350 行且已稳,换库面积大收益低;Canvas 重绘——同上。

**类图**:不动 domain/persistence 实体(`GanttRow` 是 API wire DTO),无 puml 同步义务。

## ③ 手工测试用例(用户 build/debug 阶段执行)

1. 打开 `/p/YPJ/gantt`:默认月刻度;切「周」刻度变每周一;切「日」刻度变逐日。
2. 给某 item 建一条指向未完成 item 的依赖 → 该 item 行出现 blocked 标记;把被依赖 item 移 Done → 标记消失。
3. 给某 item 设 estimate=2h、start=due=同一天 → 甘特上仍可见(最小宽条 + `2h` 标签)。
4. 折叠父 item、未排期组、今日线、超期红色——回归不受影响。

## 验收清单

- [ ] 粒度切换(月/周/日)可用且刻度符合历法
- [ ] blocked 标记随依赖状态变化
- [ ] estimate 小任务可见
- [ ] `gradle build` / `integrationTest` 绿;e2e 相关用例绿;prettier/ktlint 绿
- [ ] api.http gantt 节更新;As-built 补本 spec
