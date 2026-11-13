# 0123 — YPJ-177:item type 项目级自定义(镜像 ItemGroupType 机制)

> 日期:2026-11-14。用户期望:item type(bug/task/feature 类别)由项目级用户自定义,预置初始 type,可增删改。**域语义变更 + 数据迁移,大票。**

## ① 需求

**现状实锤**:

- `item.type` varchar(20),V1 带 CHECK `ck_item_type in ('task','bug','goal','story')`;service 白名单双写(create 直存,update L323 白名单校验);DB 在用 task/story/bug(prod)
- 前端 type 下拉硬编码 4 项(item 详情页 L1166),建/编表单同处(i/[itemId]/page.tsx L511/536)
- metrics workload `byType` 按 type 分组(TypeCountDto 仅 type/count);Flow 图(YPJ-178)以 key 作轴标签
- 镜像源齐备:ItemGroupType(V35 迁移/实体/service/Controller CRUD/GroupTypesPanel UI/spec 0114 删改拍板语义)完整同型

**范围**:item_type 项目级定义实体 + CRUD API + item 校验改造 + 迁移(V57) + metrics 显示名 + web(管理面板/type 下拉动态化/byType 图显示名) + api.http + 测试三线。

**非目标**:type 维度工作流(type 不带 workflow,是纯标签——拍板见下)、按 type 的权限差异、历史 type 值改写工具(改 type 走 item update 即可)。

**语义拍板点**(开工前需用户确认,附推荐):

1. **初始种子**:推荐 4 个白名单值全种 builtin(task/bug/goal/story)——白名单平移,存量零孤儿;镜像 V35「sprint 全种」
2. **type_key 约束**:推荐 slug(小写字母/数字/`-`,≤20,唯一 per 项目)——标识符不进 UI 文案,name 承载中文显示(镜像 group typeKey)
3. **type 无工作流**:推荐维持纯标签(与 status 解耦)——type 是分类不是状态机,加工作流=新域语义,另票再议
4. **删除语义**:推荐镜像 spec 0114——builtin 不可删 409;自定义**非空**(有 item 引用)409「N 个 item 在用,先迁移」;空即可删。不做级联改 type(不可逆数据变更)

**真值表**(item × type 动作):

| 动作 | 结果 |
|---|---|
| create 无 type | 默认 `task`(种子恒在,builtin 不可删 → 恒存在) |
| create/update type ∈ 项目定义 | 存 |
| create/update type ∉ 项目定义 | 400 `type 非法:$type` |
| update type = 同值 | 不校验直过(现状逻辑保持) |
| 删 builtin | 409 |
| 删自定义空 type | 成功,列表 -1 |
| 删自定义在用 type | 409「N 个 item 在用」 |
| 新增 typeKey 重复 | 409 |
| PATCH 改 typeKey | 不可变——PATCH 仅 name |
| Flow byType 图 | 轴标签用定义 name;定义缺失(历史值防御)回退 type_key |

**前端操作流程(设置面板 × 动作)真值表**:

| 状态 × 动作 | 行为 |
|---|---|
| 面板开 | 列表显示全量(builtin 标识+删钮置灰) |
| 输入 key+name 提交 | 成功 → 列表即时 +1;key 重复/格式错 → 409/400 错误条 |
| 改名(name 输入) | 成功 → 列表与 item 表单下拉即时新名 |
| 删自定义空 type | 确认后列表 -1 |
| 删自定义在用 type | 409 错误条「N 个 item 在用」 |

**页面示意图**(项目设置内新增 ItemTypePanel,镜像 GroupTypesPanel;item 表单下拉):

```text
┌─ 项目设置 ────────────────────────────────────┐
│ …既有段…                                      │
│ ── Item 类型 ─────────────────────────────    │
│  task    Task          [builtin]  删除(灰)    │
│  bug     Bug           [builtin]  删除(灰)    │
│  feat    Feature            删除              │
│  ┌──────────┐ ┌──────────┐                    │
│  │ key(slug)│ │ 显示名    │        [+ 新增]    │
│  └──────────┘ └──────────┘                    │
└──────────────────────────────────────────────┘

item 表单 Type 下拉(动态):
  Type  [ Feature ▾ ]   ← options = GET item-types(name 显示,key 提交)
```

## ② 详细设计

**迁移 `V57__item_type.sql`**(镜像 V35):

1. `create table item_type(id, object_id, project_id, type_key varchar(20), name varchar(50), builtin bool default false, 审计四列;uk(project_id,type_key) + fk project)`(无 level/timed——type 非嵌套非时间盒)
2. 种子:`insert … select gen_random_uuid(), id, 'task'/'bug'/'goal'/'story', <name>, true from project`(全项目四种,存量零孤儿)
3. `alter table item drop constraint ck_item_type`(varchar 保持,service 校验兜底)

**persistence**:`ItemType` 实体(镜像 ItemGroupType 去 level/timed)+ `ItemTypeRepository`(`findByProjectObjectIdOrderByCreatedAt` / `findByProjectObjectIdAndTypeKey` / `countByProjectObjectIdAndTypeKey`)。

**api 新切片 `business/itemtype`**(CRUD 独立成片,与 item 共存包外;ItemService 注入 repo 做校验):

- `ItemTypeController`:`GET/POST /projects/{key}/item-types`、`PATCH/DELETE /projects/{key}/item-types/{typeKey}`
- `ItemTypeService`:create(slug 校验 `^[a-z0-9-]{1,20}$` + 项目内唯一 409)/patch(仅 name)/delete(builtin 409;`itemRepository.countByProjectObjectIdAndTypeKey > 0` → 409);DTO `ItemTypeDto(typeKey, name, builtin)` 同文件
- `ItemService`:create/update 的 type 校验改 `itemTypes.findByProjectObjectIdAndTypeKey(project.objectId, type) ?: 400`;默认 task 逻辑不变
- **MetricsService**:`TypeCountDto` 加 `name` 字段——按项目 item_type 建 key→name map,缺失回退 key;WorkloadResponse 形状仅加字段(非破坏)

**web 三处**:

1. `ItemTypePanel.tsx`(镜像 GroupTypesPanel):列表(builtin 标识+删钮置灰)+ key/name 双输入新增 + 行内改名 + 删除确认;项目设置对话框挂载(layout.tsx 加 `itypeOpen`,与 gtypeOpen 同排)
2. `i/[itemId]/page.tsx`:type 下拉 `options` 改 `GET item-types`(name 显示,key 提交);`useState("task")` 默认不变
3. `flow/page.tsx`:`typeSeries` 轴数据 `x.name`(回退 x.type);texts + `itemTypes` 段文案

**api.http**:item 区后新增 `item-types` 区块(CRUD 四请求,变量自含)+ 负例区(builtin 删 409 / 在用删 409 / key 重复 409 / 非法定义 type 建 item 400)。

**否决备选**:复用 ItemGroupType 加 kind 列(两种类型语义差异大:level/timed/workflow 只属 group,塞一起污染);type 上 CHECK 保留+动态改约束(迁移复杂化,service 校验足够,镜像先例);级联删除改 item type(不可逆,禁)。

**测试设计**:

- 单元:ItemTypeService(create slug/重复 409/delete builtin 409/delete 在用 409/patch 仅 name)+ ItemService type 校验(动态定义命中/未命中 400/同值跳过)——mockk 直测
- 集成(ItemTypeApiTest 新文件,extends AbstractApiTest):CRUD 全链路 + item create/update 用动态 type + 在用删 409 + bootstrap 项目四种断言;**`cleanDatabase` 两清单加 `item_type`**(审计清列表 + delete 列表,置于 item_group_type 旁;backend-testing skill 硬规)
- e2e:project-settings.spec(或新 item-type 段)——面板增 type → item 表单出现新 name → 用新 type 建 item 成功 → 删在用 type 409 提示;Flow byType 显示名联动
- 迁移验证:integrationTest 起 Flyway 全链路;种子断言(既有项目四行)

## ③ 手工测试用例(用户 build/debug,dev 3001)

1. 项目设置 → Item 类型:见 4 builtin;新增 `feat/Feature` 成功;重名 409;非法 slug(大写/中文)400
2. item 表单:Type 下拉出现 Feature;选它建 item 成功;Flow「按 Type」轴显示 Feature(非 feat)
3. 删 feat:有 item 在用 → 409 提示 N 个;把该 item 改回 task 后删除成功
4. 删 task(builtin):按钮置灰 + API 409
5. 改名 Feature→特性:下拉/Flow 图即时新名
6. 回归:存量 item 的 type 显示正常;metrics/dashboard/Excel 导出 type 列不受影响
