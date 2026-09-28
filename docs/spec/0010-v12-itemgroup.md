# Spec — yangzhou V12:ItemGroup/Sprint(Phase 1)

> 来源:`docs/requirement/ItemGroup PRD.md`(§28 实施规划)+ 2026-09-28 grill 七题裁决(全部采纳推荐项)。本 spec 只覆盖 **Phase 1 = Sprint 平面**;Milestone(Phase 2)、Release(Phase 3)见 PRD §28.2,后续 spec 化。

## Problem Statement

item 缺少时间盒组织:看板堆满所有 items,无法回答"这一轮做什么/做到哪了"。PRD 已裁决 ItemGroup 不进 item 树,Epic 用现有树表达(goal type + parentId),v1 只需要 Sprint 一个分组平面 + backlog 派生桶 + 项目左侧边栏。

## Solution(四块)

1. **Sprint 平面**:`item_group`(type 判别列,v1 仅 'sprint')+ `item_group_member` 多对多;Sprint CRUD(名称/起止日期/状态)。
2. **item 指派**:`PUT /api/items/{itemId}/sprint`;同一 item 至多属于一个 planned/active sprint(应用层不变量),完成 sprint 的成员关系为历史事实,不可变。
3. **backlog 派生桶**:`GET /api/projects/{key}/backlog` = 非终态 ∧ 不在任何 planned/active sprint 的 items;零建模,一个查询。
4. **项目侧边栏**:项目布局加左栏——看板(全部 items)+ Sprints 段 + Backlog 入口;现有甘特/表格/工时链接原地不动。Sprint 页复用看板/表格组件,item 详情常驻区加 sprint Select(照抄 priority 模式)。

## Grill 裁决(2026-09-28,七题全锁)

1. **状态机**:planned → active → completed;允许多个 active 并行;不做 cancelled(误建直接删);「进行中」= status='active',不做日期推导。
2. **membership 语义**:历史保留。挪动 = 替换 planned/active 行(不变量"至多一个"的必然推论);行指向 completed sprint 后即为历史,不可再改;完成的 sprint 页面显示完成时仍在其列的 items。
3. **侧边栏 IA**:最小范围(只加 ORGANIZE 段),现有项目视图链接不动;IA 重排推迟。
4. **日期**:可选,状态纯手动,无日期自动翻转。
5. **backlog 能力**:只读表格;入组唯一路径 = item 详情 Select;拖拽/行内编辑推迟。
6. **删除**:仅空 sprint 可删(无任何 membership 行);非空 409。
7. **文案**:界面保留 "Sprint" 原词(texts.ts 外置,要改一行)。

## User Stories

1. As a 用户, I want 建 Sprint 并把 item 指派进去, so that 看板外的计划有处安放。
2. As a 用户, I want 侧边栏按 Sprint/Backlog 浏览 items, so that "这轮做什么"一眼可见。
3. As a 用户, I want item 详情上直接选/清 sprint, so that 规划不离开详情页。
4. As a 用户, I want 完成的 Sprint 仍能回看当时的 items, so that 历史不蒸发。

## Implementation Decisions

- **V27 migration**:`item_group`(identity PK / object_id / project_id→project.id / type check('sprint') / name / status check('planned','active','completed') / start_date / end_date / created_at / updated_at)+ `item_group_member`(group_id / item_id,`uk_item_group_member(group_id,item_id)`);uk_/fk_/ck_ 命名照 V1 约定。Phase 3 加 'release' = 一条 ALTER。
- **API**(SprintController,类级 `@RequestMapping("/api")`):
  - `GET/POST /api/projects/{key}/sprints`;`GET/PATCH/DELETE /api/projects/{key}/sprints/{groupId}`;`GET /api/projects/{key}/sprints/{groupId}/items`(含历史行)
  - `PUT /api/items/{itemId}/sprint {sprintId|null}`(语义照 V24 priority:值=指派,null=移出当前 planned/active sprint)
  - `GET /api/projects/{key}/backlog`
  - PATCH 语义:不传=不变 / 空串=清除(日期)/ status 传值=校验枚举后设置,不做迁移合法性检查(全放行,领域规则 3 同哲学)
- **ItemDto** += `sprintId` / `sprintName`(当前 planned/active sprint,可空);list/get/详情共用;Sprint 页 items 复用 ItemService 的 DTO 组装(抽 `toDtos(project, items)`)
- **不变量**:`item 至多一个 planned/active membership` 由 SprintService.assignItem 保证(替换式写入);DB 层不做跨表部分唯一索引
- **组不进匹配输入**(领域规则新条,对齐规则 5):Sprint 不参与可行性判定、忙闲色点、需求/能力匹配;组只影响展示与过滤
- **测试**:集成测试走 AbstractApiTest(Testcontainers,自清表清单 += item_group_member / item_group);用例:CRUD、非空删除 409、指派/移出/替换、指派到 completed 409、跨项目 sprint 400、backlog 谓词、完成后历史仍在

## Out of Scope(→ Defer)

Milestone/Release(Phase 2/3)· 类型运行时 CRUD · View 配置持久化 · 拖拽入组 · 到期提醒 · item 级多个 sprint

## Further Notes

- 切票:S1 Sprint 后端(本 spec 全部 API + 测试)→ S2 侧边栏壳 + Sprints 段 + Backlog 页 → S3 Sprint 页(看板/表格)+ 详情 Select + 卡片角标 + E2E
- **工作方式**:放慢节奏——每票 agent 出码 → 建 PR **不自行合并** → 用户 build/debug → 通过后合并推进
- 领域落点待办(随票完成):CONTEXT.md 加 ItemGroup 词条;DOMAIN.md 领域规则加"组不进匹配输入";实体概念图 entities.puml 同步(改 schema 必须同步)
