# spec 0177-S2 — YPJ-293 后端·账号治理 API

> 主 spec:`docs/spec/0177-YPJ-166-multi-user-authz-spec.md` §4.4 / §2.3 B 表 / §7。S1 子文档(`0177-s1`)的续篇:S1 做地基(status/黑名单机制/自助改密),本票做**治理端点全套 + whoami**。

## ① 需求(范围/非目标)

### 端点一览(admin 除注明外;全挂 requireAdmin)

| 端点 | 语义 | 关键分支(B 表) |
|---|---|---|
| POST /api/members(**扩展**) | 现 displayName-only → 加 `username?`/`password?`/`projectIds?`。有凭据=真人账号(username 唯一 409、重名成员 409 既有、password ≥8、**必挂 ≥1 项目 400(C8)**、挂池=ProjectMember 行 role=member);无凭据=虚拟(现状不变,**不强制挂池**,见开放决策 2) | 未挂项目 400 / 重名 409 / username 占用 409 / <8 位 400 |
| PUT /api/members/{memberId}/credentials | `username?`+`password`(≥8)。真人=重置密码(+黑名单踢会话);虚拟=**设凭据即升级**(必须同时给 username,唯一 409) | <8 位 400 / username 占用 409 / 不存在 404 |
| PUT /api/members/{memberId}/status | `{status: "active"\|"disabled"}`。禁用=黑名单 upsert;复职=删行(S1 方法已落,本票接端点)。status 值大小写随 V73 先例用大写枚举名,请求线收小写归一 | **最后 admin 禁用自己 409** / 非法枚举 400 |
| PUT /api/members/{memberId}/admin | `{admin: true\|false}`。授=插 (admin, NULL) 行(已有则幂等 no-op);收=删行(无则 no-op) | **最后 admin 不可收 409**(收自己且是唯一 admin;收自己非最后放行) |
| DELETE /api/members/{memberId} | **放开真人可删**(现「登录账号不可删除」守卫 → 改「自己不可删」);非终态 assignee 409、引用计数 409 守卫原样;删号后 member 行没了,filter sub 存在性即拒(S1);blacklist/role 行随 FK 级联清 | 自删 409 / 非终态 assignee 409 / 引用计数 409 |
| GET /api/members/me(**新**,登录者) | whoami:MemberResponse 全字段 + `admin: Boolean` + `projectRoles: [{projectId, role}]`(S2 恒空数组,S3 落 editor/viewer 行后自然有数据;S5 导航 / S7 权限 UI 的落地依赖) | — |
| PUT /api/members/me/password | S1 已落,不动(列此仅为端点全景) | — |

### 非目标(归 S3+)

- §4.4 表中两个 **project-scoped** 端点(POST /projects/{key}/members/account、PUT /projects/{key}/members/{memberId}/credentials)→ **S3**(依赖 requireEditor,S3 才建);详见开放决策 1
- 三级守卫平移 / 可见性过滤 / 建删项目门 / 项目成员管理 API → S3
- web 任何页面(S6/S7);refresh(defer 已定稿)
- 成员目录两级门(矩阵 3a/3b)→ S3

## 开放决策(请复核——本票文档门拍板项)

1. **§4.4「全部端点」的收窄**:表内两行 project-scoped 端点归 S3 不归 S2。依据:依赖 requireEditor(S3 建),S2 中间态无 editor 行可判;§5「项目成员管理 API(editor+)」整段与 §7 表 S3 内容吻合;§5/C 表标题的「(S2)」是 R8 细化前旧口径,§7(建票定稿)为准。
2. **C8 只约束真账号**:矩阵行 24(全局建登录账号)标 C8、行 30(建虚拟)未标;注 102「C8 使建号不产生游客」——游客只对登录账号有意义。虚拟成员维持现状不挂池(与现实现一致判例),web members 页 / api.http §4 / e2e fixture 零破坏。真账号建号走 UI 在 S6,本票仅 API 层。
3. **DELETE 放开真人可删**:§4.4「守卫复用:…自己不可删」+ filter sub 存在性拒 → 真人账号(admin 操作、非自己)可删;现「登录账号不可删除」守卫废除。删除是全局治理动作,黑名单/角色行 FK 级联清(V73 已级联)。
4. **whoami 形状**:`GET /api/members/me` 现状无此端点(web 靠 GET /api/members 全列表 + `!virtual` 猜自己,login/page.tsx:33);本票补正经端点,`projectRoles` 字段 S2 恒空但形状现在定死,S3/S5/S7 不再动契约。

## ② 详细设计(文件级落点)

| 件 | 位置 | 说明 |
|---|---|---|
| AdminGuard 加 `isAdmin(): Boolean` | api/guard/AdminGuard.kt | 现 requireAdmin 提取判定内核;whoami 与「最后 admin」计数同源(admin 兜底单缝纪律:admin 判定只住此文件);加 `countWorkspaceAdmins(): Int`(查 role=admin ∧ project_id NULL 行数) |
| MemberRoleRepository 加查询 | persistence/repository/Repositories.kt | `findByRoleAndProjectIdIsNull()`(admin 行)、`deleteByMemberIdAndRoleAndProjectIdIsNull` 等;单表派生查询,不过 JDBC client 红线 |
| 建号扩展 | MemberService.create(改 createVirtual→统一 create) | 有凭据分支:username 唯一(409)、password≥8(Bean Validation)、projectIds 逐个 resolve(ProjectRepository.findByObjectId,404)、插 ProjectMember(role=member,已入池幂等跳过)、passwordHash=BCrypt;无凭据分支=现 createVirtual 原样 |
| 重置凭据 | MemberService.resetCredentials + Controller 端点 | 虚拟:username 必给(400 if 缺)+唯一 409;写 username+passwordHash;真人:只写 passwordHash;两路都 blacklist.upsert(踢会话)。204 |
| 禁用/复职 | MemberService.setStatus + Controller 端点 | disabled→upsert / active→delete(方法 S1 已落);最后 admin 禁用自己:isAdmin(me) ∧ countWorkspaceAdmins()==1 ∧ 目标==me → 409;status 枚举大小写:请求收 `active/disabled` 小写,映射 V73 大写枚举名(S1 坑复用) |
| 授/收 admin | MemberService.setAdmin + Controller 端点 | 授=无行则插 / 收=有行则删(幂等);最后 admin 不可收:目标==me ∧ count==1 ∧ 收 → 409 |
| 删号放开 | MemberService.delete(改) | 删「passwordHash != null → 409」守卫,换「memberId == me.objectId → 409 自删」;其余守卫(非终态 assignee、引用计数)原样 |
| whoami | MemberController + WhoamiResponse | GET /api/members/me;admin=guard.isAdmin();projectRoles=member_role(此成员,S2 恒空) |
| DTO | MemberController 底部 | 语义后缀:CreateMemberRequest(扩展)、ResetCredentialsRequest、SetMemberStatusRequest、SetMemberAdminRequest、WhoamiResponse |
| 契约 | script/api.http | 新 §23(S2 治理;节内自含:自建一次性项目/成员,节末清场),§4 虚拟建号不动(开放决策 2 下不破坏) |
| 测试 | api/src/integrationTest MemberAdminApiTest(新) | B 表分支全覆盖:建号(真账号挂池/未挂 400/重名 409/username 占用 409/<8 位 400/虚拟不变)/重置(真+虚拟升级/黑名单踢/<8)/status(禁用后 login 401+filter 拒/复职/最后 admin 自禁 409)/admin(授收幂等/最后 admin 收自己 409/收自己非最后放行)/delete(真人可删/自删 409/删后 token 401/三守卫)/whoami(admin 真/虚拟 false) |
| 图/迁移 | — | **零迁移、entities.puml 无变化**(表 S1 已建;本票纯端点层) |

否决备选:status 用 PATCH /members/{id}(半更新语义混乱,仓内 PUT 全量惯例);admin 授收用 POST/DELETE /members/{id}/roles(暴露内部叉表形状,矩阵语义是「workspace admin」布尔而非角色集合);whoami 放 /api/auth/me(成员语义住 member 切片,ADR-0007 垂直切片归位)。

## ③ 手工测试用例(api.http §23,用户 build/debug 阶段执行)

1. 登录 admin(节内自建)→ whoami:admin=true
2. 建真账号(username+password+projectIds)→ 201;新账号新会话登录可进
3. 建真账号缺 projectIds → 400;重名 → 409;username 占用 → 409;password 7 位 → 400
4. 重置该账号密码 → 204;旧密码登录 401;新密码可登
5. 禁用该账号 → 204;其存量 token 即失效(新请求 401);再 login → 401「账号已禁用」;复职 → 删行,可登
6. 授 admin 给该账号 → whoami admin=true;收回 → false;收自己的 admin(唯一 admin)→ 409
7. 删该账号 → 204;其 token 401(sub 不存在);自删 → 409
8. 虚拟成员建号(displayName only)→ 201 不变(回归 §4)

> 手工测试用例落档豁免:纯 API 票、web 零改动、主路径由 api.http §23 + e2e(既有不破)覆盖,理由随 PR 注明(2026-11-22 例外条款)。

## 验收

- [ ] `gradle build` + `integrationTest` 双绿(新 MemberAdminApiTest 全 B 表分支)
- [ ] api.http §23 全绿(8082);§4 回归不破
- [ ] e2e 全量绿(member.spec 既有断言不破)
- [ ] 开放决策 1–4 经用户确认
