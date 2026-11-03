# 0096 — v128 并发写死锁(40P01→500)修复:统一锁序 project→item(YPJ-128)

> 2026-11-02 立项。2026-10-31 e2e 实测并发建 item 触发 PG 40P01 deadlock → 500 冒泡(票描述:两事务 item 表 ShareLock 交叉互等);e2e 侧加瞬态重试兜底(shortcut 注释挂账)。本票治本:统一写事务锁序。

## 1. 需求

### 范围

- 同项目任意两个写事务并发(create / replaceRequirements / delete / purge / 能力变更触发重算)不再产生 40P01→500
- 行为与契约零变化(响应形状、状态码语义不变;唯一变化 = 不再偶发 500)

### 非目标

- 吞吐优化:锁 project 行 = 单项目写事务串行化。单人 V1 吞吐不成立(先例:取号计数器行同判,`ItemNumberRepository` ponytail 注释)
- 死锁重试(spring-retry):治标且放大 recompute 重复计算;锁序治本后不需要
- item 行 feas_signal 冗余的去冗余重构(涉及查询面大改,另票)

## 2. 归因(实锤链,非推理)

1. **代码路径举证**:
   - `create`:`nextNumber` = `UPDATE project SET last_item_number...RETURNING`(`ItemNumberRepository`,**X 锁 project 行,第一把锁**)→ 写 item 行 → `recomputeProjectSignal`:按 number 序逐个 `itemRepo.save(item.copy(feasSignal=signal))`(**X 锁 item 行**,已实证 `recomputeItemSignal` 落库)→ `projects.save(feasSignal)`。锁序 = **project → items**
   - `replaceRequirements` / `delete`(软删)/ `purge`(物理删):**先写 item 行**再调 `recomputeProjectSignal`(末尾才 UPDATE project)——锁序 = **items → project**,与 create 反序
   - `CapabilityService.set/rm` → `recomputeWorkspace`:逐项目 items → project(项目间按 id 序;**YPJ-10 只修了 workspace 内部项目间排序,未修 workspace×create 交叉**)
2. **环形成立**:T1(create,持 project 锁,遍历到 item i_k)⋮ T2(反序族,持 i_k,末尾等 project 锁)→ 互等 → 40P01
3. **探针实证**(§4 测试1):双 connection 交叉复刻两锁序,修复前必现 40P01
4. **反证条款**:若探针无法成环(如 recompute 实际不写 item 行)则本归因作废——现已实证 save copy 落库,e2e "并发建 item" 中另一事务实为 fixture 走的反序写路径(纯 create×2 同项目会被 project 行锁串行化,理论无环)

## 3. 详细设计(代码级)

统一锁序 = **每写事务的第一把锁必须是 project 行,其后才允许 item 行**:

| 文件 | 改动 |
|---|---|
| `persistence/repository/ProjectRepository.kt` | 加 `@Query("select object_id from project where object_id = :id for update") fun lockByObjectId(id: UUID): UUID?`(ADR-0004 授权扩展点 `@Query`;单列投影) |
| `api/.../item/ItemService.kt` | `replaceRequirements` / `delete` / `purge`:校验通过后、**首个 item 写之前** `projects.lockByObjectId(projectObjectId)`;`create` 不显式加(nextNumber 的 UPDATE 已是第一把锁,补注释说明等效锁序) |
| `api/.../feasibility/FeasibilityService.kt` | `recomputeProjectSignal` 入口 `projects.lockByObjectId(projectObjectId)`(对已持锁事务 no-op;覆盖 workspace 路径 = 逐项目先锁 project 再写 items,id 序保留) |
| `web/e2e/table.spec.ts` | 瞬态重试 shortcut 保留为保险(治本后不再触发,不删) |

**否决备选**:spring-retry 40P01 重试 ✗(治标,retry 放大 recompute 重复计算,环不消除);feasSignal 去冗余(读时算)✗(查询面大改,另票);pg_advisory_xact_lock ✗(应用层锁语义分散,不如行锁直观)。

## 4. 测试

1. **实锤探针**(`LockOrderProbeTest`,集成):基类注入 jdbc 双 connection:A `BEGIN; UPDATE project; UPDATE item(i_k)`(不提交)→ B `BEGIN; UPDATE item(i_k); UPDATE project` → 断言 B 端 40P01(`DeadlockLoserDataAccessException`),探针内 rollback 清理。**这是归因实锤,非回归**
2. **并发回归**(`ConcurrentWriteApiTest`,集成):同项目 10 轮,线程 A POST create 与线程 B PATCH(replaceRequirements/status)交错,断言全 2xx。修复前概率挂;修复后理论必过——**若挂即归因有误,回探针重查**
3. 全量 `gradle test` + `integrationTest` 绿(存量集成测试大量写路径,天然回归网)
4. 手测:e2e table.spec 全量本跑,不再触发 shortcut 重试

## 5. 拍板点

1. 锁序方案(治本)vs spring-retry(治标)——**默认锁序**,与 YPJ-10 先例同族同向
2. 单项目写串行化的吞吐代价——V1 接受(先例同判)
