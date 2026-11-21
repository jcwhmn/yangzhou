# spec 0153 — YPJ-249 甘特页重构:单文件拆组件/hooks/utils(纯结构零语义)

> 票:YPJ-249(8h,V32 页面重构 sprint;In Progress,分支 `refactor/YPJ-249-gantt-page-split`)。
> 性质:**纯搬家零语义变化**——SRS v4 真值表、245 六项语义、所有 testid、行为闸 = 全量 e2e 121 例绿。

## 一、需求

`web/app/p/[key]/gantt/page.tsx` 1702 行(全站第二大页面),拆为可单独验证的小控件 + 一个页面入口:

- 入口 ≤~300 行;最大单文件 <400 行
- components/ hooks/ utils/ types.ts 分层;每个文件职责单一、可独立定位
- 行为与重构前逐像素一致(今日线/徽标三档/折叠组/过滤包络/借锚排序/缩放/工况恢复)

**V32 页面重构 sprint 背景**(2026-11-21 用户拍板):全站 ≥800 行页面共 5 张(2786/1702/1033/913/887),全部立票进 V32,甘特页(249)先行;250 detail 右 sidebar 改挂 detail 页重构(251)之后。

### 非目标
- 拖拽编辑/虚拟列表等新功能;246 视觉调整;任何渲染语义变更;detail/sprint/board/table 页(各有独立票 251–254)。

## 二、详细设计(代码级)

### 现状结构(段图)

| 行区间 | 内容 |
|---|---|
| L1–30 | imports |
| L31–52 | `type GanttRow` |
| L53–150 | 常量(DAY/标题列宽/缩放上限/Tick/HeaderRow/EST_UNIT_SHORT) |
| L152–1702 | `GanttPage()` 单组件 ~1550 行,其中:状态群 L154–235;load+scope 选项 L237–286;借锚 L288–306;**axis useMemo L307–466(159 行)**;容器测宽 L467–476;工况存取 L477–508;缩放锚点 L509–538;视点日/今日线滚动 L539–586;日窗预设 L587–631;x()/条宽 L632–654;summaryOf 包络 L655–712;descendantsOf L713–726;hoverCard L727–786;toggle/折叠 L787–842;**行渲染器+主 JSX L843–1675(~830 行)**;RowTitle L1676–1702 |

### 目标形态

Next.js 路由要求 `page.tsx` 固定在原路径 → 入口即 `page.tsx`(不设 index.tsx 转发层,与用户草图同构、少一层 shim;用户已示「名字无关」):

```text
web/app/p/[key]/gantt/
├── page.tsx               ← 入口:组装 + 数据获取 + 顶层状态(≤~300 行)
├── GanttChart.tsx         ← 核心画布:消费 axis,行布局/分组序列/面板(<400 行)
├── components/
│   ├── TitleColumn.tsx    ← 标题列 + 拖拽分割条(L1676–1702 RowTitle 并入)
│   ├── TimelineHeader.tsx ← 多粒度刻度头(月/周/日/时)
│   ├── GanttRow.tsx       ← 单行壳(标题+条区布局;renderRow 拆入各条组件)
│   ├── SummaryBar.tsx     ← 父行汇总条
│   ├── PlanBar.tsx        ← 计划条/计划点/菱形
│   ├── ActualBar.tsx      ← 实际条/完成点
│   ├── ProgressOverlay.tsx← 黄覆盖 + 清单遮罩
│   ├── OffsetBadge.tsx    ← 偏移徽标三档
│   ├── TodayLine.tsx
│   ├── UnscheduledGroup.tsx
│   └── HoverCard.tsx
├── hooks/
│   ├── useGanttData.ts        ← load/rows/scope 二级选项/借锚(rowById、groupStartOf)
│   ├── useTimelineScale.ts    ← axis useMemo + zoom 锚点 + Ctrl 滚轮 + 视点日/今日线滚动/日窗预设
│   ├── useEnvelope.ts         ← summaryOf / descendantsOf / hiddenByCollapse / isCompletedRow / 未排期判定
│   └── useGanttInteraction.ts ← 工况 saveView/restore + 标题列宽拖拽 + 容器测宽
├── utils/
│   ├── timeCalculations.ts← x() 坐标 / 条宽含当天 / estimate 铺算 due / ms 时刻版 x
│   ├── barGeometry.ts     ← 条 top/left/width 常量几何(计划 5 / 实际 24 等)
│   └── sorting.ts         ← 渲染序列(mainRows/completedRows/unscheduled 分组与排序)
└── types.ts               ← GanttRow/Tick/HeaderRow/常量
```

### 状态与边界拍板

- **入口持有**:数据(rows/wd)、zoom/center/scope、titleW/splitting、collapsed/showCompleted/showUnsaved 会话态——即现 L154–187 原样,不借重构重划状态。
- hooks 只做**行为封装不持有跨文件状态**;axis 是纯派生 → `useTimelineScale` 返回 axis+缩放动作;summaryOf 系纯函数 → `useEnvelope` 以 useMemo/闭包提供,依赖经参数注入。
- renderRow 的 JSX 按「行壳/条/徽标/hover」拆入 components;`renderRow` 本体保留在 GanttChart(组装点),行内条形子块移出。
- **testid 全部原样保留**(`gantt-completed-group`/`gantt-hover-plan-N` 等);props 命名即拆即定,不加转换层。
- 常量/类型原值搬运,不改名不改值(改了就不是纯搬家)。

### 迁移顺序(每步编译绿,绿点即 commit)

1. types.ts + utils/(纯叶子,零依赖)
2. hooks/(依赖 utils)
3. components/(依赖 hooks 类型)
4. GanttChart.tsx(消费 1–3)
5. page.tsx 收口(删旧巨石,入口 ≤~300)
6. `npm run format` + 全量 e2e + 手测冒烟

回退策略:每绿点一 commit;任何语义偏差 → revert 该步重切,不带病前进。

### 否决的备选

- `GanttPage/index.tsx` + page.tsx 转发 shim——Next 路由 page.tsx 必在原路径,加 shim 徒增一层,弃。
- 一步到位多文件同拆后统一调试——编译错误成堆难定位,自底向上每步绿,弃。
- renderRow 整体搬进 GanttRow 组件——renderRow 闭包捕获 axis/折叠/hover 等十余项上下文,整搬需巨量 prop 钻孔;行壳进组件、条形子块拆出、组装留 GanttChart,弃深拆。

## 三、手工测试用例(dev 3001,重构分支;用户 build/debug)

前置:dev 容器重建为重构镜像后对照(prod 3000 = 重构前基线)。
- A. 加载:树/汇总条/今日线/徽标三档/折叠组与 prod 像素级一致;控制台零错误。
- B. 缩放:± 按钮/Ctrl+滚轮锚点缩放/日窗预设 1·7·14·30/今日居中;刷新后工况(sessionStorage)恢复。
- C. 标题列:拖拽调宽+localStorage 记忆+双击重置。
- D. 范围过滤:全部/看板/sprint/group/milestone/release 切换;过滤后父包络与排序按可见子孙(§10.5)。
- E. 已完成折叠组:默认收起/展开/收起;未排期组置尾不变。
- F. hover:条上悬停卡(起止时分/工时/返工行「上次完成」)。
- G. e2e 全量 121/121 绿(agent 本地)+ `npm run format` 绿。

### 自动化测试清单
- 既有全量 e2e 即回归闸(121 例,gantt.spec/overview.spec/board 覆盖语义);**不新增测试**——纯重构,新测试=测试重构前不存在的行为。
- 单元/集成不涉(纯前端)。
