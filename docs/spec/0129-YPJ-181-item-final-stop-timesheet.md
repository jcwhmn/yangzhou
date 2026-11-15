# spec 0129 — YPJ-181 item 进 FINAL 自动停进行中工时

## ① 需求

- **背景**(票内实锤):`ItemService` 状态迁移对 TimeEntry 零引用——item 进 FINAL(如 Done)后,该 item 上的进行中计时仍在涨,工时报表折算到 now 持续计入,数据失真。
- **用户拍板(2026-11-14,方案 A)**:item 状态进入 FINAL(按 `StatusType.FINAL` 判定,非硬编码 Done)时,自动停掉**该 item** 上所有进行中的 TimeEntry(`endedAt=now`);其他 item 的进行中计时不受影响(全局计时器语义不变)。
- **范围**:
  - 触发点:状态**首次**进 FINAL(`to FINAL && from 非 FINAL`,与 YPJ-184 `itemFinalize.onFinalized` 同一判定/同一挂点);重开再完成会再次触发,但幂等(运行中的才停,已停的重复停 = 无行可停,天然幂等)。
  - 停表语义:与手工 stop 一致——`endedAt = now`,`endedAt` 为 null 的行才动;**不写** minutes(报表由起止折算);不产生 activity 留痕(状态变更本身已有 status_changed,停表是其机械后果,避免日志噪音)。
  - 通知:不发(同上,机械后果)。
- **非目标**:不接管「开新计时自动停旧」逻辑(TimeEntryService.create 既有行为不变);不做 startedAt/completedAt 生命周期时刻(YPJ-132 另票);不改前端。

## ② 详细设计

**落点:`ItemFinalizeService.onFinalized`**(而非 ItemService 内联)——YPJ-184 已在此集中「首次进 FINAL 的后置动作」,挂同处语义最贴;且 onFinalized 已是 best-effort 不阻塞迁移的约定位置。

改 3 个文件:

1. `backend/persistence/src/main/kotlin/yangzhou/persistence/repository/Repositories.kt`
   - `TimeEntryRepository` 加 `fun findByItemObjectIdAndEndedAtIsNull(itemObjectId: UUID): List<TimeEntry>`(派生查询,与 `findByMemberObjectIdAndEndedAtIsNull` 同式)。
2. `backend/api/src/main/kotlin/yangzhou/api/business/item/ItemFinalizeService.kt`
   - 构造注入 `TimeEntryRepository` + `java.time.Clock`(Clock 注入纪律:endedAt 是审计时刻;测试可 Fixed Clock。项目已有 Clock bean)。
   - `onFinalized` 里、GitHub 快照之后追加:
     ```kotlin
     val running = timeEntries.findByItemObjectIdAndEndedAtIsNull(itemObjectId)
     if (running.isNotEmpty()) {
         timeEntries.saveAll(running.map { it.copy(endedAt = clock.instant()) })
         log.info("FINAL 停表:item={}, 停 {} 条进行中工时", itemObjectId, running.size)
     }
     ```
   - 同在 runCatching 内还是外?**放快照之后、独立 try 不包**——停表是本票核心行为,失败应随迁移事务回滚暴露,不该静默吞(与快照的 best-effort 性质不同,故不并入其 runCatching)。
3. `backend/script/api.http`
   - 无新端点,不改契约;负例区不动(无新校验)。仅验证脚本沿用既有 create/stop 节奏,不新增节。

**否决的备选**:
- ❌ 挂 `ItemService.update` 内联:FINAL 判定与 184 重复,且 ItemFinalizeService 就是「进 FINAL 后置动作」的家。
- ❌ Spring 事件(ApplicationEventPublisher):单订阅者同步调用,事件是投机抽象。
- ❌ 停表也 best-effort runCatching:核心行为失败被吞 = 数据继续失真,违背 fail-fast。

**测试**(单元 `src/test` / 集成 `src/integrationTest` 物理二分;无新表,cleanDatabase 清单不动):

- 集成 `ItemFinalizeStopTimesheetApiTest`(extends AbstractApiTest,REST 黑盒):
  1. item A 开计时(成员 me)→ PATCH A 状态到 FINAL → 查 A 工时列表:该 entry `endedAt` 非空、totalMinutes 定格;
  2. 非 FINAL 迁移(如 To Do → In Progress)→ 计时仍进行中(`endedAt=null`);
  3. 成员在 A、B 两个 item 各开计时(A 全局计时器语义:后开的 B 已停 A 的?——注意:现有 create 计时模式会先停我**所有**进行中再开新,单成员同时只有一条进行中。故用例 3 改为:成员 me 在 A 计时,成员 other 在 B 计时,PATCH A 到 FINAL → only A 停,B 仍进行中);
  4. FINAL→非 FINAL 重开→再 FINAL:幂等,无异常,不重复停已停行。
- 单元:无纯函数新增(逻辑为 repo 调用编排),不设。

## ③ 手工测试用例(用户 build/debug 阶段执行)

1. 看 item 详情 → 工时 tab → 开计时(分钟数留空)→ 卡片显示计时中;
2. 把该 item 状态拖/改到 Done(FINAL 列)→ 刷新工时 tab:计时已停,时长定格;卡片角标计时消失;
3. 另一 item 开计时,再把第一个 item 重开再 Done → 第二个 item 计时不受影响,仍在涨;
4. 工时报表/表格工时列:该 item 总时长不再随时间增长。
