# 0120 — overdue/dueSoon 徽标本地历:Clock 全局语义拍板(YPJ-175)

> 日期:2026-11-13。上游:台账 #9(MilestoneApiTest 深夜时段敏感)定位出 Clock bean = `Clock.systemUTC()`(ClockConfig);UTC+8 用户本地 0–8 点,徽标迟红 8h。

## ① 需求

**症状**:服务端按 UTC 日界计算 `overdue`/`dueSoon` 徽标;UTC+8 用户本地 00:00–08:00 之间,UTC 已翻日 → `dueDate=本地今天` 的 item 被误判 overdue 红、里程碑徽标同口径误判。

**Clock 消费点盘点(实锤)**:

| 消费点 | 用法 | 语义类别 |
|---|---|---|
| ItemService L668/674 | `LocalDate.now(clock)` → isOverdue/isDueSoon | **日界显示**(本票目标) |
| MilestoneService `today()`(L104→135/168/234) | `LocalDate.now(clock)` | **日界显示**(本票目标) |
| ItemGroupService/ReleaseService memberHistory | `clock.instant()` | 绝对时刻(无 zone 语义,不动) |
| MilestoneService L228 updatedAt | `Instant.now(clock)` | 绝对时刻(不动) |
| AuditingConfig DateTimeProvider | `clock.instant()` | 绝对时刻(不动) |
| MetricsService `utcDate()`(L38 显式 UTC) | 日期切桶 | 独立语义(非目标,见下) |

**关键事实**:三套 compose 均未注入 `TZ` → **容器内 `systemDefaultZone` = UTC**。即「把 Clock bean 改 systemDefaultZone」若不动容器,线上零变化——方案 A 必须配 TZ 注入。

**范围**:徽标日界语义本地化(两案任选其一);**非目标**:Metrics 桶 UTC 日界(是否本地历化开放,另议)、多用户多时区、前端显示格式(浏览器 `new Date()` 本就本地)、审计/历史时间戳。

**行为真值表**(UTC+8 本地;overdue = 非终态 ∧ dueDate < today):

| dueDate | 本地时刻 | 修复前 | 修复后 |
|---|---|---|---|
| 本地今天 | 00:00–08:00 | **误红**(UTC today=本地今天+1) | false |
| 本地今天 | 08:00 后 | false | false |
| 本地昨天 | 任意 | 红 | 红 |
| null | 任意 | false | false |

dueSoon 同理(黄标窗口随 today 平移)。

## ② 详细设计(两案,拍板后编码)

**方案 A(推荐):全局 Clock = 本地历 + 容器显式 TZ**

1. `ClockConfig.clock()` → `Clock.systemDefaultZone()`
2. compose 三 profile api 服务 environment 加 `TZ: ${TZ:-Asia/Shanghai}`(显式注入消除「容器/宿主」漂移;默认 Asia/Shanghai,单实例单时区)
3. 测试同步(台账 #9 规则升级「同钟」→「同 zone」):MilestoneApiTest 日期计算 `Clock.systemUTC()` → `ZoneId.of("Asia/Shanghai")`

- 优点:单一时间概念(「服务日历=本地历」一处定义),后续读码零心智负担
- 缺点:zone 由环境决定(靠 compose 注入锚定);A 改的是全局语义——**架构级变更,显要标注**(本 spec 即为拍板记录)

**方案 B:显示层显式 zone,Clock 保持 UTC**

1. 配置项 `yz.display-zone`(application.yaml 默认 `Asia/Shanghai`,可覆盖)
2. 仅 ItemService/MilestoneService 两处显示计算改 `LocalDate.now(clock.withZone(displayZone))`(注入 ZoneId)
3. 测试:同 zone 计算

- 优点:显式、容器无关、单点可配;Clock 契约不变
- 缺点:双时间概念并存(clock=UTC / displayZone=本地),每个新读码者都要记住「徽标走 displayZone」;灵活性(多 zone)当前无需求

**否决**:只改 ItemService 不改 Milestone(两处徽标同口径,半改更乱);Metrics 顺手改(独立语义,不搭车)。

**测试设计**:
- 单元:`isOverdue`/`isDueSoon` 已是 file-level `internal` 纯函数(收 `today` 参数)——现有单测锁定谓词,zone 语义在调用点,不新增投机抽象
- 集成:同 zone 计算期望日期(台账 #9 规则);诚实声明局限——UTC/本地分歧行为只在本地 0–8 点现形,白天跑的集成测不出 A/B 差异,0–8 点由 nightly/手工兜底
- MilestoneApiTest 随方案调整(两案都要动)

## ③ 手工测试用例(用户 build/debug)

1. 容器 rebuild(prod)后:建 `dueDate=本地今天` 的 item → 不红;`dueDate=昨天` → 红;终态 item → 不红
2. Milestone 徽标同口径:targetDate=今天 → 不红;昨天 → 红
3. (可选,深夜窗口)本地 0–8 点:dueDate=今天的 item 不红(修复前此场景必红,是本票的验收级证据)
4. 回归:dueSoon 黄标、item 表格/详情徽标、milestone 时间线落位

## ④ 拍板记录

- 2026-11-13 用户立项「全做」。
- 2026-11-13 用户拍板:**方案 A**(全局 Clock = 本地历 + 容器显式 TZ)。理由:单一时间概念,TZ 显式注入消除环境漂移;B 的多 zone 灵活性无需求。

## ⑤ As-built(2026-11-13)

- 落地与 ② 方案 A 一致,1:1:ClockConfig `systemDefaultZone` + 三 compose 注 `TZ: ${TZ:-Asia/Shanghai}` + MilestoneApiTest 回 `LocalDate.now()`(同 JVM 同 zone,台账 #9 结构性解法)+ e2e `shanghaiYesterday`(+8h 口径,红断言恒红保持)
- 测试:gradle test / integrationTest / e2e 全量绿(用户跑);dev 实例 API 真值表 6 行全对(TZV 项目,数据留档)+ 容器 `date`=CST 结构实锤
- 深夜 0–8 点窗口验收级证据:可选,数据留在 dev 供随时复验(TZV-1 今天到期应不红)
- 偏差:无。api.http 无涉(无端点变更)。插曲:spec 0121(属 YPJ-176)误提交本分支后搬家(台账 #12),与本票内容无关
- prod 容器重建(使 TZ 生效于 PM 日常)由用户择机执行,未纳入本票验证
