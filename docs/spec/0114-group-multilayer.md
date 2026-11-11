# 0114 — ItemGroup 多层嵌套推广(封顶 3 层)(YPJ-163)

> 2026-11-15 立。主票 YPJ-163;110(Epic)收尾条件。拍板记录:封顶 3 层(用户 2026-11-15「测试一个三层的就可以了」);item 只挂叶层(上层成员=下层并集);feasibility 红黄绿逐层递归聚合到顶;163 暂不挂 sprint。

## 一、需求

### 范围

项目组层级从固定两层(Epic>Sprint)推广到 **3 层**(如 Epic>Feature>Sprint),复用既有两层语义逐层外推:

1. **类型层级**:沿用「自底向上补位」(首个类型=level 1 叶层,第二个=level 2,第三个=level 3);**封顶 = 单项目 level 类型的层级数 ≤3**(第 4 个 400)。builtin(release,level=NULL)不占位不参与。
2. **建组不变量泛化**(0112 N=2 → N=3):组类型 level 为 L 时,**若项目存在 level>L 的类型**,则该组必挂父,且父组类型 level 须恰为 L+1(父组自身的父由其层位自洽,不要求无父)。无更上层类型 → 该层即顶层,不带父(带则 400)。
3. **状态联动泛化**(0111):非叶层组状态恒由子组联动推导,**不开放手动动作**(409);红黄绿聚合从「一层」改「逐层递归到顶」(L1 → L2 → L3)。
4. **item 挂载**:维持「item 只挂叶层(level-1)组」;非叶层挂 item 409(语义=上层成员是下层并集,不单独收 item)。
5. **组改父泛化**(0113):类型 level L∈{1,2} 的组可改挂(顶层无 L+1 类型不可改,400);目标=类型 level L+1 的组(其自身的父随层位自洽);**子树随迁**(改挂中层组时其子组整体跟随);严格邻层约束使环不可能(L+1 目标不可能是 L 组的后代),无需环检测。新旧父链逐层重算状态。
6. **Web**:侧栏树递归化(N 层缩进+开合);新建入口「当前父组」泛化;sprint 详情页「所属 Epic」→「所属父组」(按组类型 level 给出 L+1 候选);处置流(0110)目标父组泛化。

### 非目标

- 异形层级(同类型多父/任意层跨挂)——本票只做严格树 + 邻层约束;「异形评估」在 110 收尾时另议。
- 上层独立挂 item、跨层看板、metrics 除递归聚合外的新语义。
- builtin 类型(release)参与层级树(维持 level=NULL 旁路)。
- 存量数据迁移(两层项目零迁移;3 层是新配置路径)。

## 二、详细设计

### A 切片:后端泛化(`backend/api/.../itemgroup/ItemGroupService.kt` 为主)

硬编码点逐一处理(现行 → 泛化):

| 位置 | 现行 | 改为 |
|---|---|---|
| `createType`(自底向上补位) | 无上限 | 补位结果 >3 → 400「层级封顶 3」 |
| `createGroup` | hasParentType=存在 level-2;叶层必挂父;父类型须 level 2 | `parentTypeLevel(L)=L+1`:存在 level>L 类型 → L 层组必挂父;父组类型 level 须=L+1(父组自身的父自洽,不要求无父);无上层类型而带 parentGroupId → 400 |
| `actions` | `level==2` 409 | `t.level != null && t.level > 1` → 409(状态由子组联动) |
| `group-membership` | `level==2` 409 | 同上判式 → 409(item 只挂叶层) |
| `reparent` | 仅 level-1 可改;自挂判 level==1;目标须 level 2 | L=t.level:存在 L+1 类型才可改(否则 400「顶层不可改挂」);目标组类型 level=L+1 且目标无父;子树随迁(仅动该组 parent_object_id,子组指向不变,天然随迁);新旧父**链上逐层**重算状态(改循环到顶) |
| `membershipIsHistory` | builtin 或 level==1 不可删 | 不动(叶层语义不变) |
| 类型删除 | 类型下有组 409(既有) | 不动;新增 guard:删 level-2 类型时若存在 level-3 类型 → 409(避免「上层在、中层类型亡」死配置) |

- `recomputeParentStatus` 链式化:沿 parent 链向上循环重算至顶层(每层聚合一层子组,红黄绿规则不变)。
- DTO 注释同步措辞(level-2 → 父层泛化)。
- `listParents`(0113)泛化:入参组 → 返回其类型 L+1 层的无父组列表。
- `board`/level-1 谓词:不动,叶层语义不变。

### B 切片:侧栏树递归化(`web/lib/sprints.ts` + `app/p/[key]/layout.tsx`)

- `listGroupTree`:两层桶(`parents[]` + `childrenByParent`)改递归结构 `nodes:{sprint,children[]} + orphans`(悬空叶层仍平面区)。
- 侧栏渲染:递归组件,缩进按深度;`expanded` 沿用 0112(只加不移,active 链默认展开);`syncCurrentParent` 泛化:叶层 active → 其父链。
- 新建入口:**仍只在叶层段头**(建中层组走 API 手测路径,UI 只展示+叶层新建),控制本次 UI 面。3 层建组顺序:L3 组 → L2 挂 L3 → L1 挂 L2。

### C 切片:sprint 页/处置流(`web/app/p/[key]/sprint/[id]/page.tsx` + 0110 处置)

- 详情页选择器:候选=listParents(按组类型动态);label「所属 Epic」→「所属{父类型名}」(texts.ts 外置)。
- 0110 处置弹窗目标 epic:候选生成沿父链泛化(两层项目行为不变)。

### 否决的备选

- **开放 N 层**:UI 心智/缩进/选择器级联成本陡增,3 层封顶(用户拍板)。
- **环检测**:严格邻层约束下数学上无环,不加代码。
- **侧栏全层新建入口**:本次不做(见 B),控制面。

## 三、手工测试用例(用户 build/debug 阶段执行)

前置注意自底向上语义:**第一个类型=叶层(Sprint),第二个=中层(Feature),第三个=顶层(Epic)**。

- **T163A 建三层主路径**(dev):建 3 类型 → 建 Epic 组 → 建 Feature 挂 Epic → 建 Sprint 挂 Feature → 侧栏三层树缩进/开合正确 → board 只显示 Sprint
- **T163B 状态逐层联动**:Sprint planned→active → Feature 与 Epic 状态均按 0111 规则联动上传;Sprint 移走 → Feature 回规划中 → Epic 回规划中
- **T163C 改父泛化**:Feature 从 Epic1 改挂 Epic2 → 子树(Sprint)随迁,Epic1/Epic2 状态联动;Sprint 跨 Feature 改挂 → 侧栏即时更新
- **T163D 负例**(api.http 0114 区块):第 4 个类型 400;Sprint 建漏父 400;Feature 建漏父 400;越级(Sprint 直挂 Epic)400;Epic 改挂 400;Epic 手动动作 409;Feature 挂 item 409;删含子组 409;删中层类型(上层存在)409
- **T163E 两层回归**:DEMO1107 行为不变——侧栏两层树、新建必挂父、改父、处置流照旧
- **T163F 平面项目回归**:单类型项目侧栏平面区/Backlog 不变



## 四、设计增补 v2:无限层级 + workflow/transition/action 前端配置 + 完成链绑定(2026-11-09,待用户过目,未编码)

> v1 已按用户三点反馈作废重写:①无层级封顶(测试数据恰 3 层,非系统约束);②前端 action/transition 配置入口是关键需求;③完成逻辑两层与多层本质同链,直接改原逻辑。

### R1 层级语义修正:无限层级(推翻「封顶 3」拍板)

- 需求:单项目类型层数不设上限;**邻层不变量(组挂父须恰为自身类型 level+1)、叶层挂 item、非叶禁手动动作、删中间链 409、逐层级联**均与层数无关,保持不变,天然支撑 N 层。
- 改动点(实现时,共 4 处):
  1. `ItemGroupService.createType` L185-186:删 `level > 3` 封顶 400;
  2. `GroupMultilayerApiTest.三层建组链成功且第4类型封顶400`(L101):改为「第 4 类型 201,4 层链邻层自洽」正例;
  3. `backend/script/api.http` L1736 负例节:同步改正例;
  4. spec 0114 头部拍板记录/§一/§三 T163D 措辞 + 标题「(封顶 3 层)」随 As-built 一并修正(注明 2026-11-09 用户改拍板为无限层级)。
- e2e 无封顶断言(已核),web 侧栏树/新建挂父候选均递归/按 L+1 动态,无需改。

### R2 action/transition 的前端配置入口(关键需求,主设计)

**现状回答:前端没有任何设置处。** transitions 建类型时由后端种子固化(`group_status.transitions` JSON,依附状态行);前端「分组类型管理」只有只读 workflow 行;**无编辑 UI、无编辑 API**。fn 中文名是前端全局映射(`texts.ts wfFnLabel`),icon 不存在。这就是「看不到/配不了」的根源。

**设计(补齐配置链路,契约变更)**:

- 数据模型:`transitions` JSON 项扩展为 `{function, target, label?, icon?}`——缺省字段回退全局映射,**存量零迁移**。
- 新 API:`PUT /api/projects/{key}/group-types/{typeKey}/workflow`,body = `[{name, transitions: [{function, target, label?, icon?}]}]`(按状态全量替换,同 0110 处置提交风格)。
  - 校验:状态名集合与 statusType **不可增改**(START/ACTIVE/FINAL 是 0111 联动推导与叶层判定的语义锚,锁死);`target` 必须属本类型状态集;`function` 必须命中 `GroupActionFn` 注册表;label/icon 长度上限(如 20/4)。
  - 权限:leaderGuard(同动作端点);api.http 0114 区块同步补节。
- 前端:「分组类型管理」面板每状态行加编辑态——出边列表行内编辑(function 下拉=注册表中文名/目标态下拉/label/icon 输入),保存走 PUT;文案外置。
- ⚠ **架构标注**:工作流从「建类型种子固化」变为「运行时可编辑」——锁定状态集与 statusType 是安全边界,否则组状态悬空、联动推导失效。此改动契约+UI 面大,**建议独立成票**(R2b),163 先收 D1+R1+只读 icon(R2a:面板只读行补 icon 映射 `↔ 切换 → active`,零后端)。**待你拍板:163 内做 / 立新票**。

### R3 完成逻辑同一性(回答,无新增改动)

- **原两层 complete 逻辑位置**(0105→0110 演进,唯一一套):前端 `web/app/p/[key]/sprint/[id]/page.tsx` 的 `confirmDisposition()`/`doBacklogAll()`(决策树+提交)+ 后端 `ItemGroupService.action()`(状态迁移+处置同事务)。
- **≥3 层没有另一套逻辑**:163 切片C 只是把同一条链的「候选范围」(同类型/同父)与「function 解析」参数化;事务、幂等、回滚完全共用。多层没有独立 complete 分支。
- 「直接在原逻辑上改」正是 D1 所做:改该链两处硬编码 fn(激活目标组 `"start"`、完成/回池提交 `"complete"`)为按出边目标态 statusType 解析(`startFnOf()/finalFnOf()`),后端处置事务门禁 `function=="complete"` 字面量 → 「迁移至 FINAL」语义。无限层级下该链天然成立。

### D1(已实现未提交,3 文件,build+集成双绿;全量 e2e 上轮中断)

前端 fn 解析 + 后端门禁语义化 + `GroupDispositionTransactionApiTest` 新增 switch 完成携处置集成测试(直建项目使自定义类型恰 L1,绕开 helper 补种 sprint 占 L1 的基类约定)。

### 手工测试用例(用户 build/debug;前置 rebuild dev)

- **T163A-F1**:完成带 2 个未完成 item 的 active 月(同季有计划月)→ 处置弹窗目标=该月,确认 200,目标月进行中、item 迁入、原月 completed 仅剩 Done。
- **T163A-F2**:同季无候选 → 新建月挂同季,item 转入。
- **T163A-F3**:一行覆写回 Backlog → 该 item 入池,其余迁入(0110 事务经 switch 链)。
- **T163E**:两层 Sprint(Epic 下)完成处置照旧(start/complete 不变)。
- **R1 后**:第 4 类型创建 201、4 层树侧栏递归/联动正常。

### 待拍板

1. R2 配置化:163 内做 or 立新票(推荐立新票;163 先收 D1+R1+R2a 只读 icon)。
2. D1 是否按 v2 提交(我补跑全量 e2e 绿后 commit)。
3. 「面板看不到 workflow 行」现象确认(整块缺失 vs 缺动作概念/icon)——决定 R2a 之外是否另有只读展示缺陷要修。
