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

## 五、开放决策

无(票面 2026-11-22 用户过目;两处实现补充见 §二,PR 显要标注)。

## 六、As-built(完工后补)
