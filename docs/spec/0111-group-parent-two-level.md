# 0111 — ItemGroup 两层固定嵌套·后端(YPJ-111)

父票 YPJ-110(多层嵌套);用户需求拍板 2026-11-11:固定两层先行,合入后马上同构推广多层(异形不做)。术语硬约束:**不 hardcode sprint/epic**——层级由 `item_group_type.level` 定义,level-2=父层、level-1=叶层(nearest-to-item)。

## 一、需求

### 1.1 范围

- `item_group` 加 `parent_object_id`(引用同表 `object_id`,同 item 树模式),实现**固定两层**实例嵌套:level-2 组(如 Epic/期)为父,level-1 组(如 Sprint/主题)为子。
- 树查询:组列表带 `parentGroupId`;侧栏(Web,YPJ-112)客户端组树。
- **子组状态联动父组**(动作端点内同事务):按父组 workflow 的 statusType 推导——
  - 任一子组 ACTIVE 态 → 父组置 ACTIVE 态名;
  - 有子组且全部子组 FINAL 态 → 父组置 FINAL 态名(多 FINAL 取 workflow 顺序首个);
  - 有子组、无 ACTIVE、未全 FINAL → 父组置 START 态名;
  - **无子组 → 父组状态不动**;
  - 推导只在目标 statusType ≠ 当前 statusType 时落库(幂等,不刷 updatedAt 无谓)。
  - 状态名经父组类型 workflow 按 statusType 解析(不 hardcode 'active' 等字面量);父组无对应 statusType(如缺 START)则跳过该项推导。
- level-2 组**不直接挂 item**(item 只挂叶层):membership 目标组类型 level=2 → 409。
- 删除:含子组的父组 → 409(先删子组);子组删除不联动父组状态?——**联动**(同规则重算)。
- 环检测:固定两层下 parent 必须是 level-2 类型且自身 `parent_object_id IS NULL`,天然无环;FK 自引用兜底。

### 1.2 非目标

- 多层/异形(本次合入后另票同构推广);metrics rollup(将来另票);level-2 组的独立 item 挂载;Release(level NULL)不参与父子(类型无 level,不可为父亦不可为子)。

## 二、详细设计

### 2.1 迁移 `V53__group_parent.sql`

```sql
alter table item_group add column parent_object_id uuid;
alter table item_group
    add constraint fk_item_group_parent foreign key (parent_object_id) references item_group (object_id);
create index ix_item_group_parent on item_group(parent_object_id);
```

存量不动(全 NULL)。两层约束(父=level-2、子=level-1、父无父)走应用层校验(跨 project_type 表,CHECK 做不了 join;与「同 item 至多一个 active sprint」同一先例:应用层不变量)。

### 2.2 实体与 repository

- `ItemGroup` 加 `val parentObjectId: UUID? = null`(Entities.kt;放 endDate 后)。
- `ItemGroupRepository` 加:`findAllByProjectObjectIdAndParentObjectId(pid, parentObjectId): List<ItemGroup>`。

### 2.3 ItemGroupService

- `GroupDto` 加 `parentGroupId: UUID? = null`;`toDto` 带出。
- `CreateGroupRequest` 加 `parentGroupId: String? = null`;`createGroup` 校验:
  - 类型 `level == 2` 时 parentGroupId 必填且目标组类型 `level == 2`?——反了:子组是 level-1。规则:**level-1 组可带 parentGroupId,目标组类型 level 必须为 2 且目标组自身 parentObjectId 为 null**;level-2 组带 parentGroupId → 400。
  - parent 与子组同 project(FK+查询按 project 已保证)。
- `listGroups` 加可选 `parentId: UUID? = null` 参数(null=不过滤;传入=按 parentObjectId 过滤;特殊值 `none`?——不做,前端全量+客户端组树)。Controller 透传 query param。
- `deleteGroup`:组有子(`findAllByProjectObjectIdAndParentObjectId` 非空)→ 409「含子组,先删子组」。
- `replaceMembership` / `boardMembership`:目标组类型 `level == 2` → 409「该层级组不直接挂 item,item 挂叶层组」。
- **联动**(私有 fun `propagateParent(g: ItemGroup)`):`action()` 与 `deleteGroup`(删子组时)在事务内调用:
  - 取 g.parentObjectId(空则返回);父组 = 按 project+objectId 查。
  - 子组集 = 父的现存子(删除场景重查,动作场景=父的子减 g?——**重查父的子集**:动作场景 g 状态已落库或已删,重查即得最终集)。
  - 按 §1.1 规则算目标 statusType,经父类型 workflow 解析状态名(新私有 `statusNameByType(type, StatusType): String?`),≠ 当前则 save。
- `membershipIsHistory` 不动(level-2 组根本进不了 membership)。

### 2.4 Controller

- `listGroups` 加 `@RequestParam(required=false) parentId`;其余端点形状不变(联动内嵌 action/delete,无新端点)。

### 2.5 否决的备选

- DB CHECK/触发器强制两层:跨表 join 不可行,触发器引入隐式行为,放弃(应用层集中可测)。
- 独立 `/groups/{id}/children` 端点:前端全量列表客户端组树即可(现 sidebar 本就全量拉),不做。
- 联动走事件/异步:违反「动作=单一事务」(0110 教训),同事务内联。

## 三、手工测试用例

- T111A(dev,leader):建 level-2 类型「Epic」(timed 默认)+ 建 Epic 组 E1;建 level-1 组 S1、S2 挂 E1;GET groups 见 parentGroupId=E1。E1 无子动作前保持 planned。
- T111B:S1 start → E1 自动 active;S2 start 后 S1 complete → E1 仍 active;S2 complete → E1 自动 completed;S1 reopen → E1 回 active。
- T111C:对 E1 执行 start 等——父组状态仍可**手动**动作?(见下「待用户拍板」②)
- T111D:PUT membership 挂 item 到 E1 → 409;删 E1(有子)→ 409;删 S1、S2 后删 E1 → 200。
- T111E:level-2 组建组带 parentGroupId → 400;子组挂 level-1 组为父 → 400。

## 四、待用户拍板(spec 过目时一并确认)

1. **父组状态可否手动动作覆盖**(T111C):联动写完后,用户手动对父组 complete/backward 是否放行?**建议放行**(父组动作端点不变,下次子组动作再被推导拉回)——简单且不阻断;若要禁手动需另加规则。
2. 集成测试文件名:`GroupParentApiTest`(新文件,AbstractApiTest)。

## 五、验收(YPJ-111)

- V53 迁移 + 实体/DTO/API 改动 + 联动逻辑,集成测试绿(建树/联动三规则/无子不动/409 群/membership 拒绝),`gradle build` + `integrationTest` 绿;entities.puml 同步;api.http 补样例。
## 六、As-built(2026-11-11,PR #194 合入)

- 迁移 V53:`item_group.parent_object_id` 自引用 FK + `ix_item_group_parent`。
- 实体 `ItemGroup.parentObjectId`;`ItemGroupRepository.findAllByProjectObjectIdAndParentObjectId`。
- 服务:`GroupDto.parentGroupId` / `CreateGroupRequest.parentGroupId`(仅 level-1 可挂,父须 level-2 且无父);`listGroups(parentId)` 过滤(controller `?parentId=`);`propagateParent`(action/delete 同事务,statusType 推导,无子不动);`GroupWorkflowService.statusType/statusNameByType` 两助手;level-2 组 action 409 / membership 409 / 含子删除 409。
- 测试:`GroupParentApiTest` 7 例(树过滤/联动四规则/无子不动/删子重算/删父 409/守卫群/父引用 404);`ItemGroupApiTest` 自定义类型用例按新语义适配(phase=level-2 → switch 409、挂 item 409)。
- **语义变化实锤**:`createType` 自底向上分配 → 每个新建自定义类型即 level-2 父层;0056 平面自定义组语义收窄(用户 2026-11-11 确认)。
- 手测:api.http @ test 容器全过(用户执行,含 Flyway 首启等待)。
- 差异:与设计一致;待办 = Web 侧栏树(YPJ-112)、多层同构推广、metrics rollup(另票)。
