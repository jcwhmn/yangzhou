# 0115 — ItemGroup workflow 可编辑:transition label/icon 配置化(YPJ-164)

> 来源:spec 0114 §四 R2,2026-11-09 用户拍板「立新票,先做配置」→ YPJ-164;163 只收 D1+R1。
> **⚠ 架构标注(置顶)**:本票把分组类型 workflow 从「建类型种子固化、运行期只读」(spec 0097 既有形态)变为「**运行时可编辑**」。安全边界 = **状态名集合与 statusType 锁死**(START/ACTIVE/FINAL 是 0111 联动推导、叶层判定、处置链的语义锚)——unlock 即组状态悬空。API 契约向后兼容:GET 形状不变,`transitions` 项新增**可选** `label`/`icon` 字段;新增 PUT 端点。

## 一、需求

### 范围

- 分组类型 workflow 的**出边(transitions)**可运行时配置:
  - 每条出边可覆写 `label`(中文显示名)与 `icon`(符号);缺省回退前端既有全局映射(`wfFnLabel`),存量零迁移、零行为变化
  - 某状态的出边可增/删/重排(function 限 `GroupActionFn` 注册表,target 限本类型状态集)
  - 「分组类型管理」面板 workflow 行从只读变为可编辑(含只读行的 icon 展示,即原 R2a)
- 状态集与 statusType **锁死**:不可增删状态、不可改 statusType(PUT 请求形状不承载,且服务端校验名称集合严格相等)

### 非目标

- 状态名/statusType/position 的编辑(语义锚,锁死)
- 注册表外的自定义 function(「出边即真相」预留语义不变,`applyAction` 层不受影响)
- sprint 页动作按钮文案接 edge label(按钮文案按 statusType 模板「开始{类型名}/完成{类型名}」;要接另立票)
- 侧栏树/详情页等其它消费点接 label/icon(本票最小面 = 面板只读行 + 编辑态)
- workflow 版本历史/审计/撤销

### 现状(为何做)

transitions 建类型时由后端种子固化(`group_status.transitions` JSON 依附状态行),无编辑 API、无编辑 UI;fn 中文名是 `GroupTypesPanel.tsx` 本地 `wfFnLabel` 映射,icon 不存在——即「月 workflow 行看不到/配不了」的根源。

## 二、详细设计

### A 后端(`GroupWorkflowService.kt` + `ItemGroupController.kt`)

1. **DTO(小切片同文件,吵了再拆)**:
   - `GroupTransitionDto` 增 `val label: String? = null, val icon: String? = null`(`toDto` 透传;GET 缺省为 null,契约兼容)
   - 新请求形状(顶层 JSON 数组,同 0110 处置提交风格):`StatusTransitionsRequest(name, transitions: List<TransitionRequest>)`;`TransitionRequest(function, target, label?=null, icon?=null)`
2. **`Edge` 增 label/icon 可选字段**;`parseTransitions` 用既有 `readTree` 按键取值,新增键天然容忍(已核实现实现);`applyAction` 不动(仍按 function/target 匹配与意图校验)。
3. **新 `@Transactional fun updateWorkflow(key, typeKey, requests): List<GroupStatusDto>`**(GroupWorkflowService,注入 `LeaderGuard`,同 `ItemGroupService.action` 模式):
   - `leaderGuard.requireLeader(key)` → 非 leader 403
   - 解析 type(项目内 typeKey,404 同既有 workflow GET)
   - 服务端集中校验(fail-fast 业务异常):
     - `requests` 状态名集合与现状态集**严格相等**(不多不少)→ 否则 409「状态集不可增改」
     - 每边 `target` ∈ 本类型状态名集 → 409
     - `function` ∈ `GroupActionFn` → 409「未注册动作函数」
     - 注册表意图**静态预检**(sourceType/targetType 规则同 `applyAction`)→ 409;防配置出「点了必 409」的死边
     - `label` ≤20 字符、`icon` ≤4 字符 → 400(对齐 GlobalExceptionHandler 既有校验形状,实现时落)
   - 每状态行 `copy(transitions = 序列化 JSON)` save(label/icon 仅非空写入);name/statusType/position 不触碰
   - 返回 `workflow(type)`(同 GET 形状)
4. **Controller**:`@PutMapping("/projects/{key}/group-types/{typeKey}/workflow")`,`@RequestBody List<StatusTransitionsRequest>`,返回更新后 workflow 列表。
5. **否决的备选**:
   - 请求包 wrapper 对象 `{statuses:[...]}` + Bean Validation 嵌套校验:顶层 List 元素校验需容器元素注解样板;校验规则本就多为跨行语义(集合相等/注册表/意图),集中在 service 一处更短更可测
   - PATCH 增量边语义:全量替换与 0110 风格一致,幂等,免 diff 逻辑
6. **api.http**:0114 区块 GET workflow 后补 PUT 节(**自含**:节内建类型→PUT→GET 回读),负例区补:多/少状态 409、target 越界 409、未注册 function 409、意图违规 409、label 超长 400(只在 test 8082 跑)。

### B 前端(`web/components/GroupTypesPanel.tsx` + `web/lib/texts.ts`)

1. 类型:`transitions` 项增 `label?: string; icon?: string`。
2. 只读行:`icon`(有则前缀)+ `(tr.label || wfFnLabel(tr.function))` + ` → target`(原 R2a 并入)。
3. 编辑态:每类型 workflow 块头「编辑」按钮 → 出边行内编辑:
   - function Select = **英文函数名**,数据字典 `web/lib/groupWorkflow.ts`(`WF_FN_DICT`,与后端 `GroupActionFn` 注册表对齐:fn/中文 label/预定义 icon 三元组,唯一来源)
   - label/icon **预填字典值**(进编辑态对存量缺省边预填;换 function 即重预填,均可改;保存落库)
   - icon = **预定义下拉**((无) + 7 个字典符号),不自由输入
   - target Select = 本类型状态;状态级「加出边」(每状态可多条出边)、边级「删」
   - 保存走 PUT → 重拉 workflow;取消还原
   - 已知行为(数据驱动,用户自负):删掉某边后,sprint 页对应动作按钮点击将 409(按出边 fn 路由);UI 不拦。
4. 新文案键入 `web/lib/texts.ts`(中文优先,文案外置)。
5. **release 类型不进面板**(release 非用户管理的分组类型,专属功能域;2026-11-09 用户明确)。保存成功即回 W-RO,定义好的 transition 直接显示于面板只读行,无需进编辑态查看。
6. **否决**:独立 workflow 编辑对话框——面板已有类型/状态上下文,行内编辑少一层状态同步。

#### B-1 操作流程(状态×动作真值表)

面板态:只读 **W-RO**(默认)/ 编辑 **W-EDIT**(单类型粒度,同时至多一个类型处于 W-EDIT)。草稿生命周期 = 对话框会话,关闭即弃(不拦截,如需关闭前确认另行拍板)。

| 当前态 | 动作 | 行为 |
|---|---|---|
| W-RO | 点类型头部「编辑」icon | 该类型 → W-EDIT;若有他类型已在编辑,先静默取消之 |
| W-EDIT | 改 function/target 下拉、label/icon 输入、加/删出边行 | 仅改本地草稿,不落库 |
| W-EDIT | 「保存」 | PUT 全量(该类型全部状态+出边)→ 200:重拉 workflow,回 W-RO;409/400:块底错误条,停留 W-EDIT |
| W-EDIT | 「取消」 | 丢弃草稿,回 W-RO |
| W-RO/W-EDIT | 关闭对话框 | 直接关闭;W-EDIT 未保存草稿丢弃 |

#### B-2 页面示意图(ASCII,贴合 Dialog 实况:maxWidth sm、List dense、workflow 块 pl:2)

只读态(打开默认;出边行 = icon 前缀 + label‖wfFnLabel → target):

```text
┌─ 分组类型管理 ───────────────────────────────┐
│ (说明 caption)                               │
│ ● sprint    [chip][chip]                  ✏  │ ← 类型头部:名称+chips+编辑 icon
│ │   planned (START)    开始 → active         │
│ │   active  (ACTIVE)   完成 → completed      │
│ │   completed (FINAL)   重开 → active        │
│ ● month     [chip]                        ✏  │
│ │   planned (START)    切换 → active         │
│ │   …(出边以实际种子为准,示意)              │
│ 新建类型:[typeKey] [名称] [创建]              │
│                                [关闭]        │
└─────────────────────────────────────────────┘
```

编辑态(仅 sprint 类型展开;每出边一行 4 控件,状态行保持只读):

```text
│ ● sprint    [chip][chip]        [保存] [取消] │
│ │   planned (START)                           │ ← 状态行(只读,不可增删/改 statusType)
│ │     [fn▾ start]    [target▾ active]      [label: 启动] [icon▾ ▶] [删] │ ← 出边 1
│ │     [fn▾ switch]  [target▾ active]      [label: 切换] [icon▾ ↔] [删] │ ← 出边 2(同状态可多条)
│ │     [＋ 此状态加出边]                        │
│ │   active (ACTIVE)                           │
│ │     [fn▾ complete][target▾ completed]   [label: 完成] [icon▾ ✓] [删] │
│ │     [＋ 此状态加出边]                        │
│ │   ⚠ 错误条(PUT 409/400 时显示于此,如「状态集不可增改」) │
```

### C 测试

- **集成** `GroupWorkflowApiTest.kt`(新,extends `AbstractApiTest`;自定义类型测试数据**直建项目**绕开 `createProject` helper 补种 sprint 占 L1 的基类约定,同 D1 踩坑记录):
  1. happy:自定义类型 PUT(label/icon 覆写+新增边)→ 200;GET 回读反映;sprint 内置类型 GET 不带 label(缺省 null,契约兼容)
  2. 状态集多/少一个 → 409(安全边界主测)
  3. target 越界 → 409
  4. 未注册 function → 409
  5. 意图违规(complete 边指向 START 态)→ 409
  6. label 超长 → 400
  7. 非 leader → 403(样式对齐 `LeaderPermissionApiTest`)
  - 若实现时发现仓内已有 service 单测样式(mock repo),校验分支可下沉单元;无则集成全包(端点契约为主,符合「集成只留关键路径+契约边界」)。
- **e2e**(web/e2e,非 @smoke):优先入既有类型面板相关 spec(实现时对齐文件名,勿重复建),否则新建 `group-workflow-config.spec.ts`——唯一 key 项目 API 预置自定义类型 → 面板编辑 label/icon → 保存 → reload 后只读行显示自定义 label;MUI 对话框断言前 `toBeHidden()`。
- 无新表/无迁移 → cleanDatabase 清单不动。

## 三、手工测试用例(用户 build/debug;前置 `script/app-infra.ps1 build test` + rebuild dev)

- **T164-1**:test(8082)api.http 新 PUT 节 + 负例区全绿。
- **T164-2**:dev 面板 → sprint 类型 workflow 行显示既有出边(中文 fn 名)→ 编辑「开始」边 label=「启动」icon=「▶」→ 保存 → 行显示「▶ 启动 → active」→ 刷新页面持久。
- **T164-3**:编辑态给自定义类型加出边(complete → completed)→ 保存回读一致;sprint 页既有动作按钮行为不变。
- **T164-4**:非 leader 账号调 PUT → 403(UI 面板入口本就 leader 门控,以 API 负例验证)。
- **T164-5**:回归——月类型完成处置链(D1 场景)不受 PUT 影响(PUT 不触 statusType)。
