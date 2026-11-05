# 0105 — sprint 拉起弹窗:无活动 sprint 时建 item / 挂 planned 的激活提示(YPJ-104)

> 票面(2026-10-27 拍板):「先激活下一个 sprint,再关当前」已支持;缺的是无活动 sprint 时的便捷拉起入口。两个触发点弹窗提示,确认后一键 PATCH active;取消维持现状。非自动迁移。不改 API。

## 一、需求

### 触发点①:board 降级态建 item
- 条件:看板无 active sprint(activeSprints.length === 0)且项目存在 planned sprint;建 item 提交成功后
- 弹窗提示是否拉起(激活)planned sprint;取消 → 维持现状(sprint 仍 planned,item 落 backlog)

### 触发点②:挂 planned sprint
- 条件:详情页把 item 挂到 **planned** sprint 成功,且项目当前无 active sprint(挂 active / 移出 null 不弹)
- 弹窗提示是否顺带激活该 sprint;确认 → sprint 变 active,**item 随组立即可见于看板**(membership 不变,组状态决定可见性;无需任何迁移)

### 范围与非目标
- 范围:仅 web 交互;激活复用既有 `sprintAction(key, id, "start")`;API 零改动
- 非目标(票面拍板):存量/遗留 item 自动迁移;backlog 页(无挂 sprint 入口,非触发点);仅 planned=1 之外的特殊化

### ⚠ 品味拍板点(触发点①确认后的 item 去向,两案请拍板)
刚建的 item 已落 backlog(无 sprintId);确认激活后:
- **方案A(严格票面「一键 PATCH active」)**:只激活 sprint。看板收窄到该 sprint → 刚建的 item 从看板消失,需去 backlog 查看。文案须明说,否则「建完就不见了」。
- **方案B(推荐)**:激活 sprint **并把刚建的 item 挂入该 sprint**。item 立即可见于看板,操作连贯。「自动迁移」非目标针对存量 item;本次刚建的 item 是当前操作对象,不属遗留迁移。
- 共同点:多 planned sprint 时弹窗列出全部供选(与多 active 的「放入哪个 sprint」弹窗同构);取消均维持现状。

## 二、详细设计

### `app/p/[key]/page.tsx`(触发点①)
- `handleSubmit`:现有分支不动;`createItem` 成功且 `activeSprints.length === 0 && sprints.some(planned)` → 记录 `launchState`(含刚建 itemId),打开 `launchOpen`
- 新 Dialog(结构照既有 `sprintPickOpen` 弹窗):
  - 单 planned:文案「项目还没有活动 sprint——要激活 planned sprint「{name}」吗?」+ 确认(激活)/取消
  - 多 planned:列表选择「要激活哪个 sprint?」+ 确认
- 确认动作:`sprintAction(key, pick, "start")` → 方案B再加 `assignSprint(key, 新itemId, pick)` → `load()`;失败走既有 `setError`
- 弹窗期间 item 已持久化;关闭不清 title

### `app/p/[key]/i/[itemId]/page.tsx`(触发点②)
- `applySprint(sprintId)` 成功后:`target = sprints.find(s.sprintId === sprintId)`;`target?.status === "planned" && !sprints.some(s => s.status === "active")` → 打开确认弹窗(单 sprint,无需选择)
- 确认:`sprintAction(key, sprintId, "start")` → 重拉 item + sprints(sprint 状态标注更新;item 无需迁移)
- 取消:什么都不做(挂载已生效,仍 planned)

### 文案
- `texts.ts`:`board.launchTitle` / `board.launchBody` / `board.launchConfirm` / `board.launchPickTitle`;`item.launchAsk` / `item.launchConfirm`。中文,外置。

### e2e(`web/e2e/sprint-launch.spec.ts`,新文件)
1. `@smoke`:fixture 独享项目;API 建一个 planned sprint(level-1 类型)→ board 输入标题建 item → 断言弹窗出现 → 确认 → 断言 sprint 段/看板出现 active(方案B再断言新建 item 在看板可见)
2. 触发点②:API 建 planned sprint + item → 详情页 Dropdown 挂 planned → 弹窗 → 确认 → 断言 sprint 标注变 active
3. 取消路径:弹窗取消 → sprint 仍 planned(board 提示条仍在)
- 弹窗淡出期注意 `toBeHidden()` 后再点下层(MUI 惯例)

## 三、手工测试用例(用户 build/debug 阶段)

1. **回归**:项目无 planned sprint 时建 item → 无弹窗,行为与现在完全一致
2. **触发点①·取消**:建 planned sprint → 看板建 item → 弹窗 → 取消 → sprint 仍 planned,item 在 backlog
3. **触发点①·确认**:再建一个 item → 弹窗 → 确认 → sprint 变 active;方案B:刚建 item 在看板可见 / 方案A:在 backlog
4. **触发点②·确认**:详情页挂 planned sprint → 弹窗 → 确认 → sprint active,item 随组在看板可见
5. **触发点②·取消**:挂 planned → 弹窗 → 取消 → 挂载生效(sprint 标注 planned),sprint 未激活
6. **不弹场景**:挂 active sprint;移出 sprint;有 active sprint 时建 item——均无弹窗

## 增量(2026-11-06,手工验证中发现):看板建 item 必进看板

> 用户重申既有拍板:在看板建的 item 必进看板,同时参考 sprint 状态决定去向(有 active → 进 active sprint;无 active → 不挂 sprint 但看板可见)。

- **根因**:0098(2026-11-05)改了 board 降级分支为「全量 − backlog 桶」,前端 board 建item 还是 0098 之前世界的语义(「不带 sprintId 落 backlog,与看板全量显示自洽」注释)→ 看板建的 item 隐身进 Backlog、看板不可见。T104Z(预置无组项目)实测照亮。
- **修复(前端单点,后端零改动)**:board 建item 无 sprintId 分支,建成功后 `PUT /api/items/{id}/board-membership {onBoard:true}`(0099 端点;无 active 时后端只置标志不挂组,实锤 ItemGroupService.kt:431-453)。与 0098 不变量自洽:onBoard=true 被 backlog 谓词排除 → **board ∩ backlog = ∅ 仍成立**,item 在看板可见、Backlog 页不可见。
- **文案对齐 0098 端点语义**(0101 案B 遗留失真):board.noGroups「以下显示全部非终态 item」→「以下显示不在 Backlog 中的 item」(与 noActiveSprint 同口径)。
- **0105 弹窗语义随之更顺**:触发点①「暂不」→ item 仍 onBoard 在看板可见(不再隐身);「激活」→ 挂入 sprint。触发点②不受影响(挂 sprint 的 item 天然看板可见)。
- **e2e**:sprint-launch 用例 2 断言改看板可见;新增无组项目用例(建item 不弹 + onBoard 可见 + Backlog 不可见)。
- **欠账提示(另票,不扩本票 scope)**:0098 增补的「根页无 active 重定向 Backlog」在修订二已撤(根页恒看板),但看板降级视图当前无显式「拉入」提示,Backlog 页的拉入入口承担此职——观感另行观察。


## 增量二(2026-11-06,拍板升级):看板建 item 必进 level-1 组——0 进行中时激活/新建组

> 用户拍板(2026-11-06):只要有非完成态的 level-1 组,item 必须进组;不能出现「item 游离(在看板+无归属)」。术语遵循 0101 案B:需求与 UI 文案一律用「组」,不出现特定组类型字样(数据层状态字面量与实现层 typeKey 例外)。

### 一、场景矩阵(看板建 item 时刻)

| # | 项目状态 | 行为 | 交互 |
|---|---|---|---|
| S0 | 无 level-1 组类型 | item 建后 onBoard=true 上看板;全站无组类型字样(0101 铁律) | 无弹窗 |
| S1 | 进行中组=1 | 挂该组 | 无弹窗(现状) |
| S1m | 进行中组≥2 | 弹窗选一个挂入(YPJ-56 现状) | 既有弹窗 |
| S2 | 进行中=0,计划中=1 | **激活该组 + 挂入** | 确认弹窗:「激活「计划中B」并放入?」按钮=「激活并放入」 |
| S2m | 进行中=0,计划中≥2 | 弹窗列表选一个 → 激活+挂入 | 同上 |
| S3 | 进行中=0,计划中=0(实例全完成或 0 实例) | **新建组实例(默认名 = 类型名+序号,如「Sprint 3」)→ 激活 → 挂入** | 弹窗带名称输入框(默认名可改)+「创建并激活」 |
| — | Backlog 页就地建 item | 零成员关系入池(现状,V13-S4) | 不适用本语义 |

- 状态字面量对照:planned=计划中 / active=进行中 / completed=已完成(组状态,值域由类型 workflow 定义;文案用中文,数据层保留字面量)。
- **取消语义(S2/S2m/S3)**:弹窗「取消」= **放弃整个创建**(item 不建;标题保留在看板输入框)。游离不再是合法落点,故无「暂不」。
- **S2 为什么保留确认步**:激活会把项目从「无进行中」切到「有进行中」(看板收窄),属可见状态变化,防误触;非游离问题。
- S3 默认名 n = 该项目 level-1 组实例数+1,前缀取类型显示名(类型配置什么就叫什么,不硬编码);提交前查组列表,重名自动递增(组名无唯一约束,重名不报错)。

### 二、失败路径分析(核心决策:组先行,item 后行)

执行链(S3 为全链,S2 从激活步起):**建组 → 激活 → 建 item(带 groupId)**。

| 失败点 | 落点 | 游离 item? |
|---|---|---|
| 建组失败(网络/权限) | item 未建;标题保留;重试 | 无 |
| 激活失败 | 组在(起点态)、item 未建;重试时同名校验跳过建组 → 走激活+挂入 | 无 |
| 建 item 失败 | 组已激活(空进行中组合法);重试 | 无 |
| item 挂入失败 | **不可能单独发生**:后端 create() 中 groupId 校验+membership 同事务,负例不落库(实锤 ItemService.create) | 无 |

- **防重**:提交前 GET 组列表比对名字,已存在同名组 → 复用(不重复建),继续激活+挂入。
- 并发边界(标注不处理):两用户同名并发建组 → 同名两组;多进行中组由 S1m 弹窗兜底,不致命。
- 自定义 workflow 边界(标注):level-1 类型若自定义 workflow 且起点→active 出边缺失,激活 400 → 降级:组留起点态、item 挂入(补集看板仍可见),报错提示。v1 不特判自定义 workflow。

### 三、e2e 与手工

- 每场景一例;预置数据(dev 8081,已建):T104S1(进行中组「进行中A」)/ T104S2(计划中「计划中B」)/ T104S2X(计划中×2)/ T104S3(已完成「已完成C」)/ T104Z(有类型 0 实例)/ T104NT(无类型)。
- 增量一的 onBoard 分支仅剩 S0 在用。

### 四、开放问题(过目时定)
1. S2/S3 是否保留「放入但不激活」次选项(现设计:无,强制激活)。
2. 自动命名「类型名+序号」规则。
3. S0(无类型)维持 onBoard 现状——0101 铁律,确认即可。
