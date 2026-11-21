# spec 0156 — YPJ-253 看板页重构:913 行拆 hooks/组件(纯结构零语义)

> 状态:三件套待用户过目,过目后才编码。分支 `refactor/YPJ-253-board-page-split`(基线 main@33b1ca4)。节奏 = 新节奏(2026-11-21 拍板):先设计 → 停等 review → 一次 coding → 停等 e2e;编码期间 tsc+prettier 绿点 commit 保留。

## 一、需求

`web/app/p/[key]/page.tsx`(BoardPage)**913 行**:头部(标题/刷新/快速添加 portal)+ S-flow 快速添加决策树 + 过滤 chips + 看板列(拖拽)+ 三弹窗(S1m 选组/S2S3 确认/拉入)。拆 hooks/组件 + 页面入口(同 249/251/252 形态家族)。

### 范围

- 纯搬家零语义变化:既有行为不变;**本页 0 testid,重构后仍 0**(已核对;e2e board.spec 依赖的 `cand-row` 在 layout.tsx,本票不动)
- 行为闸 = 全量 e2e 绿(用户跑)+ 手测冒烟
- 每绿点 tsc+prettier 绿 + 就地 commit;**停点只有两个**:本设计过目后、编码完成 e2e 前

### 非目标

- 任何字段/交互语义变更;S-flow 决策树(0105)语义重写;加载时机变更;30s 定时器行为变更(本页**有** 30s 自动刷新,V9-Q3——见拍板 1 归属断言)

## 二、详细设计(代码级)

### 现状段图(基线 main@33b1ca4)

| 行区间 | 内容 |
|---|---|
| L1-49 | imports(含 sprints 工具 listSprints/sprintAction/assignSprint/createSprint/level1Type/syncCurrentParent) |
| L51-85 | module 类型(Status/Item/Filter/SprintLite) |
| L86-117 | 状态群(~22 useState:数据 8 + 表单 4 + 弹窗/胶水 10) |
| L119-136 | effects:last-project PUT 记忆(V11-S1)+ 30s 自动刷新 interval(V9-Q3,手刷同函数) |
| L138-172 | load(P useCallback:project+board+listSprints)+ mount effect + `yz:project-changed` 监听 |
| L174-195 | 拉入流(openPull/setBoardItem/confirmPull) |
| L197-246 | addItem 快速添加决策树(S1m 多 active 选组/S1 直挂/S2 激活并放入/S0 无组概念直上/S3 新建组并激活) |
| L249-302 | createInGroup(S1/S1m/S2/S3 共用)/createFree(仅 S0) |
| L305-346 | confirmLaunch(S2/S3 确认;激活失败降级+手工补广播) |
| L349-357 | startSprint(无 active 提示条用) |
| L359-386 | moveTo(拖拽:乐观 setItems+PATCH+autoAssigned 轻提示 6s+失败回滚重拉) |
| L389-410 | 派生(filtered/memberOptions/finalNames/byStatusName) |
| L412-913 | 主 return JSX:头部+快速添加 portal L414-463、error/sprintInfo Alert L465-478、无 active 提示条 L480-502、过滤 chips+拉入钮 L504-542、看板体 L544-785(列 L556-782:drag 三 handler+列头+空态+卡片 L602-779:chips 行/AssignPopover/标题 Link/due+est/requirements/移出钮)、S1m 选组弹窗 L787-804、S2/S3 确认弹窗 L806-858、拉入弹窗 L859-910 |

### 目标形态

```text
web/app/p/[key]/(board)      共 ~1270(913 → +39%,252 家族 +35% 同例)
├── page.tsx               ~430(入口:态+派生+S-flow/拖拽动作编排+胶水)
├── hooks/
│   └── useBoardData.ts    ~105(数据球 8 态 + load + 全部 effects;setItems 外露)
├── types.ts               ~40(Status/Item/Filter/SprintLite)
└── components/
    ├── BoardHeader.tsx        ~95(标题+刷新+快速添加 portal 表单;受控 newTitle/newEst*)
    ├── BoardCard.tsx          ~205(卡:chips 行/标题 Link/due+est/requirements/移出内置;router.push 内部)
    ├── BoardColumn.tsx        ~115(列:drag 三 handler+dragOver 高亮+列头+空态+list.map→BoardCard)
    ├── FilterChips.tsx        ~60(S7 chips+member chips+拉入钮 onOpenPull 回调)
    ├── LaunchDialog.tsx       ~90(S2/S3 前置确认;launchName/launchPick 受控)
    ├── PullDialog.tsx         ~85(backlog 拉取+picked 局部+逐条 PUT 内置;onPulled 上行)
    └── SprintPickDialog.tsx   ~45(S1m 选组;纯展示)
```

### 拍板

1. **数据球 = useBoardData**:statuses/items/activeSprints/sprints/estScales/estRequired/loading/error 八态 + load + 全部 effects 归 hook(30s interval、`yz:project-changed`、last-project PUT 记忆、mount)——单屏单一数据球,家族规则合法(252 CP2 同构)。**setItems 外露**:moveTo 乐观更新零改动;error 归 hook(load catch 写),动作 setError 解构引用零改动。
2. **S-flow 创建决策树留 page**:addItem/createInGroup/createFree/confirmLaunch 跨 快速添加表单 × S1m/S2S3 双弹窗 × sprintAction/createSprint/level1Type 三域,拆则胶水 > 收益(252 拍板②家族)。SprintPickDialog/LaunchDialog 纯展示+受控:launchPick/launchName/launchMode/pendingTitle/pendingEst 态留 page 走胶水回调。
3. **PullDialog 自包含**(251 ①先例:按钮触发纯区块态内置):backlog 拉取 + picked 局部态 + 逐条 PUT 确认内置;onPulled 回调上行调 load;拉入按钮在 FilterChips(onOpenPull 回调)。原共享 setBoardItem 拆为两处内置(PullDialog 确认 + BoardCard 移出)——同端点同语义,消除 page 共享。
4. **BoardCard 自包含**:卡片 onClick `router.push`(useRouter 内部)、移出按钮 api 内置(onRemoved 回调上行)、AssignPopover `onChanged` 回调上行 load(252 SprintItemsTable onAssigneeChanged 同构);拖拽 onDragStart/onDragEnd 与 dataTransfer 键名逐字节保留。est 块仅依赖 estimateText/fmtDate,**不需要 estScales**(已核对)。
5. **BoardColumn 持列级 drag handler**(onDragOver/onDragLeave/onDrop→moveTo 回调)+ dragOver 高亮;dragOver 态留 page(单值跨列共享,弹窗态家族同构)。
6. **派生留 page**:filtered/memberOptions/finalNames/byStatusName 纯胶水 ~21 行无状态,不值一个 hook。
7. **page 预估 ~430 > 票面 ≤~300,如实记偏差**:S-flow 决策树 ~152 行 + moveTo 53 行编排留 page 是主因(拍板 2 的直接体量代价);251(1398)/252(451)同例;逃生 hatch 同 252——不硬拆动语义。
8. **真值表/示意图不适用**:零新交互,e2e 全量 + 手测冒烟为闸(0153/0154/0155 同例)。

### 切片(CP 绿点,一次 coding 零中途停)

| CP | 内容 | page 行变化(预估) |
|---|---|---|
| CP1 | types.ts 抽出 + PullDialog 自包含(拉入流三函数+两态随迁) | 913→~870 |
| CP2 | useBoardData 数据球(8 态+load+4 effects;setItems 外露) | ~870→~760 |
| CP3 | BoardHeader(快速添加 portal 表单受控化) | ~760→~690 |
| CP4 | FilterChips + 无 active 提示条判定(条留 page) | ~690→~620 |
| CP5 | BoardCard + BoardColumn(卡列拆分,drag 键名逐字节) | ~620→~430 |
| CP6 | SprintPickDialog + LaunchDialog(受控胶水化) | ~430 收口 |

### 预估行数表

| 文件 | 预估 | 内容 |
|---|---|---|
| page.tsx | ~430 | 入口:态+派生+S-flow/moveTo/startSprint 编排+胶水 |
| hooks/useBoardData.ts | ~105 | 数据球+load+4 effects |
| types.ts | ~40 | Status/Item/Filter/SprintLite |
| components/BoardHeader.tsx | ~95 | 标题/刷新/portal 表单(受控) |
| components/BoardCard.tsx | ~205 | 卡片全家(chips/标题/due+est/requirements/移出) |
| components/BoardColumn.tsx | ~115 | 列壳+drag+空态+卡片映射 |
| components/FilterChips.tsx | ~60 | 过滤 chips+member chips+拉入钮 |
| components/LaunchDialog.tsx | ~90 | S2/S3 确认(受控) |
| components/PullDialog.tsx | ~85 | 拉入(backlog 流内置) |
| components/SprintPickDialog.tsx | ~45 | S1m 选组(纯展示) |

> page 收口不达 ≤~300 的偏差如实记 As-built(拍板 7;251/252 同例先河)。

### 否决的备选

- S-flow 动作收进独立 useBoardActions hook:决策树跨表单+双弹窗,收进 hook 后回调签名面 > 收益,且与 252「编排留 page」家族冲突,弃。
- PullDialog 只做纯展示(picked/backlogItems 留 page):可与 252 CP5 同构,但拉入流自足单域(backlog+board-membership),内置更贴 251 ①先例且 page 再省 ~27 行,取内置。
- BoardColumns 容器组件(包 statuses.map+loading skeleton):skeleton 6 行 + map 胶水 20 行,不值一个文件,留 page,弃。
- 一步到位同拆后统一调试:249/251/252 血训,自底向上每步绿,弃。

## 三、手测冒烟(dev 3001,重构分支;用户 build/debug;基线 = prod 3000 对照)

- [ ] A. 加载/渲染:标题+刷新钮+快速添加 portal(placeholder/EstimateField 按 estRequired/estScales 形态);loading skeleton;error Alert;空列文案
- [ ] B. 快速添加 S-flow 全支:S1 单 active 直挂;S1m 多 active 选组弹窗;S2 有 planned → 激活并放入(确认弹窗,取消=放弃创建 D6);S3 无 active 无 planned + 有 level-1 类型 → 新建组默认名「N 号」+撞名递增;S0 无 level-1 → 直接上板(0101 铁律)
- [ ] C. 拉入/移出:拉入钮→backlog 多选→确认逐条 PUT→看板刷新;非终态卡移出钮(终态不显示)→列刷新
- [ ] D. 拖拽:跨列拖放→乐观更新+PATCH;触发 autoAssignedSprintName → 轻提示 6s 消失;失败回滚(错误 Alert+数据回拉)
- [ ] E. 过滤:all/unassigned/blocked/member chips 与 prod 行为一致;memberOptions 由 items 派生
- [ ] F. 无 active 提示条:两文案态(无组/有组无 active)+ planned Dropdown 一键开始 → 看板收窄
- [ ] G. 刷新链路:30s 自动刷新仍在(hook 化后时序不变)+ 手刷钮 + `yz:project-changed` 广播重拉 + last-project 记忆(V11-S1)
- [ ] H. e2e 全量绿(用户跑)+ `npm run format` 绿


## 四、As-built(PR #243 squash 9194a10 合入后补,2026-11-21)

### 交付形态

```text
web/app/p/[key]/(board)      共 1155 行(原 page 913 单文件,+27%;252 家族 +35%,更瘦)
├── page.tsx               404 行(入口:态+派生+S-flow 决策树/moveTo/startSprint 编排+胶水)
├── hooks/
│   └── useBoardData.ts    91 行(数据球 8 态 + load + 4 effects 全归:setItems 外露供 moveTo 乐观更新)
├── types.ts               36 行(Status/Item/Filter/SprintLite)
└── components/ ×7
    ├── BoardCard.tsx        176(chips 行/标题 Link/due+est/requirements;移出内置,onRemoved 上行)
    ├── PullDialog.tsx       103(backlog 拉取+picked 局部+逐条 PUT 内置;onPulled 上行)
    ├── BoardHeader.tsx      91(标题+手刷+快速添加 portal;slotRef/useProjectToolbarSlot 随迁,portal 时序不变)
    ├── LaunchDialog.tsx     84(S2/S3 前置确认;launchPick/launchName 受控,planned 派生内置)
    ├── BoardColumn.tsx      68(列壳+drag 三 handler page 闭包下发+空态)
    ├── FilterChips.tsx      59(S7 chips+member chips+拉入钮)
    └── SprintPickDialog.tsx 42(S1m 选组,纯展示)
```

### As-built 偏差(目标形态 → 实拆)

- **page 404 行**:优于预估 ~430,仍 > 票面 ≤~300——S-flow 决策树 ~152 行 + moveTo 53 行编排留 page 是主因(拍板 2/7;251:1398/252:451 同例);最大组件 BoardCard 176 < 核心文件 <400 闸。
- **setBoardItem 拆两处内置**(拍板 3 落实):PullDialog 确认 PUT(onBoard:true)+ BoardCard 移出 PUT(onBoard:false + `.then(onRemoved)`);原共享函数退役,page 不再持有 board-membership 调用。
- **removable=列 statusType!==FINAL**:原 `!finalNames.has(it.status)`;卡片按 status 名分列(byStatusName),it.status===列名,故与列 statusType!==FINAL 等价——名称唯一性前提,同原分组逻辑自身前提。
- **LaunchDialog planned 派生内置**:原三处内联 `sprints.filter(planned)`/`find` → 组件内 hoist 一次,表达式等价(launchBody 的 find → planned[0],首元素同)。
- **assignSprint 死导入顺手清**:原 page import 未使用(疑似历史残留),随 CP2 import 收敛一并删除,零行为面。
- **残留 import 清理单独绿点**:CP6 后 page 残留 DialogActions import,终检发现单独提交(1a6cf0e)——切片后残留检查纳入下票 checklist。
- **真值表/示意图不适用与解读断言**:零新交互维持;30s 定时器随 useBoardData 迁移后 interval 空依赖挂载一次、load 闭包随 key 稳定,时序不变(G1 手测过)。

### 节奏执行(新节奏第三票)

- 一次 coding CP1-CP6(types+PullDialog → 数据球 → Header → FilterChips → 卡列 → 双弹窗),每绿点 tsc+prettier 绿就地 commit,零中途停;停等仅两处(设计过目 "go" + e2e)。PR squash 后 main 单提交(9194a10)。
- **踩坑延续(handoff 实录应验)**:CP2 提交链 `tsc > log; echo exit=$? && commit` 把红提交放行(echo 恒 0)——当场发现,slotRef 属 CP3 范围、恢复后 amend 回绿;此后闸一律 `tsc --noEmit > log 2>&1 && echo GREEN` 短路链。**教训:闸的退出码必须直接短路 &&,不得经 echo/中转变量**。

### 验证快照

- tsc --noEmit 0 锁(每绿点 + 终检);prettier --check + `npm run format` 绿;testid 对账 0/0(本页无 testid;e2e 依赖的 cand-row 在 layout 未动)
- e2e 全量绿(用户跑,test 容器 8082);手测冒烟 A-H 落档 `docs/testing/manual-test-cases.md` YPJ-253 节(H1 已勾)
- CP 行数轨迹:913→808→760→722→686→468→405(残留清理→404)
