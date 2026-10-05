# Spec — yangzhou V14:ItemGroup Phase 2(Milestone + Epics 侧边栏 + 组级 rollup)

> 来源:`docs/requirement/ItemGroup PRD.md` §7.1(Epic)、§9–12(Milestone)、§28.1–28.2(Phase 2 裁决)。
> Phase 1(Sprint 平面)= spec 0010 已交付;本 spec 只覆盖 **Phase 2**。Release(Phase 3)另立。

## Problem Statement

项目缺时间轴语义: milestone(目标节点)无建模,侧边栏无 Milestones/Epics 段(PRD §19 导航缺两块),组(Sprint/Epic)只有逐 item 信号、无组级红黄红一眼可见。PRD §28.2 Phase 2 四件事:**Milestone 独立表**、**侧边栏 Milestones 段**、**侧边栏 Epics 段(树查询,零新表)**、**组级可行性信号 rollup**。

## Solution(四块)

1. **Milestone 独立表**(非 ItemGroup,PRD §9):CRUD + 生命周期,0..1 current 由 DB 部分唯一索引硬约束
2. **侧边栏 Milestones 段**:列表 + 管理对话框(单向状态按钮,沿用 V13-S4 哲学)
3. **侧边栏 Epics 段**:`type='goal'` 顶层 item,零新表零新端点(前端从 items API 过滤,§28.1 裁决「树查询」)
4. **组级 rollup**:红>黄>绿取最差(DOMAIN 聚合规则),前端计算——Epic 段色点 + Sprint 页头部信号

## Implementation Decisions

### Milestone 持久层(V28)

- `V28__milestone.sql`:`milestone`(identity PK / object_id uuid / project_id→project.id / name varchar not null / status varchar default 'planned' / target_date date null / created_at / updated_at)
- 约束命名沿用:`ck_milestone_status` check in ('planned','in_progress','completed','cancelled');`fk_milestone_project`
- **0..1 current 硬约束**:部分唯一索引 `CREATE UNIQUE INDEX ux_milestone_project_current ON milestone(project_id) WHERE status = 'in_progress'`(§11「Current 通常最多一个」;DB 兜底,服务层预检给友好 409)

### Milestone 生命周期(PRD §10)

```text
planned ──▶ in_progress ──▶ completed
   │             │
   └──▶ cancelled ◀──┘
```

- 合法迁移:planned→in_progress / planned→cancelled / in_progress→completed / in_progress→cancelled
- completed/cancelled 为终态:再改状态 → 409;终态改名称/日期 → 409(PRD §11「历史 Milestone 应保留」)
- planned→in_progress 时若已有其它 in_progress → 409(先完成或取消当前者)
- DELETE:硬删(无 item 关联,§26.4 v1 不做关联),非终态终态均可删

### Milestone API

```text
GET    /api/projects/{key}/milestones           → 列表(排序:in_progress 优先,completed 末尾;余按 target_date asc nulls last,再按 id)
POST   /api/projects/{key}/milestones           {name, targetDate?} → 201,status=planned
PATCH  /api/projects/{key}/milestones/{id}      {name?, targetDate?, status?}
DELETE /api/projects/{key}/milestones/{id}      → 204
```

跨项目 milestoneId → 404;name 空 → 400(Bean Validation);日期格式 yyyy-MM-dd。

### Epics 段 + rollup(零新端点)

- **Epic 定义**(§28.1 裁决):item `type='goal'` 且 parentId 为空(顶层);子 goal 经 item 树在详情可见,侧边栏 v1 只列顶层
- 侧边栏新增「Epic」段:每行 = rollup 色点 + Epic 名 → 点击进 `/p/{key}/i/{itemId}`
- **rollup 规则**(DOMAIN 聚合规则沿用):Epic 信号 = 自身+全部子孙 item 的 feasSignal 取最差(红>黄>绿,无信号跳过;全无信号 = 无点);Sprint 信号 = 组内 items 取最差,显示于 Sprint 页头部(SignalChip),侧边栏不加(克制)
- 布局:看板/Overview 下 → `Sprint` 段(现状)→ `Epic` 段 → `Milestone` 段(手稿 §19 的 ORGANIZE 语义,v1 不做分组小标题)

### Milestones 段 UI

- 段头「+」→ 管理对话框(名称 + 目标日期);列表行:状态图标(○ planned / ▶ in_progress / ✓ completed / ✗ cancelled)+ 名称 + targetDate
- 行内单向按钮:planned→[▶ 开始]、in_progress→[✓ 完成][✗ 取消];终态无按钮(历史保留)
- 行内 [编辑](复用创建对话框回填)/ [删除](确认后 204)
- 空态:「暂无 Milestone」

## Stories(验收视角)

1. As a 用户, I want 建/改/删 milestone 并推进状态, so that 项目时间轴节点可管理
2. As a 用户, I want 侧边栏看到 Epic 及其红黄红, so that 大目标健康度一眼可见
3. As a 用户, I want Sprint 页看到组级信号, so that 一轮迭代的可行性一眼可见
4. As a 用户, I want 同一项目开两个 in_progress 被拒, so that「当前节点」语义不漂移

## Testing

- **集成**(AbstractApiTest/Testcontainers,清表清单 += milestone):CRUD、状态机合法迁移、非法迁移 409、双 in_progress 409、终态改名 409、跨项目 404、排序规则、删除 204
- **E2E**:`milestone.spec.ts`(对话框 CRUD + 单向推进 + 侧边栏展示 + 双 current 拒绝);`epic.spec.ts`(建 goal item → 段内出现 + 色点;非 goal 不出现)
- 文案外置 texts.ts;`entities.puml` 同步 +Milestone(改 schema 必须同步,AGENTS「类图」);`domain.puml` 不动(纯持久层概念,不进引擎)

## Out of Scope(→ Defer)

Release(Phase 3)· Milestone 关联 item(§26.4)· Release↔Milestone 基数(§26.3)· 里程碑到期提醒 · milestone 落地页(无 item 关联,无内容可放)· Overview 页 Milestone 区块

## Further Notes

- 工作方式(沿 0010):本票单 PR 全量交付 → **不自行合并** → 用户 build/debug → 通过后合并
- 切票:单票(JCW-162)——四块共享同一 sidebar/迁移上下文,拆票收益低于成本
