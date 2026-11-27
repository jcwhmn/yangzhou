# spec 0177-S1 — YPJ-288 后端·账号基座

> 主 spec:`docs/spec/0177-YPJ-166-multi-user-authz-spec.md` §3.1 / §4.1–§4.3 / §2.3 A、B 表。本票只做地基,不做 §4.4 治理端点(S2)。

## 做什么(范围)

1. **V73 迁移**(§3.1):member 加 `status`(CHECK active/disabled);建 `member_blacklist`(member 级黑名单,每成员至多一行);建 `member_role`(统一角色叉表,scope CHECK:admin 仅 NULL / editor、viewer 必关联项目);backfill:`password_hash` 非空的既有账号 → 落 `(admin, NULL)` 行。
2. **requireAdmin 守卫新立**(§5 三级守卫之一):`(me, admin, NULL)` 行判定;挂到既有成员治理端点 POST /api/members(建虚拟,矩阵行 30)与 DELETE /api/members/{id}(矩阵行 28)——收权 admin only。
3. **JwtAuthFilter 新链**(§4.2):验签后 member 按 sub 存在(删号即拒)→ `status == active` → 黑名单无有效行;登录端点补 disabled 拒绝 → 401「账号已禁用」。
4. **JWT ct 分型**(§4.1):login/bootstrap 请求加 `clientType`(缺省 web);token 加 `ct` claim;TTL 分键 `ttl-hours-web: 8` / `ttl-hours-cli: 720`(现 `ttl-hours: 720` 保留为兼容缺省,未设分键时回退);存量无 ct token 按 web。
5. **黑名单写语义**(§4.3,本票只建机制+一个调用方):自助改密 upsert(踢全部会话);禁用 upsert/复职删行的方法落好,端点在 S2;惰性清理(读时顺带删过期行,无定时任务)。
6. **PUT /api/members/me/password**(§4.4 唯一本票端点):验 old(错 → 401);new ≥8 位(Bean Validation);改后黑名单 upsert → 当前会话一并失效,客户端需重登。204 返回。
7. **yz CLI**(§2.3 D 表):login/bootstrap payload 恒传 `clientType: "cli"`(ApiClient.kt 两处);无新命令。
8. 契约与图同步:api.http 新增 me/password 段 + login 带 clientType 示例;entities.puml(member.status、MemberBlacklist、MemberRole)。

## 怎么做(实现落点)

| 件 | 位置 | 说明 |
|---|---|---|
| V73__member_auth_foundation.sql | persistence/migration | 三段 DDL + `insert into member_role select gen_random_uuid(), id, 'admin', null from member where password_hash is not null`(先例 V30+)。**偏离 §3.1 原文**:member.status 存 enum name 大写 `ACTIVE/DISABLED`(SD JDBC 4 默认按 name 映射,随 status_type='START' 先例;spec 原文小写会违 CHECK),role 值仍小写(随 ProjectMember.ROLE_* 先例) | |
| MemberStatus enum;Member 加 `status` | persistence/Entities.kt | enum+VARCHAR+CHECK(仓规);role 不引枚举,随 ProjectMember.ROLE_* 常量先例(§3.3) |
| MemberBlacklist / MemberRole 实体+repo | Entities.kt + Repositories.kt | 实体主构造 id/objectId/createdAt/updatedAt;FK 用 member_id bigint + 级联(spec SQL 原文) |
| AdminGuard.requireAdmin() | api/guard/AdminGuard.kt | 查 principal(member objectId)→ member_role 行;无行 403。S2 的 25 处 requireLeader 平移不动它 |
| 过滤链 | JwtAuthFilter(改) | 注入 MemberRepository + MemberBlacklistRepository:两次单行索引查询(§4.2 允许);黑名单行存在且过期 → 顺带删行后放行(惰性清理) |
| ct 分型 | JwtService(改)+ application.yml | `generate(subject, clientType)`;verify 不看 ct;`ttl-hours-web/-cli` 缺省回退 `ttl-hours` |
| disabled 拒登 / bootstrap 落 admin 行 | AuthService(改) | login 补 status 检查(BadCredentials→401);bootstrap 建号同时插 (admin, NULL) 行(§2.3 A 表) |
| 黑名单写语义 | business/member/MemberBlacklistService.kt(新) | upsert(memberId,TTL=cli 30d)/delete/有效行判定;自助改密在 MemberService 调 upsert |
| 自助改密 | MemberService + MemberController | `me/password`;密 Hash 存 BCrypt;old 错抛 BadCredentials |
| CLI clientType | cli/ApiClient.kt | 两处 payload 加一键 |

## 行为变化声明(用户可见)

- web 登录新 token TTL 720h → 8h(R8 定稿);CLI 30d 不变(M-166-5);存量 token 不受影响。
- POST /api/members、DELETE /api/members/{id} 从「任何登录者」收权为 admin only(S1 态=仅 bootstrap 账号是 admin)。
- 中间态(S1 后 S2 前):授权仍「全局同权」(§6),语义自洽无断裂;部署建议 S1→S2 同批。

## 验收

- [ ] `gradle build` + `integrationTest` 双绿;member_blacklist/member_role 入 AbstractApiTest cleanDatabase(FK 序,先读 backend-testing skill)
- [ ] 测试覆盖:disabled 登录 401 / 过滤链三拒(删号、disabled、黑名单)/ ct TTL web≠cli / 自助改密(old 错 401、<8 位 400、成功踢会话)/ requireAdmin 403 / backfill admin 行
- [ ] api.http 手测主路径绿(8082);entities.puml 已同步
- [ ] 合并后用户重建 prod/dev 容器(票面验收)
