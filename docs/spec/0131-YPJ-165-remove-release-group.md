# spec 0131 — YPJ-165 移除旧 Release 组实现(独立实体重建的前置)

> **架构破坏显要标注**:本票删除 builtin release 类型与整个 release 切片,属领域语义变更(PRD v1.2 §0/§11 已裁决:Release 独立实体重建,旧 ItemGroup 形态两头不靠,拆)。重建见 Milestone-Release PRD §4.1/§6。

## ① 需求

- 移除「release = item_group(type=release)+ ReleaseService」的混合旧实现,为独立 release 实体(PRD US-R1+)腾位。
- 存量处置(PRD §12.3 拍板):**A 不迁移**——存量 type='release' 组行及成员关系废弃删除,不转换。
- 范围两切片(同 PR 原子交付,前端契约同步):A 后端拆除;B 前端拆除。
- **非目标**:新 release 实体(后续票);milestone 域(不碰);`project.release_enabled` 列/设置(保留,V62 不动,新实体复用);`item_group` 表本身(sprint 继续用)。

## ② 详细设计

### A. 后端

1. **迁移 V62__release_group_removal.sql**(数据清除 + 列退役):
   ```sql
   delete from item_group_member_history where group_object_id in (select object_id from item_group where type='release');
   delete from item_group_member where group_object_id in (select object_id from item_group where type='release');
   delete from item_group where type='release';
   alter table item_group drop constraint fk_item_group_milestone;
   drop index ix_item_group_milestone;
   alter table item_group drop column milestone_id;
   alter table item_group drop column released_date;
   ```
   注:`ck_item_group_type` 已被 V35 drop(type 自由 varchar + service 层校验),无需收回;type='release' 组行删后服务层自然拒绝。
2. **删目录** `backend/api/.../business/release/`(ReleaseService/ReleaseController)+ 测试 `ReleaseApiTest`/`ReleaseServiceUnitTest`。
3. **ItemController**:删 `releaseService` 注入与 `PUT /items/{itemId}/release` 端点;**ItemService**:ItemDto 删 `releaseId`/`releaseName`,buildCtx 删 currentRelease 逻辑(契约变更:items 响应少两字段,前端同步)。
4. **ProjectService**:create 不再种子 release 组类型(新项目 group-types 为空,用户「加 level」自配;影响 e2e helpers 拉入分流注释);`releaseEnabled` 设置字段保留不动。
5. **CLI `Main.kt`**:bootstrap 文案「sprint/release N」→「sprint N」。
6. **api.http**:删 release 相关节(创建/发布/挂摘/负例 builtin 409),「类型列表应含 sprint/release」等表述更新。
7. **entities.puml**:item_group 去 releasedDate/milestoneId;备注 release 组形态退役。

### B. 前端(web/)

8. **删组件** `ReleaseManager.tsx`;**layout.tsx**:删 releases state/fetch/侧栏 Releases 段/管理入口(设置开关里 releaseEnabled 一行保留——列在,新实体接回时复用)。
9. **item detail page**:删 ReleaseLite 类型/releases fetch/详情 Release 下拉与拉入移出调用。
10. **texts.ts**:删 release 相关键(projectNav.releases/manageRelease/noReleases 等)。
11. **e2e**:删 `release.spec.ts`;`project-settings.spec.ts` 改「关 milestone 段,release 段不存在」语义(或删 release 断言);`group-types.spec.ts` 断言 release 不在面板——改为面板无内建类型;`helpers.ts` 拉入分流注释/逻辑核对(新项目无 builtin 类型,S0 分流条件更新)。

### 测试

- 集成:删除 ReleaseApiTest;ItemGroupApiTest/MetricsApiTest/GroupReopenApiTest/BoardMembershipApiTest 里 release 引用改为自建自定义类型或删用例段;AbstractApiTest 若种 release 类型需核对。
- 单元:ItemGroupServiceUnitTest/ProjectServiceUnitTest 对应断言更新(新项目不再种 release)。
- 手工:见 §三。

## ③ 手工测试用例

1. bootRun 起 prod → 项目侧栏无 Releases 段;项目设置里 release 开关仍在(无害);
2. 旧项目(有存量 release)打开:不报错,release 数据已消失(预期废弃);item 详情无 Release 下拉;
3. 新建项目:组类型列表为空,可自建 level 类型;sprint 拉入/自动挂载正常;
4. 表格/看板/工时等既有功能回归无恙。
