# Spec — yangzhou V15:Release(ItemGroup Phase 3)

> 来源:`docs/requirement/ItemGroup PRD.md` §7.3(Release)、§8.1–8.2(Planned/Historical)、§24(导航)、§26.3(milestone 基数裁决)、§27(Release ∈ ItemGroup 抽象)、§28.2 Phase 3。
> Phase 1(Sprint)= spec 0010、Phase 2(Milestone/Epics)= spec 0011 已交付;本 spec 只覆盖 **Phase 3**。

## Problem Statement

项目缺交付轴语义:无法表达「这次交付包含什么」(§27)。PRD §28.2 Phase 3 三件事:**Release(planned/historical,支持事后补建)**、**侧边栏 Releases 段**、**release.milestone_id 可空 N:1(§26.3 裁决落地)**。

## Solution(三块)

1. **Release = item_group type='release'**(§27「Epic、Sprint、Release 共享 ItemGroup 抽象」;V27 注释预留「Phase 3 追加 'release' = 一条 ALTER」)。交付集合 = item_group_member membership(§7.3「被纳入 Release」)。
2. **生命周期**:planned → released(单向;released 终态)。**事后补建** = 创建时直接 `status:'released'` + releasedDate(§8.2 历史发布回填)。
3. **侧边栏 Releases 段** + 管理对话框(克隆 Milestone 段模式);item 拉入走详情页 Release 下拉(克隆 Sprint 下拉模式)。

## Implementation Decisions

### 持久层(V29)

- `V29__release.sql`:**零新表**,三条 ALTER:
  - `ck_item_group_type` 扩为 `('sprint','release')`
  - `ck_item_group_status` 扩为 `('planned','active','completed','released')`
  - `item_group` 加 `released_date date null` + `milestone_id bigint null` + `fk_item_group_milestone → milestone(id)` + 索引
- 列语义:targetDate = 复用 `end_date`(计划发布日,§8.1);releasedDate = 新列(事实发布日,§8.2);`milestone_id` 可空 N:1(§26.3:不强制 1:1,一个 release 至多挂一个 milestone,一个 milestone 可被多个 release 引用)。
- AbstractApiTest 清表清单不变(item_group_member → item_group → milestone 顺序已兼容 FK)。

### Release 生命周期

```text
planned ────▶ released(发布或事后补建;终态)
```

- `planned→released`:合法,可带 releasedDate;`released→*`:409(PRD §8.2 发布是事实,不可回退;对齐 Milestone 终态哲学 §11)
- released 后改名称/targetDate/milestoneId → 409(历史发布应保留;与 Milestone 终态只读一致)
- **membership 不受状态限制**:released 的 release 仍可补录/移出 items(这正是历史补建语义 §8.2)
- 无 0..1 current 约束(PRD 无「当前 release」概念;0..N)

### Release API

```text
GET    /api/projects/{key}/releases          → 列表(planned 先:target_date asc nulls last;released 后:released_date desc)
POST   /api/projects/{key}/releases          {name, targetDate?, status?('planned'|'released'), releasedDate?, milestoneId?} → 201(缺省 planned)
PATCH  /api/projects/{key}/releases/{id}     {name?, targetDate?, status?, releasedDate?, milestoneId?}(日期空串=清除;milestoneId null=清除)
DELETE /api/projects/{key}/releases/{id}     → 204;有成员 409「成员关系即交付历史」(对齐 spec 0010 裁决 6)
PUT    /api/items/{itemId}/release           {releaseId: String|null}(拉入/移出;镜像 PUT /items/{id}/sprint)
```

- 校验:跨项目 releaseId/milestoneId → 404;name 空 → 400;status 非法值 → 400;日期格式 yyyy-MM-dd
- membership 无跨类型互斥(与「至多一个 planned/active sprint」独立,一个 item 可同时在 sprint 与 release,也可同时在多个 release——PRD 未禁止,v1 不加约束)
- ReleaseDto 含 `memberCount`(列表一次 count),侧边栏行显示「名称 (N)」

### Releases 段 UI

- 侧边栏新段(位置:Milestone 段之后):段头「+」→ ReleaseManager 对话框(克隆 MilestoneManager:内联创建/编辑表单 + 行内单向按钮)
- 对话框行:状态图标(○ planned / ✅ released)+ 名称 + targetDate + releasedDate + (N items);planned 行内 [🚀 发布](确认框可填 releasedDate);released 只读
- item 详情页:Sprint 下拉旁加 Release 下拉(含「无」=移出;released 的 release 也在选项中,可补录)
- 布局:看板/Overview → Sprint 段 → Epic 段 → Milestone 段 → **Release 段**

## Stories(验收视角)

1. As a 用户, I want 建 planned release 并把 items 拉入 so that 「这次计划交付什么」可管理(§8.1)
2. As a 用户, I want 事后补建历史 release(released + releasedDate + 补录 items) so that 存量交付可回填(§8.2)
3. As a 用户, I want release 关联/解除 milestone so that 交付与目标节点挂钩(§26.3)
4. As a 用户, I want 侧边栏看到 Releases so that 交付轴一眼可见(§24)

## Testing

- **集成**(ReleaseApiTest/Testcontainers;清表清单不变):CRUD、排序规则、planned→released、released 改动 409、事后补建(POST 即 released)、membership 拉入/移出/补录、非空删除 409、milestone 同项目校验(跨项目 404)、清除语义、非法 status 400
- **E2E**:`release.spec.ts`(侧边栏段显示 + 对话框建 planned/released + 发布按钮 + 详情页下拉拉入 + 计数 + 非空删除 409 展示)
- 文案外置 texts.ts;`entities.puml` 同步(ItemGroup +releasedDate/milestoneId,关系线 Milestone);`domain.puml` 不动

## Out of Scope(→ Defer)

- Release 落地页(行点击暂无详情页;成员经详情页下拉/计数可见)
- 发布时自动完成关联 milestone / 反向联动
- 跨项目 release、release 模板、changelog 生成
- Overview 页 Release 区块

## Further Notes

- 工作方式(沿 0010/0011):本票单 PR 全量交付 → **不自行合并** → 用户 build/debug → 通过后合并
- 切票:单票(YPJ-4)——spec/迁移/后端/侧边栏/详情下拉共享同一上下文,拆票收益低于成本
