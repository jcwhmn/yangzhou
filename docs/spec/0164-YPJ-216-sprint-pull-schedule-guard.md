# spec 0164 — YPJ-216 sprint 拉入排期硬校验 + 依赖感知占位自动排期

> 8h。票面真值表(已重写详化,2026-11-22 用户过目)为准;本 spec 记实现计划与两处实现层补充。

## 一、需求

票面(YPJ-216)§需求/§真值表/§验收为准,不重复。核心:拉入 sprint(groupType=timed level-1)时——①无预估 409;②start 决策序 = 显式 > 前置可推(最晚前置 due+1 顺延工作日)> 今天 > 前置全未排 409;③due = WorkdayCalculator.dueFrom 铺算(显式 due 覆盖);④响应回填。

## 二、实现计划

- **共享入口**:`ItemService.applySprintPullSchedule(item, explicitStart?, explicitDue?): Item`——三条拉入路径共用:
  1. `ItemService.create`(sprintId 直挂,group 为 level-1 且 timed)
  2. `ItemGroupService.replaceMembership`(target 为 timed level-1 组)
  3. `ItemGroupService.boardMembership`(看板拉入自动挂 sprint 路径)
- 校验/决策全在入口内:estimate 硬校验(ConflictException)→ start 决策序 → due 铺算 → save + `dates_changed` 活动留痕。
- **工作制口径**:workMode=work → 前置顺延/今天钳制走「下一工作时刻」helper(周末跳到周一窗起;超窗同理);wall = 钟表日界(00:00 起)。
- **两处实现层补充(票面未显式,此为补全)**:
  - item **已有 startDate** → 视为已显式排期,保留不重排(预估硬校验仍执行;due 缺失时补铺算)
  - 触发条件用 **groupType.timed**(创建路径同时要求 level-1)——比 typeKey=="sprint" 字面量更稳,自定义 timed 组同纪律
- 409 不带自定义 code 字段(ConflictException 现无 code 通道,消息区分两种拒绝;统一错误形状不变)。

## 三、测试

- 集成(ItemSprintPullScheduleApiTest 或并入既有):票面验收 ①–⑥ 全覆盖。
- api.http:assign 三态段(无预估 409 / 正常回填 / due 覆盖)。
- 单测:无需新文件(决策逻辑薄,经集成覆盖;铺算本体 WorkdayCalculator 已有单测)。

## 四、手工测试用例(用户 build/debug)

1. `yz items assign` 一张无预估票 → 409
2. 有预估票(周五 due 前置已排)拉入 → start=下周一窗起、due=铺算
3. 显式 start/due 拉入 → 双显式生效
4. 甘特计划条立即出现(sprint 视图)

## 五、拉入 sprint 四途径与期望行为(2026-11-23 用户澄清,需求级)

> 把 item 从 backlog 拉入 sprint 共 4 个用户入口,全部最终走同一守卫 `applySprintPullSchedule`。
> 下表为每途径的入口→端点→期望行为;守卫本体规则见 §一/§二,增量决策见 §六。

### 途径 ①:item 详情页选 Sprint(下拉)

- 入口:item 详情常驻区「Sprint」下拉,选目标 sprint(移出=清空下拉)。
- 端点:`PUT /api/items/{id}/group-membership`(assignSprint)→ `replaceMembership`。
- 期望:
  - 票无预估 → **409,前端 toast/表单错误浮出原文**(「拉入 sprint 须先填预估时长…」),不挂组、不铺日期;
  - 挂 planned sprint → 挂载生效后弹拉起窗(YPJ-104:激活/暂不);挂 active → 直接进看板;
  - 守卫铺算/保留日期规则同 §一;
  - 幂等重选同一 sprint(已是成员)→ 不重复挂组,但仍过守卫(补铺/409,§六-2)。

### 途径 ②:backlog 页 item 行「拉入看板」(单票按钮)

- 入口:backlog 列表行内「拉入看板」按钮。
- 端点:`PUT /api/items/{id}/board-membership {onBoard:true}` → `boardMembership`。
- 期望:
  - 自动挂**最早 active sprint**(无 active → 最早 planned **并激活**,零 sprint 409);
  - 票无预估 → 守卫 409,**页首红字浮出原文**,票留 backlog 不上板(2026-11-23 前此路径静默吞错,已修 §六-3);
  - 成功 → 票出 backlog、进看板(active sprint 承载)、日期按守卫铺算。

### 途径 ③:看板页「拉入看板」(多选对话框)

- 入口:看板页「拉入看板」按钮 → PullDialog 多选勾选 backlog 票 → 「完成」。
- 端点:逐条 `PUT /api/items/{id}/board-membership {onBoard:true}`(同途径②后端语义)。
- 期望:
  - 逐条拉入;**任一条 409 → 中止,错误显示在对话框列表顶部**,已成功的票保留,对话框不关;
  - 用户可勾掉问题票重试「完成」,或取消;修预估后重开对话框再拉;
  - 自动挂 sprint / 日期铺算规则同途径②。

### 途径 ④:sprint 页「拉入 item」(多选对话框)

- 入口:sprint 页右上「拉入 item」→ AddMembersDialog(候选取自 Backlog + 本 sprint 成员)→ 勾选 → 「完成」。
- 端点:逐条 `PUT /api/items/{id}/group-membership`(assignSprint,同途径①端点)→ `replaceMembership`。
- 期望:
  - 逐条挂入目标 sprint;**无预估票 → 错误浮出对话框错误区**,已成功的保留;
  - 幂等:候选含已是成员的票,勾选不变的不发请求;勾选状态变化才发 PUT;
  - 守卫规则(含幂等重拉过守卫)同途径①。

### 四途径共同断言(验收用)

- [ ] 无预估:四途径均 409 且用户可见错误,票不入组不铺日期
- [ ] 有预估无日期:四途径均铺 start(钳工作时刻)与 due(预估铺算)
- [ ] 有显式日期:四途径均保留显式值(due 缺失才补铺算)
- [ ] 幂等重拉:已是成员再拉 → 过守卫(补铺/409),不产生重复成员行

## 六、增量决策(2026-11-23,手工验收引发;⚠ 实现已先行,本节补档待 review)

> 以下四项均超出票面真值表,验收期间实锤后先行落码(06b031b / 5edc733 / 9948839);本节为补档。**若 review 否决任一项,回滚对应实现**。

1. **「今天」语义修正(06b031b)**:start 决策序里的「今天」由「当日 00:00(全天窗)/窗起」改为「**钳到当前时刻的下一工作时刻**」(work:周末→周一窗起、窗后→次工作日窗起;wall=现在)。理由:日中拉入 due 落在过去立刻超期(overview e2e strict-mode 实锤)。**票面真值表③需同步改为「now(钳工作时刻)」**。
2. **幂等重拉也过守卫(5edc733,用户拍板 A)**:`replaceMembership` 早退分支(票已是目标组成员)同样执行守卫——存量成员无日期重拉补铺;有显式日期原样保留;无预估重拉 409。理由:封堵守卫绕行口(手工实锤 DEMO1107-7 拉入后仍悬浮)。
3. **拉入 409 必须浮出(9948839)**:看板拉入两处 UI(backlog「拉入看板」按钮 / 看板 PullDialog)不再静默吞 409,守卫消息红字展示。理由:无预估拉入「没反应」不可接受(手工实锤)。行为变化:看板拉入无预估票从「静默不上板」变「显式报错」。
4. **e2e fixture 原则(29338e5 起)**:只有真拉 sprint 的造票带预估;甘特 case 矩阵/日期语义用例保持三无(无预估票带预估会改 spec 0151 渲染分型)。

**占位语义不属本票**:无预估→中档输入框 placeholder、无开始→sprint 开始 placeholder,已立 YPJ-264(2026-11-23 拍板落点=placeholder,不写值);守卫 409 硬校验不变。

## 七、开放决策

无(票面 2026-11-22 用户过目;两处实现补充见 §二,PR 显要标注)。

## 八、As-built(完工后补)
