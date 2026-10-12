# 0022 — V18:并发 PUT 500 归因(YPJ-26)

> 状态:实现中 · 2026-10-12 · 分支 `cwjiang/YPJ-26-concurrent-500`

## ① 需求

**症状**:`PUT /api/items/{id}/requirements` 与 `PUT /api/members/{id}/capabilities` 负载下偶发 500,**单跑绿**;堆栈在用户 8080 终端(待贴,不阻塞)。

**目标**:并发复现 → 定位根因 → 修复 → 护栏长驻。

**非目标**:重算架构改造(异步化/事件化)不在本票,除非归因指向必须。

## ② 详细设计

### 已排除

- YPJ-10 修的 project 行锁序(`sortedBy { it.id }`)仍在,未回退。
- 实体无 `@Version` → 排除乐观锁异常。

### 复现:`ConcurrentPutHammerApiTest`(feasibility 包,集成测试)

- 布局:5 项目 × 2 item(带 requirements),6 属性;`GET /api/members` 取 memberId。
- 8 线程混打 12 轮:
  - 4 线程 `PUT /api/members/{id}/capabilities`(触发 `recomputeWorkspace` 全量重算,跨全部 project 行)
  - 4 线程 `PUT /api/items/{id}/requirements`(触发 `recomputeProjectSignal`,写该项目全部 item 行 + project 行)
- 断言全 2xx;失败消息带 status + body(含异常类名,区分 `40P01` 死锁 / 连接池超时 / 其他)。

### 修复方向(按归因结果二选一,堆栈定)

- **死锁复发**:找第二处无序行更新(候选:item 信号行按 `OrderByNumber` 之外的无序写、`save` 前的重算读集),同 YPJ-10 方案——全序化。
- **非死锁**(池耗时/约束冲突):按堆栈对点修;若是重算事务过长,最小修 = 缩小事务或重试语义,不开异步架构。

### 代码触点

- 新增 `backend/api/src/test/kotlin/yangzhou/api/feasibility/ConcurrentPutHammerApiTest.kt`
- 修复:`FeasibilityService.kt`(或堆栈指向处)
- `backend/script/api.http` 按需补负例(如契约变化)

## ③ 手工测试用例(用户 build/debug 用)

1. `cd backend && gradle :api:integrationTest --tests "*ConcurrentPutHammer*"` —— 复现红(预期;若绿说明并发度不够,加大轮数/线程)。
2. 修复后同一命令 —— 绿。
3. `gradle :api:integrationTest` 全量绿后 PR。

---

## As-built(2026-10-12)

### 根因

**40P01 死锁复发,现场在 `item` 表**(复现第一把即红,堆栈实锤:ExclusiveLock on item tuple × ShareLock on transaction 成环)。

YPJ-10 只全序化了 project 行;item 行有第二处越序写:`replaceRequirements` 先 `recomputeItemSignal(item)` **单独锁住编号序中任意位的本 item 行**,随后 `recomputeProjectSignal` 再按编号序扫全部 item——两个事务各自「持 #3 等 #1」即成环。capabilities PUT(workspace 重算)与 requirements PUT 混合并发时命中率最高。

### 修复(2 文件)

- `ItemService.replaceRequirements`:删除独立的 `recomputeItemSignal(item)` 调用——信号由紧随的按编号序 sweep 覆盖,响应无差(`get()` 在 sweep 后读)。
- `FeasibilityService.recomputeItemSignal`:收为 `private` + 注释说明锁序原因,防再引。

### 验证

- 锤:修复前 1 红(500,`/api/members/{id}/capabilities`,与线上症状同端点);修复后 **3 连绿**。
- `gradle build`(unit 全模块)绿;`integrationTest` 全量 **39 类 122 测试 0 败 0 跳**。
- 无契约变化(api.http 不动);无 domain/schema 变化(两图不涉)。
