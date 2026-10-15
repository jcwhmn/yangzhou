# 0040 — YPJ-56 看板创建 item 归属:入 active sprint / 无 active 落 backlog / 多 active UI 选

> 票:YPJ-56。分支 `task/YPJ-56`。拍板记录见票面(2026-10-16);自见性缺口与 YPJ-29 board 范围规则的关系见票面背景。

## ① 需求

**背景**:看板数据范围由 YPJ-29 定(有 active sprint → 只显示 active sprint 的 items;无 active → 全量)。看板创建入口 `POST /api/projects/{key}/items` 只带 title,item 落 backlog——**有 active sprint 时,刚创建的 item 当场不可见**(自见性缺口)。

**拍板规则**(用户 2026-10-16):
1. 看板创建 item → **入 active sprint**
2. 无 active sprint(含完全无 sprint)→ **落 backlog**(不挂组,与看板全量显示自洽)
3. **多 active sprint 并存 → UI 让选**
4. 零隐式规则:sprint 开始不自动吸 backlog;移动仍走显式 `assignItem`

**范围**:后端创建请求加可选归属参数;web 看板创建入口按规则 1–3 组装参数。

**非目标**:不改 board 范围规则(YPJ-29);驾驶舱不动;不收紧"多 active 并存";不加 E2E 用例(交互弹选走手工用例);不动 api 层对 planned sprint 的开放度(见设计——API 层沿用 assignItem 校验,前端只在 active 语境传)。

**验收**:
1. 单 active:看板创建 → item 即现看板(active sprint items)
2. 无 active:创建 → backlog(全量可见)
3. 多 active:UI 选后入所选
4. 集成测试三场景 + 负例(sprintId 非法/completed/别项目),**负例下 item 不被创建(原子)**

## ② 详细设计

**归属参数语义**:`CreateItemRequest` 加 `val sprintId: UUID? = null`。API 层校验**沿用 `SprintService.assignItem` 同款规则**(sprint 必须属于该项目;`status == "completed"` 拒绝;planned/active 均可)——保持两入口语义一致;前端只对 active 语境传参,属 UI 约束不属 API 约束。

### 后端(2 文件)

1. **`api/item/ItemService.kt`**(构造注入 +2 repository,无 service 间依赖):
   - `CreateItemRequest` 加 `sprintId`(同文件,仓内惯例);
   - 构造注入 `ItemGroupRepository`、`ItemGroupMemberRepository`(persistence 现成,`repositories.kt` L180/185);
   - `fun create(...)` 加参数 `sprintId: UUID?`;**尾部同事务内**(create 已 `@Transactional`):
     ```text
     if (sprintId != null)
       sprint = groups.findByProjectIdAndObjectId(project.id, sprintId) ?: 404 "sprint 不存在"
       if (sprint.status == "completed") 409 "sprint 已完成,不能指派"
       memberships.save(ItemGroupMember(groupId = sprint.id!!, itemId = item.id!!))
     ```
   - 校验置于 item 全量创建之后、事务提交之前——**任何负例下 item 不落库(原子,负例无孤儿)**。
   - **否决**:Controller 编排 create+assign 两步调用(跨事务非原子,负例留孤儿 item);ItemService 注入 SprintService 复用 assignItem(SprintService 已注入 ItemService,构造循环依赖);参数名 groupId(全仓 sprint 语境统一用 sprintId:assignItem/board/看板)。
   - ItemDto 不扩字段(前端创建后 `load()` 重拉)。

2. **`api/item/ItemController.kt`**:`create` 透传 `request.sprintId`。**api.http 同步**(硬规则):创建 item 请求加 `sprintId` 字段变体正例;负例区补 completed sprint(409)与外项目 sprint(404),负例后复测 item 计数不变。

### web(1 文件)

3. **`web/app/p/[key]/page.tsx`** `addItem`:board 响应已带 `activeSprints: SprintLite[]`(L82)——
   - `activeSprints.length === 1` → body 自动带 `sprintId`;
   - `> 1` → 弹选(MUI Dialog,列 active sprint 名;确认后带所选);
   - `=== 0` → 不带(现状行为)。
   - 创建后既有 `load()` 重拉 board,自见性即修复。弹选 MUI 对话框淡出期坑(E2E 惯例)暂不涉及(不加 E2E)。

### 测试

4. **集成 `ItemApiTest`(或就近既有 item 集成类)extends AbstractApiTest,黑盒**:
   - ①建项目 + 建 sprint(API)+ PATCH active → 创建带 sprintId → `GET /sprints/{id}/items` 含新 item;
   - ②创建不带 sprintId → `GET /projects/{key}/backlog` 含;
   - ③sprintId = completed sprint → 409,**`GET /projects/{key}/items` 计数不变**(原子);
   - ④sprintId = 别项目 sprint → 404 且计数不变;⑤sprintId 不存在 → 404 且计数不变。
5. **无新表/无迁移**:cleanDatabase 清单不动;entities.puml 不动。

### 否决的备选(汇总)

- Controller 编排两步(非原子)——否决,见上;
- groupId 命名——否决,sprintId 全仓统一;
- API 层只允许 active(收紧)——否决,planned 传参走 assignItem 同款开放度,避免两入口规则分叉;前端约束"只在 active 语境传"已覆盖拍板语义;
- 创建后前端补 `PUT /items/{id}/sprint`——否决,两请求竞态且非原子。

## ③ 手工测试用例(供 build/debug)

1. 8081 PM 实例,取无 active sprint 的项目:看板创建 → item 出现在看板(全量模式)且入 backlog。
2. 同项目建 sprint 并在看板一键激活:看板创建 → item **立即出现**在 active sprint 列(自见性);`GET /sprints/{id}/items` 含之。
3. 再建第二个 sprint 并激活:看板创建 → 弹选两个 sprint → 选定后 item 入所选。
4. api.http:创建带 sprintId 正例 201;completed sprint 负例 409 且项目 item 计数不变(负例区)。
5. 全部响应形状与既有创建一致(ItemDto),OpenAPI(Scalar)可见新可选参数。
