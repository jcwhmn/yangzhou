# spec 0154 — YPJ-251 item detail 页重构:2786 行拆组件/hooks(纯结构零语义)

> 状态:三件套待用户过目,过目后才编码。分支 `refactor/YPJ-251-detail-page-split`(基线 main@fa40055;249 PR #240 未合,不同文件域无冲突)。

## 一、需求

`web/app/p/[key]/i/[itemId]/page.tsx` **2786 行(全站最大页面)**:主表单 + 子条目直排区块 + 八页签(评论/日志/判定/需求/依赖+清单/工时/属性/GitHub)+ 弹窗群。拆为可单独验证的小控件 + 页面入口(同 249 形态)。

### 范围
- 纯搬家零语义变化:due/estimate 后写者为准保存(YPJ-244)、testid、全部交互行为不变
- 行为闸 = 全量 e2e 绿(用户跑;**新约定:e2e 一律用户执行**) + 手测冒烟
- 每绿点 tsc+prettier 绿 + 就地 commit;重要切片后停等用户 e2e(用户硬要求:多停、验证)

### 非目标
- YPJ-250 右 sidebar(独立票,依赖本票);任何字段/交互语义变更;加载时机变更(见设计拍板 2)

## 二、详细设计(代码级)

### 现状段图(基线 main@fa40055)

| 行区间 | 内容 |
|---|---|
| L1-245 | imports + module 类型 |
| L246-275 | 模块常量(activityLabel/UUID_RE) |
| L277-326 | 路由解析(resolvedId/numNotFound/编号搜索回退) |
| L327-410 | 状态群(~40 useState:表单态/字典/区块态混杂) |
| L410-481 | 字典加载(statuses/attrs/estScales/milestones/sprints/releases) |
| L482-573 | load()/loadSubitems(useCallback) |
| L574-1028 | 动作函数群(~20 async:子条目/保存/状态/指派/里程碑/sprint/需求/属性/评论/计时/建议/依赖/清单) |
| L1029-1040 | runningEntry 派生 + !item 加载态 |
| L1041-1429 | 主表单 JSX(标题/指派/日期/预估/优先级/类型/sprint/里程碑/release/状态动作) |
| L1430-1497 | 子条目直排区块(YPJ-84 拍板:不进页签) |
| L1498-1507 | Tabs 头 |
| L1508-1589 / 1590-1617 / 1618-1646 | tab0 评论 / tab1 日志 / tab2 判定 |
| L1649-1700 / 1701-1874 | tab3 需求 / tab4 依赖+清单 |
| L1875-2047 / 2048-2062 | tab5 工时 / tab7 属性 |
| L2064-2274 | tab6 GitHub(commits 加载展示) |
| L2276-2334 | launchAsk Dialog + 子条目策略 Dialog + 全局提示 |
| L2336-2786 | 4 个 helper 组件(AttrEditor/RequirementsDialog/CreateBranchDialog/DependencyAddForm) |

### 目标形态

```text
web/app/p/[key]/i/[itemId]/
├── page.tsx                ← 入口:路由解析 + 数据统一加载 + 组装(≤~300 行)
├── components/
│   ├── AttrEditor.tsx          ← L2336 原样搬出
│   ├── RequirementsDialog.tsx  ← L2435
│   ├── CreateBranchDialog.tsx  ← L2555
│   ├── DependencyAddForm.tsx   ← L2727
│   ├── SubitemsBlock.tsx       ← 子条目直排区块(L1430-1497)
│   ├── CommentsTab.tsx         ← tab0(评论)
│   ├── ActivityTab.tsx         ← tab1(日志)
│   ├── FeasibilityTab.tsx      ← tab2(判定)
│   ├── RequirementsTab.tsx     ← tab3
│   ├── DependenciesTab.tsx     ← tab4(依赖+清单)
│   ├── TimeLogTab.tsx          ← tab5(工时:计时/建议/手记)
│   ├── AttributesTab.tsx       ← tab7(AttrEditor 组装壳)
│   └── GithubTab.tsx           ← tab6(commits)
└── types.ts                ← module 类型/常量(activityLabel/UUID_RE)
```

### 设计拍板(与 249 先例的差异,需过目)

1. **区块组件自包含,不单拆 hook**:249 的 hooks(scale/interaction/envelope)是跨区块共享行为;detail 各区块行为(评论/计时/commits…)互不共享,hook 化 = 投机抽象。区块私有态(comments/newComment/timeLog/commits/branchOpen 等)+ 动作函数随组件内置;共享数据经 props 注入。
2. **加载时机不变**:所有 fetch 仍由 page 统一发起(load/loadSubitems/字典加载留 page),数据经 props 下发、动作经回调上行——**不做 tab 懒加载**(现页一进 detail 即全量拉,拆后不变,避免请求时序语义漂移)。
3. **page 持有**:路由解析、item 本体、字典群、load/saveBasics/moveStatus 等跨区块动作、全局提示(saved/error/warnMsg/launchAsk)。
4. **testid 全保留**(全页仅 4 个 testid,逐一核对);props 命名即拆即定;常量/类型原值搬运。
5. **真值表/示意图不适用**:零新交互,现状行为以 e2e 全量 + 手测冒烟为闸(spec 0153 同例)。

### 切片与停点(每步编译绿 + 绿点即 commit)

| 切片 | 内容 | 出 page 行数 | 停点 |
|---|---|---|---|
| A | types.ts + 4 个 helper 组件搬 components/(纯叶子) | ~470 | tsc+prettier 绿,commit,**停:用户 e2e** |
| B1 | CommentsTab/ActivityTab/FeasibilityTab(轻区块) | ~290 | tsc 绿,commit |
| B2 | RequirementsTab/DependenciesTab | ~330 | tsc 绿,commit |
| B3 | TimeLogTab/AttributesTab/GithubTab | ~500 | tsc+prettier 绿,commit,**停:用户 e2e** |
| C | SubitemsBlock + 主表单态收口评估(page 预计 ~800→收口) | ~500 | tsc 绿,commit,**停:用户 e2e** |
| D | 入口收口 ≤~300 + imports 修剪 + format + 全量 e2e + 手测交付 | — | **停等 build/debug(硬规矩)** |

> 行数按段图估算,以实拆为准;单文件 <400 行红线随时核(GanttChart 329 先例)。
> 若 C 阶段主表单态收口不达 ≤~300,如实记 As-built 偏差(249 同例:再拆动语义,不硬拆)。

### 否决的备选

- 各区块自 fetch(懒加载)——请求时序变化 = 语义漂移,弃(拍板 2)。
- 全部状态收 useItemForm 大 hook——区块态与表单态混居,巨石 hook 换巨石组件,弃。
- 一步到位同拆后统一调试——编译错误成堆难定位,自底向上每步绿,弃(249 血训平移)。

## 三、手工测试用例(dev 3001,重构分支;用户 build/debug)

> 基线 = prod 3000 同一票对照。前置:dev 容器重建含 251 代码镜像 + sha 核对。

- [ ] A. 主表单:标题/描述/日期+时刻/预估(后写者为准双改)/优先级/类型/sprint/里程碑/release/指派,逐一改存,值落对,warning toast 通道正常
- [ ] B. 状态动作行:迁移各状态、launchAsk 弹窗(planned 无活动 sprint)、saved 提示
- [ ] C. 子条目:添加(编号/新建/迁移策略弹窗)/展开对照,直排区块置页签栏上方不变
- [ ] D. 八页签逐块:评论增删;日志计时开停+建议登记+手记;判定渲染;需求保存;依赖增删+清单勾选;工时表;属性 AttrEditor 保存;GitHub commits 展示 + 建分支弹窗
- [ ] E. e2e 全量绿(用户跑)+ `npm run format` 绿

## 四、As-built(PR #241 squash 8691cbb 合入后补,2026-11-21)

### 交付形态

```text
web/app/p/[key]/i/[itemId]/  共 3203 行
├── page.tsx               1398 行(入口:路由解析+load 统一加载+主表单+状态动作群+全局提示/弹窗)
├── types.ts               231
└── components/ ×13        CommentsTab 108 · ActivityTab 39 · FeasibilityTab 41 · RequirementsTab 49
                           DependenciesTab 204 · TimeLogTab 213 · AttributesTab 37 · GithubTab 231
                           SubitemsBlock 142(+策略弹窗随迁)· AttrEditor 115 · RequirementsDialog 138
                           CreateBranchDialog 190 · DependencyAddForm 67
```

### As-built 偏差(目标形态 → 实拆)

- **page 1398 行 > 目标 ≤~300**:差距 = 主表单 JSX ~370 + 状态群 ~165 + 跨区块动作群 ~440 + 字典/load/提示 ~200。拍板③明文主表单态+跨区块动作留 page;「再拆动语义,不硬拆」逃生 hatch 适用(249 同例 364 行先例),如实记偏差。
- **拍板执行断言(①/②张力消解)**:②主导数据归属——load() 首屏拉取的 comments/timeLog/deps/depBlocked/checklist/suggestions/subitems 全部留 page(props 下发、动作回调上行),保证「一进 detail 即全量拉」零时序漂移;①落地于按钮触发纯区块态——commits 群/branchOpen 内置 GithubTab、depAddOpen 内置 DependenciesTab。①态清单中的 comments/timeLog 若随组件内置,tab 条件渲染下 tab 往返即重取/重置 = 时序漂移,与②冲突,故不照搬。
- reqOpen 跨 tab2/tab3(tab2 按钮置位、tab3 弹窗读),留 page;**RequirementsDialog 渲染留 page 层 tab 条件外**(原样)——若进 RequirementsTab,tab2 开弹窗时 tab3 未挂载即行为变化。
- 已知微差:depAddOpen 开合态 tab 往返复位(原 page 态存活;纯 UI 态,不在 e2e/手测覆盖面)。
- runningEntry 派生随迁 TimeLogTab;page 死态清理(branchOpen/commits×3);输入框态(newComment/manualMinutes/manualNote/newCheckText/newSubTitle)被 page 动作读取,留 page 走受控 props——零动作体改动,全部动作函数逐字节保留。
- **切片停点表作废**:新节奏(2026-11-21 拍板:先设计→停等 review→一次 coding→停等 e2e)取代切片 A-D 停点表。实际执行:B1/B2/B3/C/D 五绿点 tsc+prettier 绿就地 commit、零中途停;停等仅 e2e 一轮;PR squash 后 main 单提交(8691cbb)。

### 验证快照

- tsc --noEmit 0 错(每绿点);prettier + `npm run format` 绿;testid 全集对账一致(item-milestone-chip / item-warnings / sub-policy-ignore / sub-policy-migrate)
- e2e 全量(test 容器)用户跑,绿(新节奏单轮)
- 手测冒烟 A-E 清单落档 docs/testing/manual-test-cases.md YPJ-251 节(逐条勾档留用户;e2e 全量绿为闸)
- CI main:date-tag 绿、e2e 合并回合时点在跑;本地分支 refactor/YPJ-251-detail-page-split 永久保留(过程考古)
