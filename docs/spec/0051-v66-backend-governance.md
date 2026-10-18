# spec 0051 — V66 后端治理:updatedAt 审计基线 + created_by/updated_by + 时钟纪律 + 错误兜底 + JWT 密钥防护

> 票:YPJ-66(2026-10-18 建,r2 决策项)。分支:`chore/YPJ-66-backend-governance`。

## 0. 范围修正(需人复核)

早期讨论按"~15 张表"估,精确核查后:**28 张表中 21 张有 `updated_at`**。经用户确认"全部都加",
本 spec 按实际 21 张执行;7 张**纯追加/日志表不加**(favorite、item_activity、item_dependency、
item_group_member、item_group_member_history、notification、time_entry)——它们无更新语义,
且各自已带行为者列(comment.author 等)或本就是事实流水。

## 1. 需求

### 1.1 updatedAt 治理 + 完整审计四元组

现状:无 `@EnableJdbcAuditing`;时间戳靠实体默认值 `= Instant.now()`(仅在构造时赋值一次,
save/copy 后不前进)+ DB `default now()` 兜底 → item 等实体更新后 `updated_at` 不动的 bug(r2 #1)。

目标:audit 四元组 **created_at / updated_at / created_by / updated_by** 完整:

- `@EnableJdbcAuditing` 接管四元组写入(含修 updatedAt bug)
- 新增 `created_by` / `updated_by` 列(nullable,uuid,存 member.objectId,无 FK——audit 列不参与引用完整性,避免删成员被卡)
- 系统写入(github 同步、无认证上下文的内部任务)→ auditor 返回 empty,列存 NULL,可接受

### 1.2 时钟纪律补完(spec 0049 收尾)

ItemService 3 处裸 `LocalDate.now()`(L182/189 overdue/dueSoon、L~371 附近 plusDays)改注入 Clock。

### 1.3 错误兜底

GlobalExceptionHandler 补 catch-all(`Exception` → 500 INTERNAL 通用体,不泄栈)+ 405 映射;
收敛 `server.error.include-message`。

### 1.4 JWT 默认密钥防护

`application.yml` 现有默认 secret(`yangzhou-dev-secret-...`)。非 test profile 检测到默认值 →
启动 fail-fast;test profile 豁免。

### 1.5 ADR

- **ADR-0005 授权模型**:单用户全局权限显式化(不做 per-project ACL)+ `memberService.current()` 埋点评估结论
- **ADR-0006 并发策略**:不上 @Version;行锁 + 终写胜出 + 并发集成测试兜底(两篇分立,职责单一)

### 非目标

- 不做 audit 字段的 API 暴露 / 前端展示(后续票)
- 不回填历史 NULL 值
- 不上 @Version / 乐观锁
- append-only 表不加 audit 列(见 §0)

## 2. 详细设计

### 2.1 迁移 `V34__audit_columns.sql`

21 张表逐表:

```sql
alter table item add column created_by uuid, add column updated_by uuid;
-- ×21(表清单见 §0)
```

无 FK、无 not null(存量行为 NULL)。

### 2.2 实体(`persistence/entities.kt`)

21 个对应实体加字段(默认 null,不写 `Instant.now()` 默认值——时间交给 auditing;同批删掉
这些实体上 `createdAt/updatedAt = Instant.now()` 默认,改 `@CreatedDate @LastModifiedDate
var/val createdAt: Instant? = null` 形态):

```kotlin
@CreatedBy  val createdBy: UUID? = null,
@LastModifiedBy val updatedBy: UUID? = null,
```

注意 Spring Data JDBC auditing 要求可变属性或支持 with-copy;data class + `val` + Spring 的
entity callback 走构造器属性注入(SDAT 支持 persistvia `AuditingEntityCallback` 对 Kotlin
data class 生效的前提是字段可写——若遇只读限制,则改 `var`;实现时以编译+集成测试为准,
两形态任选,不加投机抽象)。

### 2.3 审计装配(`api/config/AuditingConfig.kt`,归 config.*)

```kotlin
@Configuration
@EnableJdbcAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
class AuditingConfig {
    @Bean fun auditorProvider(memberService: MemberService): AuditorAware<UUID> = AuditorAware {
        runCatching { Optional.of(memberService.current().objectId) }
            .getOrElse { Optional.empty() }   // 无认证上下文(系统任务)→ NULL
    }
    @Bean fun auditingDateTimeProvider(clock: Clock) = DateTimeProvider {
        Optional.of(OffsetDateTime.now(clock))
    }
}
```

- AuditorAware 放 api 模块(依赖 MemberService/SecurityContext;persistence 不依赖 api)
- DateTimeProvider 复用已注入 Clock(spec 0049 引入),时钟纪律一致
- 全局 `Clock` bean 已存在;若 AuditorAware 内 memberService.current() 在过滤器链外抛异常,按 empty 处理

### 2.4 ItemService 时钟

3 处 `LocalDate.now()` / 衍生改 `clock`(`LocalDate.now(clock)` / `clock.instant()`);类已有
clock 注入(softDelete 已用 `clock.instant()`),无新构造面。测试:`Clock.fixed` 钉时刻,
单元测 overdue/dueSoon 边界(当天/超期/3 天内)。

### 2.5 GlobalExceptionHandler

- `@ExceptionHandler(Exception::class)` → 500 `{code:"INTERNAL", message:"内部错误", path}`,log.error 带栈
- 405:`HttpRequestMethodNotSupportedException` → 405 `{code:"METHOD_NOT_ALLOWED",...}`
- `application.yml`:`server.error.include-message: never`(统一走 GlobalExceptionHandler 的体)

### 2.6 JWT fail-fast(`api/config/JwtService.kt` 或其 `@ConfigurationProperties` 校验处)

init 块:secret == 已知默认值 且 active profile ≠ test → `throw IllegalStateException`(启动即拒)。
测试:单元测两个分支(test profile 放行/默认值拒绝),不起真上下文。

### 2.7 测试(遵循 backend-testing skill)

- 新迁移**不建新表**,cleanDatabase 清单不变
- 集成(AbstractApiTest,REST 黑盒):
  - item 更新后 `updated_at` 前进、`updated_by` = 当前 member objectId(经 `jdbc` 直查断言)
  - comment 创建后 `created_by` 正确
- 单元:Clock overdue/dueSoon、JWT 密钥两分支、(405/catch-all 若走 MockMvc 禁令则并入集成:构造不存在端点断 405/404 通用体)

### 2.8 文档

- ADR-0005、ADR-0006(格式对齐 0004:短文、决策+理由)
- `docs/architecture/entities.puml`:**加列不改实体关系**,补字段说明行即可(按 AGENTS 规则,Flyway schema 变更须同步)
- spec As-built 完工后补

### 否决的备选

- created_by 存 username 字符串:弃——公共 ID 约定(objectId),改名不破列
- audit 列加 FK:弃——删成员会被引用卡死,audit 值恰要留存
- @Version:用户已拍板不上(ADR-0006 记)
- 回填历史:弃——单用户系统历史均 me,无信息量

## 3. 手工测试用例(用户 build/debug 阶段执行)

1. `gradle :api:bootRun`(8081 PM 实例)→ 登录 → 改 item 标题 → 列表/详情确认无异常;`select updated_by from item where number=...` 非空
2. 用错误方法 curl 一个端点(如 `POST /api/items/{id}`)→ 405 通用体,非 Whitelist 栈页
3. 临时清掉 `YANGZHOU_JWT_SECRET` 以非 test profile 起服务 → 启动失败,日志见明确拒绝信息;`--spring.profiles.active=test` 起服务 → 正常
4. `gradle build --no-daemon` + `gradle integrationTest` 绿(agent 自跑,用户复核)

## 5. As-built(2026-10-18)

实现与 spec 的偏离(均已验证,显著标注):

1. **行为者存 member.id(bigint),非 objectId(UUID)**——V15 早已给 item 建了 `created_by bigint + FK member(id)`(通知收件人用),UUID 方案撞列;对齐既有列语义。因此 V34 只给其余 20 表 + item 补 `updated_by`,item 不重复加 created_by。
2. **新列无 FK**——中途按 V15 模式加过 FK,但 workspace↔member 互引成循环依赖,cleanDatabase(先 member 后 workspace)死结,128 个用例崩;回退为裸列(spec §1.1 原设计,标注:V15 的 item.created_by FK 保留,item 清库先于 member,无碍)。
3. **实体 audit 四字段用 `var`**——SDAT 对 Kotlin data class 多注解 `val` 的 copy 型 accessor 属性错位(ClassCastException: Long→Instant);改 var 得真 setter,auditing 正常。
4. **createdAt/updatedAt 保留 `= Instant.now()` 默认值**(spec §2.2 原计划删除以避 nullable 波及)——auditing 写入时覆盖,默认值仅 Kotlin 构造兜底,不影响正确性。
5. **Item.createdBy 名字不变**(中途试改 creatorMemberId 后回滚,V15 语义即创建者);Item 上原 misplaced 注解(误标 @CreatedBy 到 createdAt)已修正。
6. **AbstractApiTest 加 `@ActiveProfiles("test")`**——JWT fail-fast 的 test 豁免依赖 profile;DynamicPropertySource 的 datasource 优先级更高,Testcontainers 不受影响。
7. catch-all(500)无法经 REST 黑盒稳定触发,集成测试覆盖 405;catch-all 代码路径简单,靠 nightly 兜底。

测试:JwtServiceUnitTest(3)+ ItemDueSignalsUnitTest(2,新增纯函数 isOverdue/isDueSoon,ItemService 改用)+ ItemAuditApiTest(2:updated_at 前进+updated_by 落库、405 通用体)。`gradle test integrationTest` 全绿;`gradle build --no-daemon` 绿。

ADR:0005(单用户全局授权,current() 埋点评估)、0006(无 @Version,行锁+终写胜出)。entities.puml 已补 audit 四元组说明。api.http 无新端点,不改。
