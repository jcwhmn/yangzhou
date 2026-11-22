# spec 0162 — YPJ-242 recomputeWorkspace 批量化(乙案,语义按拍板收敛)

> 3h 性能票。症状/方案来源票面(台账 A21;2026-11-19 拍板案 B 同步批量化 + 乙案)。

## 一、需求

- capability PUT/DELETE → `recomputeWorkspace()` 当前对每 item 走 `item()` 读路径:每条 item 单查 item/项目全表/definitions/requirements/capabilities——N+1 ×全库,测试库残渣下单发 7.9s+。
- 改批量读 + 引擎纯内存算 + 只 save 信号变化行;验收:PUT capability 回亚秒级、既有可行性测试全绿。

## 二、语义(拍板口径,显要标注:含一处行为收敛)

- **FINAL item 不重算、不计入 project rollup;全 FINAL → project signal = null**;冻结值 = 转入 FINAL 前最后一次重算(状态迁移链路上本就触发重算,skip 后自然冻结)。
- ⚠ **行为收敛**:现实现 FINAL item 也参与重算与 rollup;本票按票面拍板口径改为排除。读路径(`GET /feasibility`)不动,仍实时算。
- project signal 聚合规则不变(RED>YELLOW>GREEN);锁序不变(项目 id 升序,入口行锁;save 按 number 序)。

## 三、设计

`FeasibilityService`:

- 注入 `StatusRepository`;新增私有 `loadCtx()`:一次性读 definitions(workspace)+ capabilities(当前成员)+ 组装 member。
- `recomputeProjectSignal(projectObjectId)` 重写:
  1. `projects.lockByObjectId`(锁序不变)
  2. 项目 items 一次取;`statuses` 建 FINAL id 集
  3. `requirementRepo.findByItemObjectIdIn`(已有 @Query)一次取全项目需求,内存分组
  4. 非 FINAL 逐条 `MatchingEngine.matchItem`(纯函数);signal 与 `item.feasSignal` 相同则跳过 save
  5.变化的 item 按 number 序 save;project signal = 非 FINAL 最差(无 → null),变化才 save
- `recomputeWorkspace()`:ctx 装配一次,跨项目复用,逐项目调重写版(锁序不变)。
- `recomputeByKey`/外部调用方(ItemService 四处)签名不变。
- `item()`/`project()` 读路径不动。

否决:异步化(MQ/@Async)——拍板案 B 同步;信号查询接口形状不变。

## 四、测试

- 单测:FeasibilityServiceUnitTest/CapabilityServiceUnitTest 构造补 StatusRepository mock。
- 集成:FeasibilitySignalApiTest 全绿(信号语义回归);**新增**:item 转 FINAL → 其 feasSignal 冻结在转前值、project rollup 不计它;全 FINAL → project signal null。
- 性能探针:dev/test 库 PUT capability 前后计时(台账 A21 场景),目标亚秒级。
- 手测:api.http capability PUT/DELETE 三连发不再超时。

## 五、开放决策

无(方案 2026-11-19 已拍板;FINAL 排除系票面口径)。

## 六、As-built(完工后补)
