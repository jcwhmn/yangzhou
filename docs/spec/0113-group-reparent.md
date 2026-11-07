# 0113 — ItemGroup 组改父·sprint 跨 epic 移动(YPJ-160)

> 状态:spec 待用户过目。分支 `feat/YPJ-160-group-reparent`(base main)。
> 前置:0111(两层嵌套后端)/ 0112(侧栏树)已合。本票 = 票描述①②;③多层泛化不在本票。

## 一、需求

### 背景

0111/0112 后,两级项目的 sprint 建组时必挂 epic,但**挂定后无法更改**:epic 换代时(如 DEMO1107 的 26-3 → 26-4)sprint 无法跨 epic 移动;存量悬空组(先建 sprint 后配 epic 类型的项目,0112 grandfather)也无归挂入口。

### 范围

- **API**:`PATCH /projects/{key}/groups/{groupId}` 扩 `parentGroupId` 字段(端点已存在,0097,现仅 name/日期)
- **Web**:sprint 详情页头部加「所属 Epic」选择器;平面项目不显示
- **api.http**:新端点手测脚本同步(正例 + 负例区)

### 非目标

- 多层推广(票③:非顶层组改挂任意父)——固定两层内做
- CLI(`yz`)接线
- 前端接 PATCH 其余字段(改名/日期编辑,另票)
- membership 迁移:组改父**不动 item 归属**(sprint membership 本就独立于父子关系;epic 不挂 item,无联动问题)

### 设计决策(请拍板)

**置空语义:两级项目禁止置空**。`parentGroupId` 传 null = 不改(与 PATCH 既有语义一致);传非法/空串/自指/level-1 目标 → 400。即:改父只能「换成另一个有效 epic」,不能「移出变悬空」——0112「新建必挂父」不变量不破,悬空只减不增(存量 grandfather 由本票的归挂入口消化)。

## 二、详细设计

### 后端(`api/business/itemgroup`)

**UpdateGroupRequest**(ItemGroupService.kt:83)加字段:

```kotlin
data class UpdateGroupRequest(
    val name: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val parentGroupId: String? = null, // YPJ-160:null=不动;非空=改挂该父(不允许置空)
)
```

**updateGroup** 扩展(在日期处理前,同一事务):

1. `request.parentGroupId == null` → 走原逻辑不动
2. 非 null:UUID 解析(失败 400「parentGroupId 不合法」)→
   - 自身类型 level ≠ 1 → 400「父层组为顶层,不可改挂」
   - 目标组 `findByProjectObjectIdAndObjectId` 不存在 → 404
   - 目标类型 level ≠ 2 → 400「目标父组类型层级须为 2」
   - 目标自身有父 → 400(固定两层)
   - 与现值相同 → no-op(不写库、不联动)
3. save + **状态联动**:提取 `recomputeParentStatus(pid, parentObjectId)`(从 `propagateParent` 抽出 children 查询/wanted 计算/save 主体;`propagateParent` 改为定位父后调它,行为不变),改父后对**旧父、新父各调一次**。规则同 0111:任一子 ACTIVE→父 ACTIVE;全子 FINAL→父 FINAL;其余→父 START;无子不动。

校验代码与 `createGroup` 的 parent 校验为同一规则;不提取公共方法(两处上下文不同:create 校验「可挂」,update 校验「可改挂」,复制 ~10 行可读性优先,避免为两处造抽象)。

### 前端(`web`)

**lib/sprints.ts**:新增

```ts
export async function updateGroup(key: string, groupId: string, body: { parentGroupId: string }): Promise<void>
// PATCH /api/projects/${key}/groups/${groupId};失败抛 ApiError
```

**sprint 详情页**(`app/p/[key]/sprint/[id]/page.tsx`):

- 数据:页面已有 `getSprint`(GroupDto 含 `parentGroupId`);新增 useEffect 拉 `/api/projects/${key}/group-types` + `/api/projects/${key}/groups`,过滤出 level-2 类型的组作 epic 选项(侧栏 listGroupTree 的 parents 逻辑同源,但详情页独立轻拉,不复用树分桶)
- UI:头部 Stack(标题/Chip/日期之后)加 `所属 Epic` size-small Select:
  - **显示条件**:项目存在 level-2 类型;否则不渲染(平面项目零侵入)
  - 选项:全部 epic(不分状态);`sprint.parentGroupId` 为选中值;为 null 时显示「未分组(悬空)」占位——仅展示,**不可选回**(置空禁止)
  - onChange:`updateGroup` → 成功后 `window.dispatchEvent(new Event("sprints-changed"))`(侧栏重拉)+ 重新 `getSprint` 刷新本页;失败 setError(组名/错误消息,不改选择)
- MUI Select 淡出期坑:无对话框,不涉及

### api.http(组区块)

- 正例:`PATCH` 改挂另一 epic(响应 handler 无回填需求,直接断言 200 + parentGroupId)
- 负例区追加:目标为 level-1 组 / 目标自身有父 / 自身为父层组改挂 / `parentGroupId:""` 置空

### 测试

- **集成**(扩 `GroupParentApiTest`,沿用 DEC111A key 形式):
  1. 改父成功 → 响应 parentGroupId=新父;**旧父/新父状态联动断言**(旧父唯一 active 子移走 → 回 planned;新父收 active 子 → active)
  2. 目标 level-1 → 400;3. 目标自身有父 → 400;4. 自身为 level-2 组 → 400;5. 目标不存在 → 404;6. `parentGroupId:null` → 原值不动;7. 同值 no-op(updatedAt 不变)
- **e2e**(扩 `group-tree-sidebar.spec.ts`,fixture 独享项目):
  1. 详情页改父 → 侧栏树更新(新 epic 下出现、旧 epic 下消失)
  2. 旧 epic 唯一 active 子移走 → 侧栏旧 epic 状态文案变「规划中」(联动可见)
- **单元**:无新纯函数(联动是 DB 行为,集成覆盖)

### schema / 类图

无 schema 变更;`entities.puml` 已画 parent_object_id,不动;`domain.puml` 不涉及。

## 三、手工测试用例(用户 build/debug 阶段执行)

- **T160A 改父主路径**(dev,DEMO1107 或 scratch):打开某 sprint 详情 → 「所属 Epic」选另一 epic → 侧栏树即时更新(新 epic 下出现、旧 epic 下消失);新旧 epic 状态按规则联动(把唯一 active sprint 移走 → 旧 epic 回「规划中」)
- **T160B 悬空归挂**:api.http 造悬空组(先建 sprint 不带父需在无 level-2 类型时——或 SQL 直改 dev 库 parent_object_id=null),详情页选择器显示「未分组(悬空)」→ 选 epic 归挂 → 侧栏平面区消失、epic 树内出现
- **T160C 负例**:api.http 负例区 4 条全 400/404
- **T160D 平面项目**:单类型项目详情页无「所属 Epic」选择器

## 四、待用户确认

1. 置空语义:两级项目禁止置空(§一设计决策)——可否?
2. UI 位置:sprint 详情页头部选择器(而非侧栏行内菜单,因 sprintRow 整行是 Link,嵌按钮有合法性与淡出期坑)——可否?
3. YPJ-160 是否挂入 V28 sprint?(当前 backlog,按约定不自动挂)
