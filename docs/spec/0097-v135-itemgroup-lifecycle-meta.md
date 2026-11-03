# spec 0097 — V135 YPJ-105 ItemGroup 生命周期元模型(ADR-0008,草案待拍板)

> 票:YPJ-105(V27「工作流与地基」sprint)。ADR 级:交付 = `docs/adr/0008` + 本 spec + 后续票拆分。
> 票描述中「唯一实例化类型 = Sprint」「Phase 2/3 未做」已过时——本 spec 以 2026-11-03 代码现实为准(§0 实证)。
> **本文件是草案:§1 四个拍板点须用户裁决后,ADR/spec 才定稿、才动码。**

## ⚠ 架构边界说明(置顶)

- 不动 domain 匹配引擎:组不进匹配输入(领域规则 7,spec 0010)维持不变。
- Milestone 独立表裁决(V28/spec 0056 置顶)维持:本票不把 Milestone 并入 ItemGroup。
- Epic 仍走 item 树(`type='goal'` + parentId),不建 ItemGroup 平面(spec 0011 裁决)维持。

## ⓪ 现状盘点(2026-11-03 实证,比票描述新)

已落地(全部有 spec 与迁移为证):

| 能力 | spec / 迁移 | 形态 |
|---|---|---|
| Sprint 平面 + backlog 派生桶 + 侧栏段 | 0010 / V27 | item_group + item_group_member |
| membership 加/离留痕(episode) | 0037/0041 / V32 | item_group_member_history,open-episode 部分唯一索引 |
| Milestone 独立表 + 0..1 current 硬约束 + 侧栏段 | 0011 / V28 | 独立表,非 ItemGroup |
| Release(type='release')+ released 终态 + milestone N:1 + 侧栏段 | 0012 / V29 | item_group 内,type 判别 |
| ItemGroupType 项目级运行时类型 + 通用组端点 + 设置页 | 0056 / V35 | item_group_type 表,CHECK 已放开 |
| item 开始自动挂最早 active sprint + status.is_in_progress | 0089 / V42 | 服务端全入口一致 |

**语义泄漏实锤(类型泛化半途——本票直接动因)**:

1. `SprintService.assignItem`(`PUT /items/{itemId}/sprint`):找组/替换均不筛 type——release 或自定义组的 ID 传进 sprintId 会被当 sprint 挂入;且 `current = 未完成行 firstOrNull` 替换时**可能误删 item 的 release 行**(跨类型互斥破坏 PRD §7.3「被安排到 Sprint」与「被纳入 Release」共存)。
2. `ItemService.create`(YPJ-56 初始归属):`sprintId` 同样不筛 type,任意组可挂。
3. `ItemService.update` 自动挂载(YPJ-120):`firstOrNull { it.status == "active" }` 不筛 type——项目里最早的 **active 自定义组**会被当 sprint 挂入,toast 误报「已自动加入 sprint X」。
4. 同处 `hasLive = rows.any { status != "completed" }` 不筛 type——item 已在 planned/active 的 **release** 里时,自动挂 sprint 被错误抑制。
5. 对照组:`ReleaseService.assignItem` 收口正确(`takeIf { it.type == "release" }`,替换只动 release 行)——**release 路径是正确范式,sprint 路径是历史宽读法**。
6. 终态护栏不对称:release `released` 后变更 409「历史保留」;sprint `completed` 后可随意 PATCH 回 active(与 0010 裁决 2「完成的 sprint 成员关系为历史事实」语义冲突——成员行不可变但组状态可翻转,完成语义漂移)。
7. 自定义类型组:0056 通用组端点可建组/改名/删组,但**无成员管理端点**;生命周期与 sprint 同一状态机(planned/active/completed),纯为「最小语义」放行——正是拍板点 (a) 的现状读法。

## ① 需求(拍板点,★=须用户裁决)

### ★P1 生命周期归属——「开始/完成 sprint」按钮在泛化后归谁(票描述致命问题)

| 选项 | 内容 | 评价 |
|---|---|---|
| (a) 所有组都有 | 通用端点现状读法 | Epic/Phase 类组「active/completed」语义不符 |
| (b) 离 item 最近的组 | 启发式 | 嵌套后破;隐式规则难解释 |
| (c) 逐组配置 | 每组自定义生命周期 | 护栏难保,配置负担 |
| **(d) 生命周期挂 ItemGroupType【推荐】** | 类型带 lifecycle 语义,组按类型继承 | 与 PRD §27「共享技术抽象,不共享业务语义」一致;类型表 V35 已就位,增量最小 |

(d) 的具体形态(草案):

- `item_group_type` 新增 `lifecycle` 列:`timebox` / `release` / `none`(默认 none)。
- 内建种子:sprint → timebox(planned→active→completed,手动翻转,允许多 active 并行——0010 裁决 1 不变);release → release(planned→released 单向,现状语义);自定义 → none。
- `none` 类型:组只有名字(+可选日期),无状态字段语义,无生命周期按钮,不参与自动挂载。
- UI:「开始/完成」操作只对 timebox 类型渲染;「发布」只对 release 类型渲染——按钮归谁由类型回答,不再逐组配置。

### ★P2 「至多一个未完成 membership」不变量的类型范围

现状:sprint 路径跨类型互斥(§0 泄漏 1),release 路径按 type 正交(§0 泄漏 5)——同一条业务规则两种读法并存。

- **方案 i【推荐】按类型正交**:sprint 维持「同一 item 至多在一个未完成 sprint」;release/自定义组(若开挂载)各自独立计数。恢复 PRD §7.3 三维正交,`assignItem` 只动同 type 行。
- **方案 ii 维持全局互斥**:跨类型至多一个未完成组,写进 ADR 作为有意约束。与 PRD §7.3 冲突,需在 ADR 里显式推翻该节读法。

### ★P3 层级规则(PRD §26.1「暂定配置化层级,规则需设计」)

- **方案 i【推荐】本 ADR 定「v1 一层,嵌套 defer until asked」**:不建 parent 列;§26.1 本就「非目标/暂不确定」,等真实需求再立 ADR。Item 侧的层级已由同质树(ADR-0002)承担。
- **方案 ii 本票引入 `parent_id` + 层级规则**(祖先-后代不成环等):为未来预付成本,违背最简可行实现。

### ★P4 本票范围(票描述「Phase 2/3 是否并入」的现实化)

Phase 2/3 功能**已存在**(§0)。「并入」不再有意义,本票范围二选一:

- **方案 i【推荐】ADR + 语义收口**:定稿 ADR-0008 → 修 §0 泄漏 1–4(sprint 路径收口 type)+ P1(d) lifecycle 落地 + P2 裁决落地 + sprint 终态护栏(§0 泄漏 6)→ 同 PR 或紧随小 PR 交付。ADR 立规矩而代码不收口 = 票目标未达成。
- **方案 ii 只写 ADR**:泄漏修复另立票(排期风险:泄漏 1/3 是用户可触发的错误行为,建议尽早)。

### 非目标(草案,拍板后定稿)

- 不做自定义类型的生命周期配置 UI(lifecycle=none 即无生命周期,不开放自定义状态机——真需求出现再立票)。
- 不做自定义组成员管理端点(0056 未做,本票不扩;泄漏 1–3 修复后自定义组不再被误挂)。
- 不动 Milestone、不动 Epic(item 树)、不动 View/导航。
- 不做组嵌套(按 P3 裁决)。

## ② 详细设计(按推荐项预草,拍板后细化定稿)

### 1. ADR-0008(`docs/adr/0008-itemgroup-lifecycle-meta.md`)

内容骨架:决策(生命周期挂 ItemGroupType,P1-d;按类型正交,P2-i;一层,P3-i)+ 理由(PRD §27/§13 语义分型;类型表已就位)+ 推翻 V12 的部分(0010 的「v1 仅 sprint」类型封闭读法、patch 全放行读法)+ 教训记录(「模型内薄实现,不要用歪模型」:泛化类型表先行、语义分型滞后,产生 §0 泄漏窗口——泛化数据模型与语义收口应同票交付)。

### 2. 迁移 `V45__item_group_type_lifecycle.sql`(版本号以实现时最大号+1 为准)

```sql
alter table item_group_type add column lifecycle varchar(20) not null default 'none';
update item_group_type set lifecycle = 'timebox' where type_key = 'sprint';
update item_group_type set lifecycle = 'release' where type_key = 'release';
alter table item_group_type add constraint ck_item_group_type_lifecycle
    check (lifecycle in ('timebox', 'release', 'none'));
```

- builtin 类型 lifecycle 不可改(409);自定义类型创建时 lifecycle 固定 none(请求不收该字段)。
- 存量自定义类型:default none 即正确(它们本来就不该有生命周期语义)。

### 3. 服务层收口(语义变更点)

- `SprintService`:`assignItem`/`ItemService.create` 初始归属/自动挂载,找组一律 `type == "sprint"` 校验(404/409 语义:传非 sprint 组 ID → 404「sprint 不存在」);替换只动 `type='sprint'` 的未完成行。
- `ItemService.update` 自动挂载:`firstOrNull { it.type == "sprint" && it.status == "active" }`;`hasLive` 改为 `rows.any { rowGroups[it.groupObjectId]?.type == "sprint" && it.status != "completed" }`(自动挂载关心的是 sprint 占用,不是任意组)。
- `ItemDto.sprintId/sprintName` 组装:只认 sprint 类型行(现组装点一并收口)。
- sprint 终态护栏:`completed` 后 PATCH status/日期 → 409「已完成 sprint 为历史事实,不可变更」(对齐 release 范式;名称是否同样冻结,实现时按「历史不可变」整组冻结——与 release 一致)。
- `GroupTypeService` 通用组端点:`status` 字段仅在 lifecycle != none 时可传;none 类型传 status → 400。

### 4. 前端(web,最小)

- sprint 段/详情 Select:数据源不变(本就只拉 sprints);详情 sprint Select 选项列表仍来自 sprints 端点,无改动。
- 设置页 GroupTypesPanel:类型列表展示 lifecycle 标签(只读);不开放 lifecycle 编辑。
- 文案外置 texts.ts。

### 5. 契约与文档联动

- OpenAPI 变更:group-types DTO +lifecycle;sprint 409 新增场景 → `backend/script/api.http` 补正/负例(AGENTS 规矩)。
- `docs/architecture/entities.puml`:item_group_type +lifecycle(改 schema 必须同步)。
- CONTEXT.md ItemGroup 词条改写(类型泛化 + 生命周期挂类型 + 一层);DOMAIN.md 实体一览同步。
- 票拆分建议(ADR 定稿后):本票(ADR+收口)若超一个会话,拆「ADR 文档票」与「收口实现票」两张垂直切片,各自验证绿。

## ③ 手工测试用例(草案,拍板后补全)

1. 泄漏回归:建自定义类型组(设为 active)→ 开始无组 item → **不挂自定义组**,无 toast(item 留 backlog)。
2. sprint+release 正交:item 挂 sprint A → 再挂 release R → 两者共存;PUT sprint 换 B → release R 不丢。
3. sprint 传 release 组 ID → 404;create item 传自定义组 ID 为 sprintId → 404。
4. completed sprint PATCH status=active → 409;release released 后 PATCH → 409(回归)。
5. 自动挂载仍工作:最早 active **sprint** 存在 → 开始 item → 挂入该 sprint + toast + 留痕行。
6. 设置页:自定义类型 lifecycle 显示 none;builtin 显示 timebox/release;改 builtin lifecycle API → 409。
7. 既有回归:sprint CRUD/指派/backlog/board、release CRUD/指派、api.http 相关区块全绿。

## Out of Scope

组嵌套(P3 除非拍 ii)· 自定义类型生命周期配置 · 自定义组成员管理 · Milestone/Epic/View · rollup 改动 · CLI 新命令
