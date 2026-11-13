# 0121 — milestone 接棒级联:complete 当前时同事务自动开始下一个(YPJ-176)

> 日期:2026-11-13。用户期望:milestone 是序列——前一个 complete 时,自动同时开始下一个(不必手动再推 in_progress)。

## ① 需求

**现状实锤**:milestone 无显式序列字段,显示排序 = `in_progress 优先 → targetDate asc nulls last → 创建序`(MilestoneService L132-134);complete 走 `update()`(PATCH status),single-current 与 gate 硬门禁均在其同事务内;无任何级联——complete 后 current 空缺,下一个 planned 停在 planned,须手动推。

**范围**:`in_progress → completed` 成功(通过 gate)时,同事务把「下一个 planned」自动推成 `in_progress`。**非目标**:过期 planned 自动迁移(独立语义,开放待拍板)、in_progress → cancelled 的级联(拍板点,默认不做)、显式序列字段、前端改动。

**真值表**(状态 × 动作 → 行为):

| 动作 | 结果 |
|---|---|
| in_progress → completed,gate 就绪,**有 planned** | completed ✓ + 排序第一的 planned → in_progress(**同事务,原子**) |
| in_progress → completed,gate 就绪,**无 planned** | completed ✓,无级联,静默成功 |
| in_progress → completed,**gate 未就绪** | **409,无级联**(事务回滚,下一个仍 planned) |
| in_progress → cancelled | 仅 cancelled,不级联(拍板点) |
| planned → completed | 不可达(transitions 白名单禁止:planned 只能去 in_progress/cancelled) |
| 侧栏/列表 | current 自动切换到接棒者(纯后端状态变化,前端零改动) |

**「下一个」定义**:复用现有派生排序——`targetDate` 最早的 planned(nullsLast),并列取 `createdAt` 早者;不新增字段。

## ② 详细设计

`MilestoneService.kt` 单文件,零前端/零 API 形状变更:

1. 提纯选择逻辑为 file-level `internal` 纯函数(可单测):
   ```kotlin
   internal fun nextPlannedOf(planned: List<Milestone>): Milestone? =
       planned.minWithOrNull(compareBy({ it.targetDate ?: LocalDate.MAX }, { it.createdAt }))
   ```
   (null targetDate 排最后 = LocalDate.MAX 哨兵;createdAt 非空)
2. `update()` 在 `milestones.save(...)` 之后、返回之前:
   ```kotlin
   if (newStatus == "completed" && m.status == "in_progress") {
       val next = nextPlannedOf(milestones.findByProjectObjectIdAndStatus(m.projectObjectId, "planned"))
       if (next != null) milestones.save(next.copy(status = "in_progress", updatedAt = Instant.now(clock)))
   }
   ```
   原子性由 `@Transactional` 保证:gate 409 抛在 save 前,回滚即无级联;single-current 检查对 next 不触发(完成者已离开 in_progress,save 顺序保证)。
3. 否决备选:动作端点专用 complete(无此端点,PATCH 既有);级联做成事件/异步(破坏原子性,宁可同事务)。

**测试设计**:
- 单元:`nextPlannedOf` 纯函数直测——排序(null date 垫底/并列取早建)/空列表/单元素
- 集成(MilestoneApiTest 风格):
  1. M1(明天)+ M2(后天)→ 开始 M1 → 完成 M1 → 回读:恰一个 in_progress=M2
  2. 多 planned → 只有 targetDate 最早者被启动,其余仍 planned
  3. null targetDate 的 planned 垫底
  4. 无 planned 时完成 → 静默成功,无 current
  5. **gate 未就绪 → 409 且下一个仍 planned**(原子性关键证)
  6. in_progress → cancelled → 不级联(锁默认拍板)

## ③ 手工测试用例(用户 build/debug)

1. 项目建 3 个 planned(targetDate 递增)→ 推第一个 in_progress → **完成它**:列表第二个自动「进行中」,侧栏 current 行切换到它;第三个仍「规划中」
2. 依次完成到最后一个:完成后无 current,静默成功(无报错)
3. 给第二个挂一个未终态 gate item → 完成第二个 → 409「关口未就绪」且**第三个未被启动**(刷新确认仍 planned)
4. 回归:双徽标/时间线/就绪度不受影响;TZV 项目(0/1 gate)完成动作正常
