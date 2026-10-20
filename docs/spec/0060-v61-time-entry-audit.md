# 0060 — V61 / YPJ-61:审计字段基线收尾(time_entry 四元组 + 缺口裁决)

> 状态:**草案(等用户过目后编码)**
> 票:YPJ-61(V22);上游:spec 0051(YPJ-66,V34 已铺 20 张表)、spec 0047(YPJ-62,规则 2 明确「审计字段归 YPJ-61」)

## 需求

### 背景

V34(spec 0051)已给 20 张业务表铺 `created_by/updated_by`(bigint nullable 无 FK)+ auditing 统一接管(`AuditingConfig`:auditor = 当前登录 member.id,无认证上下文存 NULL;时间走全局 Clock)。本票收尾:全库还有 7 张表缺审计列,逐一裁决(票原文:「业务表必带,纯交叉表按需」)。

### 缺口裁决(7 张)

| 表 | 性质 | 裁决 | 理由 |
|---|---|---|---|
| **time_entry** | 业务(工时账) | **补全四元组** | 工时是谁记的必须可追溯;`member_id` 是「为谁记」不是「谁记的」;且可变(minutes/note 可改),需 updated_at |
| item_activity | append-only 事件流 | 跳过 | 已有 `actor_member_id` 即行为者审计;append-only 无 update 语义 |
| notification | 系统投递 | 跳过 | 已有 `actor_member_id`(触发者)+ `recipient_member_id`(收件人),再加即三重冗余 |
| item_group_member_history | append-only 历史 | 跳过 | `added_at/removed_at` 即审计;历史表本身就是审计产物 |
| favorite | 交叉(user↔item) | 跳过 | `member_id` 即创建者,冗余 |
| item_dependency | 交叉(item↔item) | 跳过 | 纯交叉无消费方,需要时再加(按需) |
| item_group_member | 交叉(组×成员) | 跳过 | 纯交叉;变更时间已在 history 表(此裁决覆盖 spec 0047 的钩子,见下) |

**spec 0047 钩子闭环说明**:0047 规则 2 曾注明「project_member 不加 created_by,归 YPJ-61」——该列已由 V34 铺掉,且「创建者自动入池」由 `ProjectService.create` 显式写池行(memberId = 当前登录人)实现,语义已满足,本票无动作。

### 范围

1. Flyway 迁移 `V36__time_entry_audit.sql`:`alter table time_entry add column updated_at timestamptz, add column created_by bigint, add column updated_by bigint;`(完全沿用 V34 形态:nullable、无 FK、**不回填存量**——历史行为者无从考证,存 NULL)
2. `TimeEntry` 实体补 `updatedAt/createdBy/updatedBy` + 注解;`createdAt` 的 `Instant.now()` 直散顺手改为 `@CreatedDate` 接管(skill Clock 规则:重构触碰即迁移)

### 非目标

- 不动其余 6 张表(裁决如上,记档即结论);
- 不对外暴露 createdBy/updatedBy(DTO 不加字段——对外永不暴露内部数字 id,AGENTS 硬规则);
- 不做历史回填、不加 FK、不动 cleanDatabase 清单(无新表)、不动 entities.puml(审计字段非领域字段);
- 不给别的实体补 `Instant.now()` 直散(未触碰不顺手改)。

## 详细设计(代码级修改计划)

| 文件 | 改动 |
|---|---|
| `persistence/src/main/resources/db/migration/V36__time_entry_audit.sql` | 新建,三条 add column(见上),头注释记裁决与 V34 形态引用 |
| `persistence/src/main/kotlin/yangzhou/persistence/entities.kt`(TimeEntry) | 改:`@CreatedDate var createdAt: Instant = Instant.now()`(沿用 Workspace 形态,var + 注解);新增 `@LastModifiedDate var updatedAt: Instant? = null`(新列存量 NULL → 必须可空,与 Workspace 非空形态有意不同)、`@CreatedBy var createdBy: Long? = null`、`@LastModifiedBy var updatedBy: Long? = null` |
| `api/src/test/kotlin/yangzhou/api/time/TimeEntryAuditApiTest.kt` | 新建集成测试(extends AbstractApiTest):①创建 time_entry → `jdbc` 直查 `created_by` = 当前成员内部 id、`updated_at` 非空;②更新 minutes → `updated_by` 变为当前成员;③无认证上下文路径(github 同步等系统写入)不在本票制造,不测 |

### 否决的备选

- **给 7 张全铺**(求整齐):违反票拍板的「按需」,给 append-only 表加 update 语义列是噪音;
- **created_by 加 FK**:V34 已裁决 workspace↔member 互引循环依赖,不加;
- **DTO 暴露记账人**:内部 id 不可外泄;要显示人名走 join 的公开需求出现时另票;
- **backfill**:无从考证,写假数据比 NULL 更糟。

## 手工测试用例(用户 build/debug 阶段执行)

1. `gradle clean build` 绿(迁移在 Testcontainers 全量跑);
2. Web:item 详情记一条工时 → 改 minutes → 无报错(行为不变,纯审计);
3. 库内抽查(可选):`select created_by, updated_by from time_entry order by id desc limit 1` → 两列有值 = 当前登录成员内部 id。

## 验收(对照票)

- [ ] time_entry 四元组齐,新写入有行为者
- [ ] 其余 6 表裁决记档(spec 本节即结论)
- [ ] 集成测试绿;既有 time 相关测试不回归

## As-built

(完工后补)
