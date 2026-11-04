# ADR-0008:组类型层级化(level)与 sprint 去内置——结构语义挂层级,业务语义挂类型

日期:2026-11-06 · 状态:已接受 · 母票:YPJ-105 · spec:0100

## 背景

V12(spec 0010)立了 ItemGroup 容器模型:v1 唯一实例化类型 = Sprint,类型封闭、sprint 内置种子,组一层无嵌套(PRD §28.1 评审裁剪「§26.1 组可配层级不做」)。0097(S1–S4)已把组生命周期泛化为类型驱动的 workflow(group_status 按 type 种子、动作端点通用化、删 SprintService),但 **sprint 仍以 `type=='sprint'` 字面量携带结构语义**:backlog 排除、开始 item 自动挂载、看板挂组、图表源(0098 后共 7 处后端 + 14 处 web)。

dogfood 无组实验(NGX,133 items,零组实例)与 0098/0099(看板=显式执行面)证明:无 sprint 的项目语义可自洽。用户拍板(0098 后续票决议,2026-11-05):类型管理不应有内置 sprint;流程 = 「加 level」自底向上(最接近 item 的层先配):name/workflow/可选时间盒;存量已配置 sprint 保留。

YPJ-105 遗留致命问题:「开始/完成 sprint」按钮在 ItemGroup 泛化后归谁。

## 决策

1. **结构语义挂层级**:每项目恰一个 **level-1** 组类型(最接近 item 的层);backlog 排除、自动挂载、看板拉入挂组、board 谓词、dashboard/metrics 数据源一律 keyed to `level == 1`,代码不再出现 `type=='sprint'` 结构判定。
2. **业务语义挂类型**:workflow(既成)与时间盒(`timed` 开关,新)是类型属性;「开始/完成」按钮 = 类型 workflow 出边动作(0097 S3 已做),不按类型名单硬编码。
3. **类型即数据,去内置**:新项目不再种子 sprint;类型由用户自底向上配置(level 分配 = 无 level-1 则 1,否则 max+1;并发由 partial unique index 兜底)。存量 sprint 行迁移为 `level=1, timed=true, builtin=false`——语义保留,身份去内置。
4. **release 暂留内置**(0098 决议),`level=NULL`(交付轴,不占嵌套层级);milestone/release 将来 = time frame 类型语义,去内置另票。
5. **否决的备选**:(a) 所有组同权按钮(Epic/Release 语义不符);(b) 离 item 最近组启发式(嵌套后破);(c) 逐组定制(护栏难保、配置负担);纯 (d) 生命周期挂 type 名(不解决结构语义归属,且维持名单硬编码)。

## 不变量(模型护栏)

1. 每项目至多一个 level-1 类型(partial unique index + 应用层 409)
2. board ∩ backlog = ∅(spec 0098)
3. completed 组 = 终态:workflow 出边空 + 成员行历史不可变(spec 0099)
4. 自动挂载/拉入只挂最早创建的 active level-1(YPJ-120 取序语义)
5. timed 组 `start_date <= end_date`;非 timed 组不进月历/velocity

## 后果

- **推翻并替代**:PRD §28.1 裁剪项「§26.1 可配层级 → 不做」;V12「v1 唯一实现 = Sprint」立场(CONTEXT/DOMAIN 词条随 spec 0100 实现更新)。
- **新项目默认无组结构**:backlog 全集、看板=补集+显式拉入(0098/0099 已就绪,自动生效);想要 sprint 节奏 = 加一个 timed level-1。
- **嵌套留白**:level 列是「固定层级」地基;父链/树查询/嵌套展示(YPJ-110/111/112)是独立后续,本 ADR 不裁决其形态。
- **教训存档**(YPJ-105 复盘):模型内薄实现,不要用歪模型——v12 为赶 Phase 1 把「结构语义」缝死在类型名单上,四张票后才拆开;数据结构与语义维度(level×timed×workflow)应在立模型时问全。
