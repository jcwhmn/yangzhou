# spec 0083 — YPJ-106:数字 FK 全量迁移 objectId(As-built 补录)

> **补录说明**:本票未先立 spec 即动工(票面 YPJ-106 已含完整盘点与方案,经用户认可),
> 此文档为 As-built 补录,含三件套(需求/详细设计/手工测试)的事后整理。
> 状态:主代码+单测+集成全绿;PM 库冒烟待用户确认(V41 应用到 `yangzhou` 库属数据变更,先问)。

## 1. 需求(范围/非目标)

### 范围
- 持久层 53 个数字 FK 列全量迁移为 `*_object_id uuid`(对内以公共 objectId 引用,永不暴露内部数字 id——AGENTS「持久层」硬规则的收口)。
- 49 个审计列(created_by/updated_by,25 表)同样迁移,并补 FK。
- 实体/仓储/服务内部引用全部 `xxxObjectId: UUID`;wire 契约**回退到 HEAD 命名**(itemId/memberId/groupId/路径变量/RequestParam),字段值由数字改objectId UUID。
- 单 PR big-bang(不用 expand-contract);数字 PK `id` 保留(ORDER BY id 等不动)。

### 非目标
- 不改任何业务行为;不加新功能。
- 不删 PK `id`(后续票再议)。
- ADR 不新写(docs/adr/ 已知 0005 撞号,另行处理)。

## 2. 详细设计(As-built)

### V41__fk_object_id.sql(589 行)
1. 每列三步:`ADD COLUMN xxx_object_id uuid` → `UPDATE ... SET xxx_object_id = r.object_id FROM <ref> r WHERE xxx_id = r.id`(backfill)→ 校验零 NULL(有NOT NULL语义的列)。
2. `DROP CONSTRAINT` 旧 53 条数字 FK + `DROP COLUMN` 旧数字列(102 DROP COLUMN)。
3. `ADD CONSTRAINT ... FOREIGN KEY (..._object_id) REFERENCES ref(object_id)` 102 条 = 53 FK + 49 审计。
4. **delete rule 保真(fix15,关键修正)**:初版 V41 丢了原 ON DELETE 语义 → member/team/sprint/release 删除撞 RESTRICT 409。已按 V1–V40 原定义回填 **24 条 ON DELETE CASCADE**(attachment/checklist/comment/favorite/igmh/item_activity/item_attribute/item_dependency/item_git_ref/item_status_history/notification×2/project_member×2/project_repo/project_workflow_rule×2/status_transition×2/team_member×2/time_entry);`fk_item_assignee` 的 ON DELETE SET NULL 在 V5 即 object_id 列,V41 不触碰,自动保留。其余列(审计 49 + 原本 plain 的 29)本就无 rule,维持 RESTRICT。

### entities.kt
- 52 个引用字段 + 25 实体的 createdBy/updatedBy → `xxxObjectId: UUID?`(审计列 var + AuditorAware)。
- PK `id: Long` 不动;`objectId` Kotlin 侧生成。

### repositories.kt
- finder/@Query 全 ObjectId 化;25 条 @Query 的 SQL 列名/join 同步。
- 树 purge 递归 CTE 保留数字 PK(`WHERE id IN (SELECT id FROM subtree)`,合法:subtree.id 是 bigint)。
- fix14:ProjectDeletionRepository.purgeRequirements/purgeItemGroupMembers 内 `(SELECT id FROM item/project ...)` 喂 uuid 列 → 改 `(SELECT object_id ...)`。
- fix18:`deleteProject` 原 `DELETE FROM project WHERE id = :pid` 绑 UUID → 改 `WHERE object_id = :pid`。

### api 主代码
- wire = HEAD 命名(两轮回退 wire1/wire2 + fix9 controller 路径变量 20 文件);service 内部 = xxxObjectId,两层不得混。
- fix19:`GET /items/{itemId}/activity` 原直返 `List<ItemActivity>` 实体(字段名随实体漂移为 actorMemberObjectId)→ 新增 `ItemService.ActivityDto`(objectId/kind/oldValue/newValue/**actorMemberId**/createdAt),wire 名回到 HEAD,值 = objectId;web `page.tsx` 的 Activity 类型字段名不变(self-consistent)。

### 测试
- 确定性映射:旧数字 id n → `UUID(0, n)`;stub 键与实体 objectId 同源;sweep2.py 从 HEAD 重扫单测(315 全绿)。
- cleanDatabase:删表前对 24 审计表 + item 置 null 审计列(审计 FK 与业务 FK 成环;item_group_member/_history 无审计列,勿加)。
- 集成 jdbc 裸 SQL:fix12 列名(item_id→item_object_id);fix16/17 uuid/bigint 混比 23 处(子查询 `select id`→`select object_id`、`i.id = ir.item_object_id` join、MetricsApiTest 数字 iid 绑 uuid 列);6 处 `as Number).toLong()` 断言改 UUID 直比;ItemAudit/TimeEntryAudit 的 meId → object_id。

## 3. 手工测试用例(供 build/debug 阶段)

1. 8081(default profile)重启 → 日志看 V41 迁移一次通过、PM 库数据完好(item 编号/指派/成员不丢)。
2. PM 界面:item 列表/详情/指派/sprint/release 正常;删除虚拟成员→item assignee 置空;删组→成员关系级联清。
3. CLI `yz` 基本读写正常(session 指向 8081)。

## 4. 验证记录

- `gradle test`:315 绿。
- `gradle integrationTest`:**全绿**(50F→18F→5F→0,五轮;失败构成:jdbc 裸 SQL uuid/bigint 混比 → FK delete rule 丢失 → deleteProject 绑定列错)。
- `gradle build`:SUCCESSFUL。
- `docs/architecture/entities.puml` 已同步(补 ItemAttribute、审计 note、V41 标注、去重 hide empty members)。

## 5. 已知边界 / 后续

- PM 库 `yangzhou` 尚未应用 V41(8081 旧实例在跑);应用前建议 `pg_dump` 备份(docker 容器内执行)。
- spec 0080(v98)仍未入库,何时提交待用户定。
- ADR-0005 撞号已理(api-package-structure → 0007);本票无新 ADR。
