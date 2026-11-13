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

**值语义定义**(2026-11-14 用户定稿;判据=**计划来源**,大小不进判据):

| type_key | 语义 | 判别一句话 | prod 范例 |
|---|---|---|---|
| **story** | 主线单元——立票时就在计划里(sprint/milestone/路线图) | 「立票时它在计划里吗?」 | YPJ-62/63/64(根件无子树);YPJ-105 的 8 子件 |
| **task** | 插入小活——干着干着冒出来的顺手修/清理/小改进 | 「是冒出来的吗?」 | YPJ-108 空行清理、59 库清理、152 数据清理、37 分支命名 |
| **bug** | 缺陷——修复性问题,横切任何层 | 「是缺陷吗?」 | YPJ-10/128 并发死锁、123 显示修复 |
| **goal** | 树根容器——要拆子树的大件(词汇表 Epic 载体) | 「要看子树才算完吗?」 | YPJ-105(挂 8 子件) |

阶梯:goal(容器)> story(单元)> task(小步);bug 横切。不违背词汇表「避免 Epic/Story 类型化分层」——禁止的是实体/硬层,这里只是同质树(ADR-0002)上的 type 值。历史 task 不批量清洗(主线统计走 milestone),从今往后立票选对。

**语义拍板点**(2026-11-14 已定,开工前确认过):

1. **初始种子**:~~4 个白名单值全种 builtin~~ → **修订(2026-11-14 用户手工测试反馈):builtin 概念整体移除**——种子只是初始化占位,所有 type 可增删改;在用守卫保留(数据保护非类型特权)。V58 摘列,V57 不可改已按迁移纪律追加
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

## ⑥ 修订二(2026-11-14,builtin 概念移除)

用户手工测试反馈:不应有 builtin type。拍板:种子=初始化占位(无特权),所有 type 可增删改;在用守卫(有 item 引用 409 先迁移)保留。落地:V58 摘 builtin 列(entity/DTO/守卫/UI Chip·置灰全退);种子删除走在用守卫(空即可删);e2e/集成/api.http 同步修订。typeKey 仍不可变(标识符,item.type 引用它;"改"=改显示名——若要改 key 需同事务迁移 item.type,未在需求内,另议)。

**开放项(另票/另议,不阻塞本票)**:FK 策略反思——用户倾向「约束在应用端而非数据库端硬约束」(FK 带来的清库链/删改摩擦);现状 104 个 FK 是历史主链形态,如何收敛待用户定。

## 七、As-built(2026-11-14)

**落地对照**:spec ①② + 修订一(值语义)+ 修订二(builtin 移除)1:1;偏差无。

- 迁移 V57(建表+四种种子+放 CHECK)+ V58(摘 builtin 列;V57 不可改,追加合规)
- `business/itemtype` 切片:CRUD 四端点 + slug 校验 + 在用守卫(带计数)+ leaderGuard;ItemService create/update 校验改查项目定义;MetricsService byType +name(缺失回退 key);ProjectService 四种占位;delete 链补 purgeItemTypes
- web:ItemTypePanel(增/行内改名含 typeKey caption/两段确认删;无 builtin 特权)+ 图标行 Label 按钮;item 表单动态下拉(历史值兜底回显);Flow byType 轴显示名
- 测试:单测(ItemTypeService 10 案 + ItemService/Project 适配)+ 集成(ItemTypeApiTest 2 例:种子/CRUD/动态建件/在用删/空占位删/metrics 注入)+ e2e(item-type.spec 2 例)
- 过程账:台账 #15(派生查询 typeKey→type.key 路径炸 context;**compileKotlin/单测不报,集成 context 期才爆**)、#16(删项目漏 purge=产品 bug;排序 fixture 与级联潜伏冲突=**#206 跳过 integrationTest 全量的欠账**;接棒级联测试期望序写反)

**验证**:ktlintFormat/prettier/tsc ✓;api+persistence 全量单元 ✓;**全量 integrationTest ✓(5m15s,含三红修复后)**;用户手工 ✓(含修订二)+ e2e 全量 ✓;api.http 全过。

**遗留**:typeKey 改名(需同事务迁移 item.type)未做,需求未确认;FK 策略收敛为开放项(用户倾向应用端约束,方案另票)。
