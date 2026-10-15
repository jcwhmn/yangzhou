# 0038 — YPJ-45 Flow Metrics 只读 API

> 伞设计:spec 0037(§二口径 / §四实现)。票:YPJ-45。分支 `cwjiang/YPJ-45-metrics-api`。

## ① 需求

**背景**:V19 基座 `item_status_history`(spec 0032/V31)已就绪——任意路径迁移留痕、id 级引用;PRD §1 F1 要求项目级纯读聚合指标 API,对 group type 中性。伞设计已定 12 指标口径(M1–M12),本票交付 M1–M10 的 8 个端点;M11/M12 留 YPJ-54。

**范围**:`/api/projects/{key}/metrics/*` 8 个只读 GET——workload / throughput / flow-times / cfd / aging / burndown / burnup / velocity。响应自带中文口径(`definitions`)。纯 SQL 取数 + 内存计算,不物化。

**非目标**:前端图表(YPJ-46)、成员变更留痕(YPJ-54)、缓存/物化、跨项目对比、导出、权限模型变化(沿现状:登录即可读项目域)。

**验收**:
1. 8 端点上线,OpenAPI(Scalar)可见;api.http 补区块(含负例)。
2. 集成测试构造迁移史断言数值:**含状态改名后数值不变(id 级)**、重开去重、未完成排除、从未离开 start 无 cycle。
3. 时间敏感路径 Clock 注入;纯计算函数单元测试;`gradle build` 绿。

## ② 详细设计

**新文件 2 + 仓储扩充 + 测试 2,无迁移**(全部读既有表:item/status/item_assignee/item_group/item_group_member/item_status_history;**cleanDatabase 清单不动**)。

### 后端

1. **`persistence/repository/repositories.kt`**(扩充,house style 单文件):新增 `MetricsRepository` 接口(Spring Data JDBC,聚合 SQL 用 **`@Query` 自定义查询**——ADR-0004 只授权 Spring Data JDBC,不引入 JdbcClient/JdbcTemplate 等第二套 API),配行投影 DTO/接口投影(同文件):
   - `statuses(key)`:status(id, objectId, name, isFinal, position)按 position 排
   - `items(key)`:item 现态(itemId, itemObjectId, number, title, statusId, type, createdAt, assigneeMemberId?)
   - `history(key, from)`:item_status_history 行(itemId, fromStatusId, toStatusId, createdAt),item+createdAt 排序
   - `members(key)`:member(id, objectId, displayName)——assignee 拆分显示名
   - `groups(key, type?)` / `groupMembers(groupId)`:item_group(id, objectId, name, status, startDate, endDate)与 item_group_member(itemId, createdAt)
   - 可选参数(groupId)用 `(:groupId IS NULL OR …)` 或双方法;投影映射细节(DTO 构造/接口投影)票内核实,Spring Data JDBC 官方支持两种

2. **`api/metrics/MetricsController.kt`**(新):类级 `@RequestMapping("/api")` 只扛前缀;8 个方法级 `@GetMapping("/projects/{key}/metrics/...")`;参数校验 Bean Validation(`from < to`、`bucket ∈ {day, week}`),非法走 GlobalExceptionHandler 统一错误形状。

3. **`api/metrics/MetricsService.kt`**(新)+ **同文件 DTO/口径**:
   - 构造注入 `MetricsRepository` + **`Clock`**(aging 的 now、窗口默认 `to = now`、默认 `from = now − 90d`)。
   - **纯计算函数独立顶层**(不起 Spring 可直测):`completionTs`(每 item 首次进 final,重开不重复计)、`cycleTs`(首次离开 start 状态)、`bucketize`(week 默认/day,UTC 周界)、`replayCfd`(history 逐行推进 + 日界快照)、`percentiles`(P50/85/95,线性插值)。
   - start 状态 = `status.is_start` 显式列(V3 起,默认 workflow 的 To Do);口径文案集中为 `DEFINITIONS: Map<String, String>` 常量(中文),各响应 DTO 携带 `definitions` 子集。
   - burnup scope 线 = `groupMembers.createdAt` 累计,**离组不可回填**写进口径(YPJ-54 落地后转精确)。
   - velocity 只取有 startDate+endDate 的 group(type=sprint,不 hardcode——`type` 作为查询参数缺省全类型);完成归属 = item 首次完成时刻落在 [startDate, endDate]。

### 测试(规矩按 `.agents/skills/backend-testing/SKILL.md`)

4. **`api/metrics/MetricsMathTest.kt`**(单元,无 Spring):百分位排序/插值、跨年周桶、空窗口、全未完成、多次重开、CFD 重放边界(首次迁移前/后)。
5. **`api/metrics/MetricsApiTest.kt`**(集成 extends AbstractApiTest,黑盒):
   - 数据准备:**现态走 API**(createProject/createItem/move/assign/sprint 建组拉入),**历史时刻用 `jdbc` 直插 item_status_history 固定 createdAt**(基类已注入;服务端 now 不可控,断言需确定时刻)。
   - 用例:①workload 三桶计数 × assignee 拆分;②throughput 周桶 + groupId 过滤;③flow-times 数值 + P50/85/95 序 + 未完成排除 + 无 cycle 计数;④cfd 某日快照;⑤aging enteredAt/age;⑥burndown ideal/actual(含中途完成);⑦burnup done+scope 累计;⑧velocity 双 sprint;⑨**rename 不破数**(完成历史后经 workflow API 改状态名,重断言);⑩负例:未知 key 404、from>to 400、坏 bucket 400。
6. **`backend/script/api.http`**:新增「## metrics(YPJ-45)」区块(8 端点正例,按序号续排);末尾负例区补 from>to / 非法 bucket。

### 否决的备选

- **JdbcClient/JdbcTemplate 直写 SQL**——ADR-0004 持久层只授权 Spring Data JDBC,聚合投影用它自己的 `@Query` 扩展点即可,不引入第二套数据访问 API(2026-10-15 用户质疑后修正);
- SQL 窗口函数逐日算 CFD——数据量小,内存重放可单测,SQL 难断言;
- 单一 mega 端点返回全部指标——各图独立取数,粒度对齐 YPJ-46 消费;
- 计算逻辑下沉 domain——指标非引擎域,纯函数留 api 切片(ADR-0002 域边界不扩);
- 引 story points——产品无 points 域,velocity 固定 count 口径(spec 0037 §一.4)。

## ③ 手工测试用例(供 build/debug)

1. 8081 PM 实例:`GET /api/projects/YPJ/metrics/workload` 的 byStatus 计数与项目表格页各状态条数一致。
2. throughput:取最近一周,与「本周完成」人工对账(周桶边界 UTC)。
3. flow-times:抽查 2 个已完成 item,cycle/lead 与其状态历史时间线一致;P85 ≥ P50,均值不出现。
4. cfd:last bucket 各状态存量 = workload.byStatus(自洽性)。
5. aging:仅含非 final item,age ≥ 0;某刚迁移 item 的 enteredAt ≈ 迁移时刻。
6. burndown/burnup:对 YPJ 当前 active sprint,total = 该 sprint 成员数;actual 尾点 = total − 已完成数。
7. velocity:YPJ 历史有日期 sprint 各计数;无日期组不出现。
8. 全部响应可见 definitions 中文口径;burnup scope 口径含「离组不可回填」字样。

## As-built(2026-10-15,PR #108 合入)

- **8 端点如期交付**,形态与 spec 一致:`MetricsController`(方法级 `@GetMapping`,类级只扛 `/api`)+ `MetricsService`(纯函数 + `Clock` 注入,`config/ClockConfig.kt` 提供 bean)+ `MetricsRepository`(`@Query` 聚合 SQL + 行投影 DTO,同 `repositories.kt`);测试 `MetricsMathTest` 8 例(单元)+ `MetricsApiTest` 10 例(集成黑盒)。
- **实现期修正(写进本节作范本)**:
  1. **SD JDBC `@Query` DTO 投影**:构造参数按命名策略 **snake_case** 匹配列,SQL 别名写成引号驼峰(`AS "itemId"`)会**静默脱靶得 null**——别名一律 snake_case;
  2. **纯查询 Repository 必须继承 `Repository<T, ID>` 标记接口**才被扫描注册,裸接口不生成 bean;
  3. **start 锚点 = `status.is_start` 显式列**(V3 起,默认 workflow 的 To Do),不做 position 推导(0037 表述已同步修正)。
- **api.http 主链三修**(多轮实证,横幅坑由用户定位):
  1. 单 `#` 行横幅在请求体后会被 IDEA http client 解析为上一请求的**延续**,吞掉紧随的请求——16 条横幅统一 `###`,`#` 仅限文件头注释区;
  2. meId 不写 JS 捕获——`TokenResponse` 自带 `memberId`,登录 handler 直取(旧引擎下箭头函数会静默语法死且无报错);
  3. 调试期删除的取消指派用例已恢复;8081 全链复现通过(开计时 201+回填、停止计时 200)。
- 偏差:无。可选参数走 `(:groupId IS NULL OR …)` 单方法;velocity `type` 缺省全类型,与 spec 一致。
- 验证:单测 ✅、全量 integrationTest ✅、`build --no-daemon` ✅、bootRun 冒烟 ✅、8081 手测第 ③ 节用例通过(SMK* 冒烟项目残留为已知取舍)。
