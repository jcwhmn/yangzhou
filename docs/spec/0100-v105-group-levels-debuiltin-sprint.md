# spec 0100 — YPJ-105 分组类型去内置:「加 level」自底向上的组层级模型

> 母票 YPJ-105(In Progress,V27)。决议链:spec 0098 后续票决议(2026-11-05 用户拍板)→ 0099 非目标(「level-1 组映射=将来加 level 票」)→ 本 spec。
> 交付:ADR-0008(`docs/adr/0008-group-type-levels.md`)+ 大票拆分(§四)。

## ⚠ 架构破坏 / 契约变更(置顶,显要标注)

1. **推翻 PRD §28.1 评审裁剪**:「§26.1 组可配层级 → 不做(v1 一层)」废止——`item_group_type` 引入 `level` 列,类型集合从「种子 + 追加」变为「用户自底向上配置的层级」。
2. **新项目行为变更**:`ProjectService.create` 不再种子 sprint 类型(仍种子 release,builtin 暂留)。新项目默认无 Sprint 段,加 level 后才有组结构——backlog/看板落 0098/0099 无组语义(已实现,自动生效)。
3. **V12 立场废止**:「v1 唯一实例化类型 = Sprint」(spec 0010 / CONTEXT 词汇表 ItemGroup 词条)由「类型即数据,结构语义挂 level-1」取代;词条随实现更新。
4. **契约扩展(兼容)**:`GroupTypeDto` + `level`/`timed`;`GET /groups` 新增 `level` 查询参数(现有 `type` 参数保留)。无删除性契约变更。

## 一、需求

### 范围

- **类型管理去内置 sprint**:类型不再有内置 sprint;用户按「加 level」自底向上配置(最接近 item 的层先配):name + workflow(模板可选)+ **可选时间盒**(`timed`)。
- **level-1 接管 sprint 的结构语义**:backlog 排除、开始 item 自动挂载、看板拉入挂组、board 谓词、dashboard/metrics 数据源——代码中 `type == "sprint"` 判定全部改为 `type.level == 1`。
- **存量迁移**:已配置 sprint 的项目 level-1 即 sprint(行为不变);未配置的项目无 level-1(NGX 无组语义已就绪)。
- **时间盒**:类型级 `timed` 开关;sprint 迁移后 `timed=true`;UI 仅对 timed 类型给起止日期;月历/velocity 只吃 timed 的 level-1 组。
- **release 保持内置**(0098 决议:milestone/release 暂定内置,将来 = time frame 类型语义);`level = NULL`(交付轴,不占嵌套层级)。

### 非目标

- 组嵌套(父链/树查询/嵌套展示)= YPJ-110/111/112 本体,本 spec 只落 level 地基(§四建议重排)。
- milestone/release 开/关开关、侧栏数据驱动显隐(「Sprint +」段头、段按实例显隐)= 后续独立票(0098 决议,中小)。
- release/milestone 去内置(将来票);displayName/icon 字段(决议中带「?」,不做,要时加列)。
- 看板列语义、拖拽排序。

## 二、现状实锚(2026-11-06,main=bd59281)

- **表**:`item_group_type`(V35:`project_id`+`type_key` 唯一,`name`,`builtin`;**无 level/timed**);`group_status`(V46:`group_type_id` 外挂,transitions JSON 内嵌——workflow 已类型驱动);`item_group`(V27:`type varchar(20) default 'sprint'`,`status`,`start_date`/`end_date` 可空——时间盒数据已在,只缺类型级开关)。
- **后端 `"sprint"` 字面量 7 文件**(逐处语义):
  | 文件 | 语义 | 归类 |
  |---|---|---|
  | `ProjectService`(:68) | 建项目种 `("sprint"→"Sprint","release"→"Release")` | 种子(去 sprint) |
  | `ItemGroupService`(:85 `builtinKeys`;:130 `membershipIsHistory`;:327/:405 active sprint;:414 退回 replaceMembership) | 删除守卫/最早 active 挂组/退回清理 | 结构语义 → level-1 |
  | `ItemService`(:173,:296-299,:616) | 组归属读、hasLive、开始自动挂载 | 结构语义 → level-1 |
  | `GroupWorkflowService`(:58) | `"sprint" -> statuses 模板` | 种子模板(去 sprint 名化) |
  | `DashboardService`(:52)、`MetricsService`(:572) | 图表/月历数据源 `listGroups(key,"sprint")` | 展示 → level-1(+timed) |
  | `Entities.kt`(:451 `type default "sprint"`) | 列默认值 | 去默认 |
- **web `sprint` 字面量 14 文件**:侧栏 `layout.tsx`、`backlog`/`flow`/`overview`/`page`(根)/`home`、`sprint/[id]`、`table`、item 详情、`AssignPopover`、`GroupTypesPanel`、`back.ts`、`sprints.ts`(:33 `type=sprint`)、`texts.ts`。
- **CLI**:`yz sprints create/move/list`、`items assign` 内部映射新端点(0097 S2 语义),仍按 `type_key='sprint'` 寻型。

## 三、详细设计

### 3.1 ADR-0008 裁决(YPJ-105 待决致命问题)

「开始/完成 sprint 按钮归谁」:**采 (d) 变体,层级化落地**——

- **结构语义挂层级**:恰一个 level-1 类型/项目;backlog 排除、自动挂载、看板挂组、图表源 = level-1 组。硬编码 `type=='sprint'` 全部按此改。
- **业务语义挂类型**:workflow(已有,group_status 按 type 种)+ 时间盒(新,`timed`)按类型配置;「开始/完成」按钮 = type workflow 出边动作(0097 S3 已做),不再问「归谁」。
- 否决:(a) 全组同权(Epic/Release 语义不符);(b) 最近组启发式(嵌套后破);(c) 逐组定制(护栏难保);纯 (d) 原样(生命周期挂 type 名,不解决结构语义归属)。

### 3.2 数据模型(V48 迁移)

```sql
alter table item_group_type add column level int;                 -- NULL=非嵌套类型(release)
alter table item_group_type add column timed boolean not null default false;
update item_group_type set level = 1, timed = true, builtin = false
    where type_key = 'sprint';                                    -- 存量:保留语义,去内置标
create unique index ux_item_group_type_project_level
    on item_group_type (project_object_id, level)
    where level is not null;                                      -- 不变量①扩展:每 level 恰一类型(2026-11-06 用户确认整列唯一)
alter table item_group alter column type drop default;            -- type 去隐式默认(实体同步去 Kotlin 默认)
```

> **level 是原生列(非计算/派生)**:创建时应用层赋值「最小未占正整数」。不可派生的根因:①值依赖同项目其它行,PG generated column 仅能同行派生;②不变量靠 partial unique index 强制,索引需物理列;③「加 level」是配置动作语义,非属性推导。

- 无新表 → cleanDatabase 清单不动;`entities.puml` 同步 `ItemGroupType.level/timed`。
- `item_group.type` 列保留(= type_key 引用,不改名);`Entities.kt` 去掉 `default "sprint"`。

### 3.3 后端(按文件)

- **ItemGroupService**:
  - `GroupTypeDto(typeKey, name, builtin)` → `+ level: Int? + timed: Boolean`
  - `createType`:level 自动分配——无 level-1 → 1(「最接近 item 的层先配」);有 → `max(level)+1`;并发撞 uk 索引 → 409(不变量①);`timed` 请求可选,默认 false
  - 新增 `level1TypeKey(project): String?`(types 小表内存解析,替代散落的 `"sprint"` 字面量;唯一口径)
  - `builtinKeys`/`membershipIsHistory`:改为 `builtin || hasGroups`(非空不可删;类型语义守卫脱离名单)
  - boardMembership 拉入挂组(:405)、退回清理(:414)、board 两侧谓词(:327):`"sprint"` → level-1 解析
  - workflow 种子模板(`GroupWorkflowService`:58):`"sprint" ->` 改名 `timedLevel1 模板`(planned/active/completed + start/complete/backward,内容不变),新类型按 `timed` 选模板:timed→时间盒模板,否则三态全连通 switch(现自定义语义)
- **ProjectService**(:68):种子表删 sprint 行,保留 release(builtin,level=NULL,timed=false)
- **ItemService**(:173/:296-299/:616):归属读/hasLive/自动挂载判定 → level-1;backlog 谓词(0098 单点)同步
- **DashboardService/MetricsService**:`listGroups(key,"sprint")` → level-1;metrics 月历/velocity 再叠 `timed` 过滤
- **CLI**(`sprints create/move/list`、`items assign`):寻型改 level-1;项目无 level-1 → 明确报错「项目未配置 level-1 组类型(Web 类型管理加 level)」;命令面不变

### 3.4 Web

- **侧栏 `layout.tsx`**:Sprints 段数据源 `GET /groups?type=sprint` → `?level=1`(段名/显隐逻辑不动,数据驱动显隐=侧栏票)
- **`GroupTypesPanel`**:建类型 = 加 level 动线:name + 模板选择(timed 时间盒模板 / 自由三态)+ `timed` 开关;level 展示只读;删类型按钮走新守卫
- **`sprints.ts`**:`type=sprint` 查询换 level;`sprint/[id]` 页路由名保留(level-1 组详情,动作渲染 0097 已做,零改动)
- `flow`/`overview`/`home`/`table`/`AssignPopover`/item 详情:sprint 数据源统一走 level-1(多数是 `sprints.ts`/`back.ts` 单点改动)
- `texts.ts` 文案外置;建项目断言(e2e projects.spec「无 Sprint 段」)

### 3.5 契约与文档义务

- `api.http`:group-types 区块补 level/timed 与负例(第二个 level-1 → 409;删有组类型 → 409)
- `entities.puml`(3.2);CONTEXT 词汇表补「Group Level」词条(**命名冲突显式声明**:Group level = 组嵌套层级,≠ Attribute level 1–4);DOMAIN.md ItemGroup 行更新
- YPJ-110/111/112(多层嵌套):level 列即其「固定层级」地基;建议三票挂到 YPJ-105 下重排为本 spec 后续切片(用户拍板,见 §四)

### 3.6 不变量(护栏,实现须断言)

1. 每 level 恰一类型(partial unique index `(project_id, level)`;level-1 语义同前;createType 应用层校验 + 竞态 409)
2. board ∩ backlog = ∅(0098,不变,回归)
3. completed 组 = 终态:出边空(V46 已有)+ 成员行历史不可变(退回/换组只动非 completed 行,0099 已有)
4. 自动挂载/拉入只挂 **active** level-1(取最早创建,YPJ-120 语义)
5. timed 类型组 `start_date <= end_date`(现校验保持);非 timed 组日期不展示不参与月历

### 3.7 测试(编码前先读 `.agents/skills/backend-testing/SKILL.md`)

- 集成:①新项目无类型 → backlog 全集/board 补集/自动挂载不发生 ②加 level-1(timed)→ 全链语义与 sprint 时代等价 ③存量迁移项目(V48 后)sprint 行为回归 ④第二个 level-1 → 409 ⑤timed=false 的 level-1 不进月历 ⑥CLI 无 level-1 报错
- e2e:新项目加 level 全链(backlog→拉入→开始→看板/月历)+ NGX 无组回归 + 存量 YPJ sprint 回归

## 四、切片拆分与工作量评估(垂直切片,各自可独立合并验证绿)

| 切片 | 内容 | 量 | 独立绿判据 |
|---|---|---|---|
| **A 数据模型** | V48 + Entities + GroupTypeDto/CRUD level/timed + 守卫改 + puml | S(≈0.5d) | `gradle build`+`integrationTest` 绿;行为零变化(存量 sprint 的 typeKey 未变) |
| **B 后端语义切换** | §3.3 全部(7 文件)+ CLI + api.http + 集成测试改造 | M(≈1d) | build+integrationTest 绿;api.http 全过;存量项目行为不变、新项目无 sprint 生效 |
| **C Web + e2e** | §3.4 全部 + e2e 三线 | M(≈1–1.5d) | tsc 干净 + playwright 全量绿;停,等用户手工验收 |

- 依赖:仅 A→B→C 单向;每片合并后 main 均可用(A 合并=纯加列,B 合并=存量项目无感)。
- **建议**(待用户拍板):YPJ-110/111/112(多层嵌套)与本 spec 的关系——level 地基本票落,嵌套树/展示是独立后续;三票改挂 YPJ-105 下并改写描述(去重防两套实现),或保持独立由本 spec 引用。

## 五、手工测试用例(用户 build/debug 阶段,合并后 rebuild dev)

1. **新项目**:侧栏无 Sprint 段;类型管理加「Iteration」(timed,时间盒模板)→ 建 iteration → backlog 拉 item → 开始 → 自动挂 iteration;看板/月历正常
2. **存量 YPJ**:sprint 段/拉入退回/rollover/月历全部不回归(V48 后 sprint=level-1)
3. **NGX 无组实验**:无 level-1 → backlog=全部非终态、看板=补集+拉入,与现状一致
4. **release 项目**:Release 段与 release 流程不受影响(builtin 保留)
5. **负例**:建第二个 level-1 → 409;删有组的类型 → 409;`yz sprints create` 在无 level-1 项目报错引导

## Out of Scope

- 组嵌套树(YPJ-110/111/112)、侧栏数据驱动显隐票、milestone/release 开关票、release/milestone 去内置、displayName/icon、看板列语义、Calendar/Roadmap ViewType

## As-built(切片A,2026-11-06)

- **V48__group_type_level_timed.sql**(§三.2 SQL 原样落地,唯一索引为整列 `(project_id, level)` 版)
- **Entities**:ItemGroupType + `level`/`timed` + KDoc 更新;ItemGroup.type 去 Kotlin 默认(DB default 同步 drop;构造点 ReleaseService:141/ItemServiceUnitTest:349/ReleaseServiceUnitTest:62 均显式传 type,编译验证)
- **ItemGroupService**:GroupTypeDto/CreateGroupTypeRequest + level/timed;toDto 透传;createType 层级分配(最小未占正整数,删 level-1 后新类型回落补位;DataIntegrityViolationException → 409「层级并发冲突」);membershipIsHistory(key, typeKey)= builtin ∨ level==1(deleteGroup 调用点同步);builtinKeys 保留字检查暂留("sprint"/"release" 仍不可作新 typeKey,切片B 复议)
- entities.puml ItemGroupType + level/timed ✓;cleanDatabase 无新表不动
- 测试:单测 +2(自底向上分配/唯一竞态→409);集成 +1(V48 回填 sprint level=1/timed=true/builtin=false、release NULL、新类型 level=2/timed 透传)
- **实现决议①**:ProjectService.create 种子同步 V48 形状——sprint 种子= level-1/timed/builtin=false(新项目数据模型即刻一致;否则运行时种子与迁移回填形状分裂,自定义类型会被误分 level-1);切片B 去种子
- **实现决议②**:deleteType 守卫扩为 `builtin ∨ level==1`——既有「sprint 不可删」契约保持(集成用例断言在);level-1 删除政策(是否放开空 level-1 删除)归切片B 决议
- 验证:gradle build + integrationTest 绿(切片A 行为零变化判据)


## 切片B 拍板与 As-built(2026-11-05)

### 用户拍板(2026-11-05)
1. **可删性**:只看空非空(有组不可删,空即可删),level 不参与——空 level-1 也可删;守卫 = builtin ∨ 有组。
2. **builtinKeys 保留字废除**:createType 不再拒绝 "sprint"/"release";冲突由同项目 typeKey 唯一约束兜底(用户:实践中不会拿内建名当自定义名,但模型不阻止)。
3. **release/milestone 需求与设计暂缓**:用户重新思考后另写文档(docs/designs/ItemGroup.md);切片B 不动 release 种子与 workflow 模板。

### 实现(按 §三.3)
- ItemGroupService:deleteType 守卫改 builtin ∨ 有组;删 builtinKeys;新增 level1Key(pid) 唯一口径 + listLevel1Groups;listGroups 加 `level` 参数(controller 同步);board 拉入挂 level-1 active、退回无 level-1 时跳过
- GroupWorkflowService.template(type):release 分支挂 typeKey==release(builtin 兜底);timed → 时间盒模板(原 sprint 内容不变);否则三态 switch
- ProjectService.create:种子只留 release(builtin,level=NULL,timed=false)——**新项目不再有 sprint 段,用户「加 level」自配**
- ItemService:创建初始归属/hasLive/自动挂载/activity 归属判定 → level-1
- DashboardService → listLevel1Groups;MetricsRepository.level1TypeKey(key)(@Query)+ velocity 数据源改 level-1
- CLI:level1TypeKey(api,key) helper;sprints list/create/move、items assign 均寻型 level-1,无 level-1 明确报错「项目未配置 level-1 组类型(Web 类型管理加 level)」;命令面不变
- api.http:group-types 区块补 timed 建型 / `?level=1` / 负例(重名 409、删 release 409、删空 level-1 204)

### 测试
- 单元:ItemGroupServiceUnitTest(sprintType fixture 改 V48 形状 builtin=false/level=1/timed=true;新增 deleteTypeAllowsEmptyLevel1;内建冲突测试改重名 409)、GroupWorkflowServiceUnitTest(sprint fixture timed=true)、ProjectServiceUnitTest(种子 2→1)、DashboardServiceUnitTest(listLevel1Groups)、ItemServiceUnitTest(groupTypes mock + level stub)
- 集成:AbstractApiTest.createProject 补种 timed level-1 sprint 类型(=V48 存量形状,既有用例语义零改);ItemGroupApiTest(sprint 删类型 204 + release 409;重名 400→409;新增「新项目不再种子 sprint——只种 release」)
- 验证:`gradle test` 326 绿 + `gradle integrationTest` 218 绿

## 切片C As-built(2026-11-06)

### 实现(按 §三.4)

- `web/lib/sprints.ts`(唯一数据源,其余页面自动跟随):`listSprints` → `GET /groups?level=1`;新增 `level1TypeKey(key)`(GET /group-types 解析 `level===1`,缺失抛 `t.sprints.noLevel1` 引导文案);`createSprint` 的 `typeKey`、`assignSprint(null)` 的 `groupType` 均走该解析——`"sprint"` 字面量×3 全数清除;assign 路径(带 groupId)不再传 groupType(后端自证类型)
- `web/components/GroupTypesPanel.tsx`:建类型表单加「时间盒」勾选(= `timed`,模板由后端按 timed 选);列表加 `L{n}`(primary 色)与「时间盒」徽标
- `web/lib/texts.ts`:文案外置(`groupTypes.timed` / `sprints.noLevel1` / hint 更新为 level-1 语义)
- 侧栏 `layout.tsx` 零改动(§三.4 约定:显隐逻辑不动,数据驱动显隐=侧栏票);`sprint/[id]` 页零改动

### e2e

- `projects.spec`:新项目(有 level-1 类型、无组)侧栏「暂无 Sprint」空态断言
- `group-types.spec`:全链用例——删 fixture 补种的 sprint 类型 → UI 建 timed「Iteration」(验 L1 徽标 + 时间盒模板 workflow)→ 侧栏建组(走 level-1 解析);踩坑:`getByText` 大小写不敏感,name/typeKey 仅大小写差时须 `exact: true`
- 全量 67 例绿(本地 8082 test 容器);存量回归由既有套件覆盖(fixture 各用例补种 sprint 类型 = level-1)

### 与设计偏差

- `GroupTypesPanel` 未做「模板选择」独立控件——`timed` 勾选即模板选择(后端按 timed 种模板),§三.4 的两项合为一项
