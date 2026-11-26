# 0174 — YPJ-269 ItemGroup 生命周期侧栏

> 需求真相源:`docs/requirement/ItemGroup 生命周期侧栏 SRS.md`(2026-11-26 grill 定稿)。本文为增量实现计划(三件套),用户过目后才编码。

## ① 需求

group 详情页右侧新增只读生命周期侧栏(280px,276 模式;数据层对全部组类型通用,现行页面 = sprint/[id]):创建时刻 / 实际开始 / 实际完成 / 执行时长 / 首 item 开始 / 末 item 完成。数据基础 = 组状态史独立表落库 + 读时派生(零物化,镜像 item LifecycleTimes 先例)。

- **范围**:Flyway 新表 `group_status_history`(sprint/release 全部组类型通用);4 个状态写点接入;GroupDto 详情响应增 4 个派生字段;group 详情页右栏(挂 sprint/[id])+ FactRow 公共件。
- **非目标**(SRS §1):release 侧栏页面(releases 现无详情页,数据层已通用,落地时同构即得)、轨迹时间线 UI(YPJ-284)、侧栏编辑、计划起止展示、milestone、ItemSidebar 改动。

## ② 详细设计

### backend/persistence

1. **`V72__group_status_history.sql`** — 新表(镜像 V31 item_status_history 语义、V41 后 object_id UUID 列形态;**无回填**):

```sql
create table group_status_history (
    id                          bigint generated always as identity primary key,
    object_id                   uuid        not null,
    group_object_id             uuid        not null,
    from_group_status_object_id uuid,       -- null = 初始进入(create)
    to_group_status_object_id   uuid        not null,
    source                      varchar(20) not null,
    created_at                  timestamptz not null default now(),
    updated_at                  timestamptz not null default now(),
    constraint uk_group_status_history_object_id unique (object_id),
    constraint fk_group_status_history_group foreign key (group_object_id) references item_group (object_id) on delete cascade,
    constraint fk_group_status_history_from  foreign key (from_group_status_object_id) references group_status (object_id),
    constraint fk_group_status_history_to    foreign key (to_group_status_object_id)   references group_status (object_id),
    constraint ck_group_status_history_source check (source in ('created', 'action', 'cascade'))
);
create index idx_group_status_history_group on group_status_history (group_object_id, created_at);
```

2. **`Entities.kt`** — `GroupStatusHistory` data class,镜像 `ItemStatusHistory` 形态(`groupObjectId`/`fromGroupStatusObjectId`/`toGroupStatusObjectId`/`source` + `@CreatedDate/@LastModifiedDate`;不加 created_by/updated_by——V46 后新表先例不带)。
3. **`Repositories.kt`** — `GroupStatusHistoryRepository` + `findByGroupObjectIdOrderByCreatedAtAscIdAsc(groupObjectId: UUID)`。

### backend/api(yangzhou.api.business.itemgroup)

4. **`GroupLifecycleTimes`**(itemgroup 包内小 object,~10 行)——同公式镜像 `LifecycleTimes`(firstOrNull { to ∉ startIds } / lastOrNull { to ∈ finalIds }),注释双向锚定 YPJ-132。**否决泛化 LifecycleTimes 签名**:动 item 侧 4 个调用点,零行为收益,不值。
5. **`ItemGroupService`**:
   - `recordTransition(g, fromName?, toName, source)` 私有 helper:按类型 workflow 解析 `group_status`(name→objectId,`groupStatuses.findByGroupTypeIdOrderByPosition` 已有),插入史行。四个写点接入:
     | 写点 | source | from |
     |---|---|---|
     | create(初始 START,~L330) | created | null |
     | `action()` 迁移处(~L454) | action | 动作前状态 |
     | `recomputeParentStatus` 实际变化分支(~L488) | cascade | 变化前状态 |
     | 改父 old parent 回退(~L403) | cascade | 变化前状态 |
   - `GroupDto` += `startedAt/completedAt/firstItemStartedAt/lastItemCompletedAt: String?`(Instant.toString,与甘特 actualStart 同形态);**列表端点 toDto 不派生**(YAGNI),仅 `getGroup()` 详情 enrich。
   - `getGroup()` 派生:组史 → 该类型 workflow 的 START/FINAL objectId 集 → GroupLifecycleTimes;首末 item = `memberships.findByGroupObjectId` → `itemStatusHistory.findByItemObjectIdInOrderByCreatedAtAscIdAsc` 按 item 分组 → `LifecycleTimes.startedAt/completedAt`(项目级 START/FINAL sets,同 gantt L310 推导)→ min/max。两查一批,无 N+1。
6. **测试**(ItemGroupApiTest 增量;backend-testing skill 已读):建组→start→complete→reopen→complete 断言派生字段(完成=第二次时刻);backward→start 断言 started=首次;直造组不动动作断言字段 null;挂成员 item 推进生命周期断言首末;repository 层史行 source 断言。

### web

7. **`web/components/FactRow.tsx`** — 新公共件(~20 行):`FactRow({label, value})` caption+body2 行,`value ?? "—"`;导出 `fmtDateTime(iso)`(zh-CN 本地化,与 ItemSidebar 现格式一致)。**ItemSidebar 不动**(451 行外科手术红线;282 即时保存票到时统一换)。
8. **`sprint/[id]/types.ts`** — `Sprint` += 4 字段;**`web/lib/sprints.ts`** `toSprint` 映射透传。
9. **`sprint/[id]/components/GroupSidebar.tsx`** — 只读仪表(挂现行 group 详情页,release 有详情页时同构即得):FactRow×6;执行时长客户端算(completed−started → `X天Y小时`,<1h 显分钟);样式对齐 276(Stack/间距/字号)。
10. **`sprint/[id]/page.tsx`** — 主列 + 右栏 flex 行(镜像 276 item 详情页布局);SprintHeader/SprintChartSection/SprintItemsTable 留主列。
11. **e2e**:不加新用例——派生逻辑 integration 覆盖(§6),前端纯渲染;`group-tree-sidebar.spec.ts` 等既有回归不动。

### 否决备选(grill 记录)

- started_at/completed_at 两列 → 丢轨迹,「每次变更都记」不满足(用户否)。
- 史存 JSON 列 → 仓内无 JSONB 实体先例(V59 明确「引入 Converter 属投机基建」);用户终审拍独立表。
- 提取值物化(扩展属性 JSON/竖表)→ 仓内无通用扩展属性机制,item 先例零物化(grill Q1'' 否)。
- release 同构侧栏 → releases 无详情页,剥离另票。

## ③ 手工测试用例(落档 docs/testing/manual-test-cases.md)

### 操作流程(状态×动作真值表,用户视角)

| 状态 | 用户动作 | 侧栏变化 |
|---|---|---|
| 新建 planned 组 | 打开详情 | 仅创建时刻有值,其余"—" |
| planned | start | 实际开始=此刻;执行时长仍无 |
| active | complete | 实际完成=此刻;执行时长出现;首末 item 若成员已有生命周期则出现 |
| completed | reopen | 实际开始保留(首次);实际完成保留(上次) |
| active(重开) | 再 complete | 实际完成刷新为第二次时刻 |
| active | backward → start | 实际开始不变(首次迁出 planned) |

### 页面示意图(ASCII)

```
group 详情页(sprint/[id])
┌──────────────────┬───────────────────┐
│ SprintHeader(名称/状态chip/计划日期)│ 生命周期          │
├────────────────────────────────────┤ 创建      2026/11/26 09:00 │
│ SprintChartSection(图表)          │ 实际开始  2026/11/26 10:12 │
├────────────────────────────────────┤ 实际完成  —        │
│ SprintItemsTable(成员表)          │ 执行时长  —        │
│                                    │ 首 item 开始 —     │
│                                    │ 末 item 完成 —     │
└────────────────────────────────────┴───────────────────┘
```

用例编号 M-269-1…8 见 manual-test-cases.md(开发中,待 review 节)。
