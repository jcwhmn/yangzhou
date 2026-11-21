# spec 0155 — YPJ-252 sprint 详情页重构:1034 行拆 hooks/组件(纯结构零语义)

> 状态:三件套待用户过目,过目后才编码。分支 `refactor/YPJ-252-sprint-page-split`(基线 main@1e098e9)。节奏 = 新节奏(2026-11-21 拍板):先设计 → 停等 review → 一次 coding → 停等 e2e;编码期间 tsc+prettier 绿点 commit 保留。

## 一、需求

`web/app/p/[key]/sprint/[id]/page.tsx` **1034 行**:头部(名/状态/日期/父 epic 选择器/feas rollup)+ 状态动作按钮群 + 图表区(burndown/按状态/按负责人)+ 成员表 + 三弹窗(拉入/确认/处置)。拆 hooks/组件 + 页面入口(同 249/251 形态家族)。

### 范围
- 纯搬家零语义变化:既有行为不变;**全页 0 testid,重构后仍 0**(已核对)
- 行为闸 = 全量 e2e 绿(用户跑)+ 手测冒烟
- 每绿点 tsc+prettier 绿 + 就地 commit;**停点只有两个**:本设计过目后、编码完成 e2e 前

### 非目标
- 任何字段/交互语义变更;加载时机变更;30s 定时器迁移(见拍板 5 归属断言)

## 二、详细设计(代码级)

### 现状段图(基线 main@1e098e9)

| 行区间 | 内容 |
|---|---|
| L1-50 | imports |
| L51-96 | module 类型(Sprint/Item/Status/TypeWorkflow/Candidate) |
| L97-144 | 状态群(~15 useState + sprintVer ref) |
| L145-176 | 首屏加载 effect(Promise.all ×5 + workflow 子拉) |
| L178-184 | 父 epic 候选 effect(parentSel) |
| L186-212 | changeStatus / reparent |
| L214-277 | 开始/完成决策群(startSprint/doStart/completeEmpty/completeSprint/doComplete+disp 计算) |
| L279-335 | confirmDisposition(0110 完成+处置同事务) |
| L337-355 | targetLabel / targetGroupName |
| L357-379 | doBacklogAll |
| L381-433 | edgeFn/startFnOf/finalFnOf + actionLabel/actionClick/doReopen |
| L435-446 | burndown effect(bd) |
| L448-490 | openAdd / applyMembership(拉入/退回) |
| L492-505 | gone 分支 return |
| L506-994 | 主 return JSX:头部 L521-649(name/chip/日期/reparent/rollup/动作按钮/拉入钮/完成横幅/空态)、图表切换 L651-668、burndown L669-701、ChartBy 用点 L702-709、成员表 L711-781、拉入弹窗 L782-838、确认弹窗 L840-875、处置弹窗 L877-989 |
| L996-1033 | ChartBy(module 级组件,纯函数渲染) |

### 目标形态

```text
web/app/p/[key]/sprint/[id]/
├── page.tsx                    ← 入口:路由+装配+动作编排+弹窗态(预估 ~350-400)
├── hooks/
│   └── useSprintData.ts        ← 数据球:states + 首屏/父候选 effects + fetchItems(~130)
├── components/
│   ├── SprintHeader.tsx        ← L521-649 头部块(~135)
│   ├── SprintChartSection.tsx  ← 图表切换+burndown+ChartBy 组装,chartView/bd 内置(~80)
│   ├── SprintItemsTable.tsx    ← L711-781 成员表(~85)
│   ├── AddMembersDialog.tsx    ← 拉入弹窗(~65)
│   ├── ConfirmFlowDialog.tsx   ← 开始/重开/完成确认弹窗(~40)
│   ├── DispositionDialog.tsx   ← 0110 处置弹窗(~125)
│   └── ChartBy.tsx             ← L996-1033 原样搬出(~40)
└── types.ts                    ← module 类型(~50)
```

### 设计拍板(与 251 先例的差异,需过目)

1. **数据层 hooks 化(与 251 相反)**:251 八页签互不共享故禁 hook;本页 = 单屏单一数据球——sprint/items/statuses/groups/wf/l1Key/typeName/parentSel 被头部/表格/三弹窗全部消费,共享为实。`useSprintData` 收 states+首屏/父候选 effects+fetchItems+sprintVer(249 useGanttData 同型),非投机抽象。
2. **动作层留 page**:changeStatus/reparent/开始完成决策群/confirmDisposition/doBacklogAll/edgeFn 三件套/openAdd/applyMembership = 跨实体编排(0110 同事务、决策树),留在 page;弹窗组件纯展示,props 下发、回调上行(251 拍板②式)。
3. **SprintChartSection 自包含**:chartView/bd 状态 + burndown fetch effect 随组件内置——组件常挂载(mount 即拉,与现 effect 时序一致,拍板②不违);本页唯一真正的区块私有态。
4. **disp/addOpen/selected/confirmFlow 留 page**:被 page 动作群读写(doComplete 产出 disp、confirmDisposition/doBacklogAll 消费、openAdd 产出 candidates/selected);dialogs 收 props+callbacks。selected(勾选集)虽仅弹窗内变异,但由 openAdd 初始化,内置需另设初值通道,不做。
5. **「30s 自动刷新」归属断言(请复核)**:全站 setInterval 仅三处——overview/board 首页与 notifications-store;**本页无定时器**。本页职责 = `sprints-changed` 广播(reparent L207 手动播 + sprintAction 内部播,layout sidebar 监听)。手测项按此解读:sidebar 即时重拉链路 + board/overview 30s 刷新不受影响。
6. **「S0 分支」解读断言(请复核)**:spec 0097 无字面 S0 定义;按代码实况取「空态(无 item 文案 L644-649)+ gone(不存在 id L492-505)」两分支入手测。若另有所指请指正。
7. **真值表/示意图不适用**:零新交互,e2e 全量 + 手测冒烟为闸(0153/0154 同例)。
8. **testid 全保留(= 零个)**:重构前后均无 testid,不新增不删除。

### 预估行数表(以实拆为准;单文件 <400 红线随时核)

| 件 | 内容 | 预估 |
|---|---|---|
| types.ts | module 类型 | ~50 |
| hooks/useSprintData.ts | 数据球 | ~130 |
| SprintHeader.tsx | 头部块 | ~135 |
| SprintChartSection.tsx | 图表区(chartView/bd 内置) | ~80 |
| SprintItemsTable.tsx | 成员表 | ~85 |
| AddMembersDialog.tsx | 拉入弹窗 | ~65 |
| ConfirmFlowDialog.tsx | 确认弹窗 | ~40 |
| DispositionDialog.tsx | 处置弹窗 | ~125 |
| ChartBy.tsx | 图表组件原样搬 | ~40 |
| page.tsx | 入口+动作编排 | ~350-400(如实记,不硬拆) |

> page 收口不达 ≤~300 的偏差如实记 As-built(拍板 2 的编排体量是主因;251 同例先河)。

### 否决的备选

- 全自包含(弹窗内置自己的动作+fetch):处置事务跨 createSprint/sprintAction/工作流出边三域,注入面 > 收益,弃。
- 处置/成员动作也收进 useSprintData:数据与编排混居 = 巨石 hook,弃(251 拍板①同理)。
- 一步到位同拆后统一调试:249/251 血训,自底向上每步绿,弃。

## 三、手测冒烟(dev 3001,重构分支;用户 build/debug;基线 = prod 3000 对照)

- [ ] A. 加载/渲染:头部(名/状态 chip/日期/父 epic 选择器悬空占位/rollup 信号)与基线一致;空态文案;不存在 id → gone 页「返回上一页」
- [ ] B. 状态动作:一键激活(已有进行中同类型组 → 确认弹窗「仍要开始」)/完成(全 Done → 确认弹窗;有非终态 → 直开处置)/重开双闸
- [ ] C. 处置流:目标组决策树(转入 active/激活 planned/新建「N 号」组继承父 epic)+ 行内覆写回 Backlog + 摘要行与落库一致;失败整体回滚弹窗保留可重试
- [ ] D. 拉入/退回:多选勾选(本组成员默认全选)/「完成」逐条 PUT/表格刷新;已完成的组无「拉入」入口
- [ ] E. 图表:burndown(空态文案)/按状态/按负责人三视图切换
- [ ] F. 成员表:AssignPopover 行内改指派后表格刷新;`?from=sprint-N` 回跳链路
- [ ] G. 广播链路:本页改状态/改父 epic → sidebar 即时重拉;board/overview 的 30s 定时刷新不受影响(定时器不在本页,拍板 5)
- [ ] H. e2e 全量绿(用户跑)+ `npm run format` 绿


## 四、As-built(PR #242 squash 7f9485f 合入后补,2026-11-21)

### 交付形态

```text
web/app/p/[key]/sprint/[id]/  共 1396 行(原 page 1034 单文件)
├── page.tsx               451 行(入口:路由解析/gone+空态/处置·成员·状态动作编排/弹窗胶水态)
├── hooks/
│   └── useSprintData.ts   92 行(states + 首屏/父候选 effects;error/gone 随 load 归 hook)
├── types.ts               50 行(Disp/图表视图等 module 类型)
└── components/ ×7
    ├── SprintHeader.tsx        203(rollup/文案内置;actionLabel 随迁,+typeName prop)
    ├── SprintChartSection.tsx  120(chartView + bd/burndown fetch 内置,常挂载 mount 即拉,时序不变)
    ├── DispositionDialog.tsx   174(决策树;disp 态留 page 走胶水回调,targetLabel/Name 派生内置)
    ├── SprintItemsTable.tsx    92(onAssigneeChanged 回调上行)
    ├── ConfirmFlowDialog.tsx   87(confirmFlow 态留 page)
    ├── AddMembersDialog.tsx    82(addOpen/selected 态留 page)
    └── ChartBy.tsx             45(图表组件原样搬出)
```

### As-built 偏差(目标形态 → 实拆)

- **page 451 行 > 预估 ~350-400**:处置流(决策树编排)+ 成员拉入/退回动作群留 page 是主因(拍板②「编排留 page」的直接体量代价);251 同例先河,如实记偏差,不硬拆。
- **hook 未抽 fetchItems**:3 处 reload 保持动作体内联——逐字节零改动优先于动作归仓(抽到 hook 需改动作签名与调用点,违背纯搬家约束)。
- **error/gone 归 hook**:load() catch 写 error、不存在 id 写 gone;page 仅解构 setError 引用(动作内 setError 调用零改动)。spec 文本未细分,实拆按数据归属断言归 hook。
- **actionLabel 随迁 SprintHeader**:纯显示函数跟用点走;因读 header 上下文补 typeName prop,无行为面。
- **两条解读断言(拍板 5/6)被证实**:「30s 定时器不在本页」(G 项手测过:sidebar 即时重拉正常、board/overview 定时刷新不受影响);「S0 = 空态 + gone 两分支」(A2 手测过);e2e 全量绿(用户跑,test 容器 8082)。
- **弹窗态分布定案**:disp/addOpen/selected/confirmFlow 四态留 page(跨域动作编排胶水),弹窗内纯派生(targetLabel/Name)内置——与拍板②一致。

### 节奏执行(新节奏正式适用第二票)

- 一次 coding 五绿点 CP1-CP5(types/ChartBy → useSprintData → ChartSection → Header+Table → 三弹窗),每绿点 tsc+prettier 绿就地 commit,零中途停;停等仅两处(设计过目 + e2e)。PR squash 后 main 单提交(7f9485f)。

### 验证快照

- tsc --noEmit 0 错(每绿点);prettier + `npm run format` 绿;testid 对账 0/0(全页无 testid,保持)
- e2e 全量绿(用户跑);手测冒烟 A-H 落档 `docs/testing/manual-test-cases.md` YPJ-252 节(交付时给勾选清单,验收逐条勾)
