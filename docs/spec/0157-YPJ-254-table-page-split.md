# spec 0157 — YPJ-254 表格视图页重构:887 行拆 shared/组件(纯结构零语义)

> 状态:三件套待用户过目,过目后才编码。分支 `refactor/YPJ-254-table-page-split`(基线 main@7137120)。节奏 = 新节奏(2026-11-21 拍板):先设计 → 停等 review → 一次 coding → 停等 e2e;编码期间 tsc+prettier 绿点 commit 保留。

## 一、需求

`web/app/p/[key]/table/page.tsx`(TableViewPage)**887 行**:工具栏(视图切换/保存/另存/删除)+ 筛选条(搜索/三 Dropdown/列配置 Popover)+ 表格(多栏排序表头+13 列单元格分发+分页)+ 命名弹窗。拆 shared/utils/组件 + 页面入口(同 249/251/252/253 形态家族:入口 ≤~300 行,核心文件 <400 行,components/hooks/utils/types 分层)。

### 范围

- 纯搬家零语义变化:既有行为不变;本页 testid 现状核对后保持(e2e 依赖点不动)
- 行为闸 = 全量 e2e 绿(用户跑)+ 手测冒烟
- 每绿点 tsc+prettier 绿 + 就地 commit;**停点只有两个**:本设计过目后、编码完成 e2e 前

### 非目标

- 任何字段/交互/视图语义变更;视图机器(YPJ-47/0045)、排序规则(YPJ-123/124)、持久化规则(YPJ-32/131)一律原样;加载时机变更

## 二、详细设计(代码级)

### 现状段图(基线 main@7137120)

| 行区间 | 内容 |
|---|---|
| L1-35 | imports |
| L37-150 | **module 层**:类型(Status/Item/ViewDto/Filters/ColumnDef/SortKey)+ 常量(EMPTY_FILTERS/COLUMN_DEFS/MOVABLE_IDS/DEFAULT_COL_ORDER/PAGE_SIZES/DEFAULT_SORTS)+ localStorage 读写(normalizeOrder/readSorts/readQuery/readSelectedView/readPageSize)+ matches(YPJ-126 表内搜索) |
| L152-249 | 态群(~21 useState;readCols/readOrder 内嵌)+ 6 effects(key 重置 9 态原子/cols 持久/colOrder 持久/sorts+query 持久/视图记忆/错误条 6s 定时) |
| L248-282 | load(Promise.all items+statuses+views + 记忆视图应用/回退默认)+ mount;失败=死页面(YPJ-131 fatal) |
| L284-322 | toggleCol/moveCol/setFilter/applyView(视图应用一次写 6 态) |
| L324-383 | saveCurrent(选中视图直存 PATCH/另存 POST 建副本,防连点+YPJ-127 遗留修复语义)/deleteView(重置回默认视图) |
| L385-410 | 派生 columns/visibleCols/nameConflict(同名预检)/assignees |
| L412-477 | 排序机器:getters/valueRank/compareField(多栏/null 恒末不翻转/number 自然序/rank 表)+ toggleSort(单击循环 升→降→移除;Shift+点击=替换,YPJ-131) |
| L479-509 | sorted(过滤+多栏排序)/safePage(YPJ-125 越界兜底)/paged/selectedName |
| L511-887 | JSX:工具栏 L513-583、筛选条 L586-673、进度/错误/空态 L675-688、表格 L689-849(表头排序标签+行单元格分发+分页)、命名弹窗 L852-884(save/saveAs 共用) |

### 目标形态

```text
web/app/p/[key]/table/      共 ~830(887 → -6%;本票逻辑留 page 多,抽取开销低于 252/253)
├── page.tsx            ~380(入口:态+effects+load+视图/筛选/排序编排+胶水)
├── shared.ts           ~175(module 全部:类型+常量+localStorage 读写+matches+排序机器)
└── components/
    ├── ItemsTable.tsx       ~195(TableContainer+表头排序标签+13 列单元格分发+TablePagination)
    ├── TableFilterBar.tsx   ~110(搜索 TextField+三筛选 Dropdown+ColumnConfigPopover+dirty 提示)
    ├── TableToolbar.tsx     ~95(标题+视图 Dropdown+保存/另存/删除按钮)
    └── ViewNameDialog.tsx   ~50(命名弹窗 save/saveAs 共用;nameConflict 受控)
```

### 拍板

1. **无数据球 hook,load 留 page**:与 253 的自含 load 不同,本页 load 成功后调 applyView(记忆视图应用/回退默认),hook 化需外露 8+ setter 或注入回调,胶水 > 收益——252 拍板②家族(编排留 page);「useXxxData 合法」是许可非强制。
2. **shared.ts = module 层**(249 utils 分层先例):类型/常量/localStorage 读写/matches/排序机器(getters/valueRank/compareField 纯函数)随迁——零状态零语义,page 与 ItemsTable 共用(PAGE_SIZES/ColumnDef)。
3. **视图/排序/筛选编排全留 page**:applyView(六态一次写)/saveCurrent(直存 PATCH vs 另存 POST,YPJ-127 修复语义勿动)/deleteView(重置回默认)/toggleSort——跨 dirty/views/prefs 多域。
4. **dirty 贯穿全域,留 page**:所有写路径(toggleCol/moveCol/setFilter/toggleSort/query/applyView)置位;不进任何子组件。
5. **四组件纯展示+受控回调**:态全留 page 走受控 props(弹窗态家族);AssignPopover `onChanged` 回调上行 load;`?from=table` 链接随 ItemsTable 迁移。
6. **key 重置 effect 留 page 原样**:一个 effect 原子重置 9 态(prefs+视图+loaded/fatal/dirty),不拆两处(拆了动 effects 语义)。
7. **page 预估 ~380 > 票面 ≤~300,如实记偏差**:排序机器调用点+视图编排(saveCurrent/deleteView/applyView)留 page 是主因;251(1398)/252(451)/253(404)同例;逃生 hatch 同——不硬拆动语义。
8. **真值表/示意图不适用**:零新交互,e2e 全量 + 手测冒烟为闸(0153-0156 同例)。

### 切片(CP 绿点,一次 coding 零中途停)

| CP | 内容 | page 行变化(预估) |
|---|---|---|
| CP1 | shared.ts 抽出(module 全部)+ page import 收敛 + 排序机器改调 shared | 887→~720 |
| CP2 | ViewNameDialog + TableToolbar(视图切换/保存/另存/删除受控化) | ~720→~610 |
| CP3 | TableFilterBar(搜索/三筛选/列配置 Popover) | ~610→~510 |
| CP4 | ItemsTable(表头+13 列分发+分页)收口 | ~510→~380 |

### 预估行数表

| 文件 | 预估 | 内容 |
|---|---|---|
| table/page.tsx | ~380 | 入口:态+effects+load+编排+胶水 |
| table/shared.ts | ~175 | module:类型/常量/读写/matches/排序机器 |
| components/ItemsTable.tsx | ~195 | 表头+行+分页 |
| components/TableFilterBar.tsx | ~110 | 搜索+筛选+列配置 |
| components/TableToolbar.tsx | ~95 | 视图工具栏 |
| components/ViewNameDialog.tsx | ~50 | 命名弹窗 |

> page 收口不达 ≤~300 的偏差如实记 As-built(拍板 7;251/252/253 同例先河)。

### 否决的备选

- useTablePrefs hook(cols/colOrder/sorts/query+持久化 effects):key 重置 effect 与视图/loaded/fatal/dirty 原子耦合,拆两 effect 动语义;dirty 贯穿使 toggleCol/moveCol 需回调出界——弃。
- useTableViewData hook(load+views/selectedView):load 调 applyView(页编排)需回调注入且 load 依赖须 useCallback 稳定化——252 拍板②同案,弃。
- 单元格 renderer 工厂进 shared:可读性零增益,弃。
- 一步到位同拆后统一调试:249/251/252/253 血训,自底向上每步绿,弃。

## 三、手测冒烟(dev 3001,重构分支;用户 build/debug;基线 = prod 3000 对照)

- [ ] A. 加载/渲染:标题+视图下拉(默认视图名)+保存/另存/删除(选中视图才显);拉取期 LinearProgress;错误条(6s 消失;命名弹窗开着保持)+ fatal 常驻重试;空态「暂无 item」
- [ ] B. 列配置:Popover 勾选显隐(编号恒显钉首位)/上下移(title 锁定)/重置;刷新后默认视图下 localStorage 保持
- [ ] C. 筛选+搜索:状态/负责人(含「未分配」)/优先级 Dropdown;表内搜索子串大小写不敏感(YPJ-126,含属性值);筛选后页码越界兜底(YPJ-125)
- [ ] D. 排序:单击循环 升→降→移除(空回默认 number);Shift+点击=仅此列替换(YPJ-131);多栏序号角标;null 恒末不随方向翻转(YPJ-124);number 自然序(YPJ-123);priority/feasSignal rank 序
- [ ] E. 视图:切换(六态一次写)/选中视图保存直存 PATCH 不弹框/默认视图首存→命名弹窗/另存 POST 副本/删除回默认;同名预检 409 前置(YPJ-131);防连点
- [ ] F. 持久化:默认视图下 cols/colOrder/sorts/query/页大小/选中视图记忆写 localStorage(选中视图时不冲默认存档,YPJ-131);重进恢复
- [ ] G. 项目切换:key 变化 9 态原子重置+重拉;负责人 AssignPopover 行内改指派→表格刷新;`?from=table` 回跳链路
- [ ] H. e2e 全量绿(用户跑)+ `npm run format` 绿


## 四、As-built(PR #244 squash 99457f7 合入后补,2026-11-21)

### 交付形态

```text
web/app/p/[key]/table/      共 1098 行(原 page 887 单文件,+24%;家族最瘦,252 +35% / 253 +27%)
├── page.tsx               458 行(入口:态+6 effects+load+视图/筛选/排序编排+胶水)
├── shared.ts              167 行(module 全迁:类型/常量/localStorage 读写/matches/排序机器)
└── components/ ×4
    ├── ItemsTable.tsx       199(表头多栏排序标签+13 列单元格分发+分页;PAGE_SIZES/ColumnDef 自 shared)
    ├── TableFilterBar.tsx   124(搜索+三筛选 Dropdown+ColumnConfigPopover+dirty 提示)
    ├── TableToolbar.tsx     79(标题+视图切换+保存/另存/删除;视图机器回调上行)
    └── ViewNameDialog.tsx   71(命名弹窗 save/saveAs 共用;nameOpen/viewName/saving 受控)
```

### As-built 偏差(目标形态 → 实拆)

- **page 458 行**:高于预估 ~380,仍 > 票面 ≤~300——视图/排序编排(saveCurrent/deleteView/applyView/toggleSort)+ 回调胶水(工具栏 onViewChange 12 行重置分支、save 直存决策体)留 page 是主因;251(1398)/252(451)/253(404)同例。shared.ts 内部用点(MOVABLE_IDS/DEFAULT_COL_ORDER 派生)不外露,仅导出跨文件用点。
- **无数据球 hook(拍板 1 落实)**:load 留 page——与 253 useBoardData(自含 load)的本质差异 = 本页 load 成功后调 applyView(记忆视图应用/回退默认),hook 化需外露 8+ setter 或注入回调;useTablePrefs/useTableViewData 两个备选均因 key 重置 9 态原子耦合/dirty 贯穿否决(见 §二否决备选)。
- **dirty 贯穿全域留 page**:toggleCol/moveCol/setFilter/toggleSort/query/applyView 全部写路径置位,不进任何子组件;FilterBar 的 onQueryChange 由 page 提供 lambda(setQuery+setPage(0)+setDirty)保编排原样。
- **key 重置 effect 留 page 原样**(拍板 6):一个 effect 原子重置 9 态(prefs+视图+loaded/fatal/dirty),未拆分。
- **残留 import 两截获一清理**:CP2 提前删 Dropdown import(筛选条仍用,CP3 才迁)被 tsc 截获,恢复后 amend 回绿——253 CP2 slotRef 同款教训第二次应验;CP4 删 Button import 被 fatal 重试钮(留 page)截获,同法;终检 PAGE_SIZES 残留(TablePagination 已迁,仅 ItemsTable 用点)单独绿点清理。**教训定型:import 删除必须等最后一个用点迁走的同一 CP,提前删必红**。
- **真值表/示意图不适用维持**:零新交互;排序机器(compareField/getters/valueRank)随 shared.ts 迁移为纯函数,D1/D2 手测过(YPJ-123/124/131 语义原样)。

### 节奏执行(新节奏第四票,V32 收官)

- 一次 coding CP1-CP4(shared → 双件 → 筛选条 → 表格),每绿点 tsc+prettier 绿就地 commit,零中途停;停等仅两处(设计过目 "go" + e2e)。PR squash 后 main 单提交(99457f7)。

### 验证快照

- tsc --noEmit 0 锁(每绿点+终检);prettier + `npm run format` 绿;testid 对账 0/5(全家族无 testid;e2e table/view.spec 走角色文本定位)
- e2e 全量绿(用户跑,test 容器 8082);手测冒烟 A-H 落档 `docs/testing/manual-test-cases.md` YPJ-254 节(H1 已勾)
- CP 行数轨迹:887→747→688→623→459(残留清理→458)
