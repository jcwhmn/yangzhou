# spec 0081 — V25 YPJ-102 Leader 权限模型(项目设置/sprint 管理门槛)

> 票:YPJ-102。约束:ADR-0003 授权/属性正交(RBAC 管「谁可以做」,属性体系管「谁做得好」),角色永不进匹配输入。性质:权限是**契约/语义变更**(改已发布行为:管理端点从「登录即可调」变「按角色」),设计须人拍板后实施。

## 一、需求

### 范围
- 引入**项目级 leader 角色**:项目设置与 sprint 管理类写端点加门槛。
- 权限清单(门槛端点,全部是「配置/管理」语义,item 日常编辑**不在内**):
  - **workflow**:statuses 增删改、transitions 配置
  - **ItemGroup**:group-types CRUD、通用 groups CRUD
  - **sprint / release** CRUD(两者同属组管理语义,一并纳入)
  - **GitHub**:repos 挂载增删、workflow-rules
  - **成员池**:project members 增删、**leader 设置本身**
  - **项目删除**(现状任何登录用户可删任何项目,一并收进门槛)
- **读端点一律不设限**(登录即可读,维持现状)。
- item 日常操作(建 item / move / 指派 / 评论 / 工时)不设限——那是"干活",不是"管项目"。

### 非目标
- 不做 workspace 级权限(词表管理、成员目录、GitHub token 设置——workspace 级 RBAC 另票)。
- 不做多级角色(viewer/admin 等)——只 leader/普通两级,role 列留扩展空间但不实现。
- 不做前端入口按角色隐藏(403 时统一错误呈现即可,UI 收敛另票)。
- 角色不进匹配输入(ADR-0003,硬约束)。

## 二、详细设计(代码级)

### 1. 存储:迁移 `V36__project_member_role.sql`
```sql
alter table project_member
    add column role varchar(20) not null default 'member',
    add constraint ck_project_member_role check (role in ('member','leader'));
```
- leader = 池内 `role='leader'` 行,**允许多 leader**(拍板点 1)。
- 迁移把**项目创建者(created_by 指向的池内行)置为 leader**(拍板点 2);无对应池行的项目不设 leader,走兼容语义。

### 2. 语义:两规则(拍板核心)
- **规则 R1(创建即管)**:建项目者自动入池且自动 leader(改 ProjectService.create,与 YPJ-62「创建者自动入池」并轨)。
- **规则 R2(无 leader 兼容)**:项目无任何 leader 时,池内所有成员皆可管理(向后兼容存量/单人场景,零感知);**有 leader 时,仅 leader 可管理**。
- 单人 workspace 语义:单登录用户建项目 → R1 自动 leader → 天然 leader,无需任何配置。✔ 回答票面问题。

### 3. 守卫:service 层集中检查
- `api/guard/LeaderGuard.kt`(新,不建业务切片):`requireLeader(projectKey): Project`(取当前 member → 查项目 → 查池行 → R1/R2 判定;不过抛 `ForbiddenException`)。
- 当前身份:JwtAuthFilter 的 principal(subject=username)→ 经 MemberRepository 解 memberId(guard 内完成,controller 不改签名)。
- `ForbiddenException` 加入 `error/exceptions.kt` + GlobalExceptionHandler 映射 **403**(形状统一 code/message)。
- 纳入门槛的 service 方法开头调 guard(workflow/grouptype/sprint/release/github/projectmember/project 删除各切片,约 8 个 service);**不加** Spring Security method security(权限数据在业务表,service 查最直接,否决 @PreAuthorize)。

### 4. leader 设置端点(池管理切片内)
- `PUT /projects/{key}/members/{memberId}/role` body `{role: "leader"|"member"}`:设/撤 leader。门槛:设 leader 需 leader(或 R2 兼容态);**最后一个 leader 不可自撤**(防锁死,409)。
- ProjectMemberDto 加 role 字段(池列表可见谁 leader,web 侧显示用)。

### 5. 否决的备选
- **project 表加 leader_member_id 单列**:单 leader 锁死设计,多人共管要改列语义,否决。
- **独立 project_role 表**:两级角色不值得一张表,池行加列最薄,否决。
- **web 层互软**:前端隐藏不设门槛,否决(安全必须服务端)。

### 6. 测试(读 backend-testing skill 后按双轨写)
- 单测:LeaderGuard 规则矩阵(R1/R2/非池内 403/无 leader 兼容/最后 leader 自撤 409)。
- 集成:门槛端点抽样(workflow statuses、sprint create、project delete、role 设置链):非 leader 403 / leader 200 / 无 leader 兼容 200;项目删除门槛回归(api.http §15 会红,同步更新)。
- `project_member` 无新表,AbstractApiTest cleanDatabase 清单不动(按 skill 核对)。
- api.http:role 设置端点入对应区块 + 负例区补「非 leader 设 leader 403」。

## 三、手工测试用例(供 build/debug,PM 实例 8081)
1. 单人(dogfood 默认):登录唯一用户建项目 → 池列表见自己 role=leader → 各管理端点照常(零感知)✔
2. 建第二登录用户 B,由 leader 把 B 入池(B role=member)→ B 调「加状态」→ 403;B 建 item/move → 正常。
3. B 被设 leader → B 加状态 → 200。
4. leader 自撤(唯一 leader)→ 409 锁死;设 B 为 leader 后自撤 → 200。
5. 兼容:手工把项目 leader 行全清(或新建无 leader 场景)→ 池内成员管理照常(R2)。
6. 回归:YPJ 项目(现役,有 leader=我)所有管理动作正常;api.http 全跑绿(§15 删除按新语义)。

## 需用户开工前拍板
1. **多 leader 允许**(池内 role 列)vs 单 leader(project 单列)——推荐多 leader。
2. **迁移**:存量项目创建者自动 leader + R2 兼容无 leader 项目——OK?
3. **门槛清单**含 release 管理与项目删除(推荐含)——OK?词表等 workspace 级**不含**(另票)——OK?
4. 最后 leader 不可自撤的锁死保护——OK?

## 四、As-built(2026-10-27 实现完成,双绿;手工用例待用户跑)

- 迁移:`V39__project_member_role.sql`(**spec 误写 V36,V36 已被 time_entry_audit 占用**,顺延 V39);role 列 + CHECK + 存量创建者置 leader(update 由 p.created_by = pm.member_id 匹配,无池行自然不设 leader)。
- 实体:`ProjectMember.role: String`(companion 常量 ROLE_MEMBER/ROLE_LEADER,仓内无 enum 先例,取 String 与 item_group.status 同风格);ProjectService.create R1 落地(入池即 role=leader)。
- `api/guard/LeaderGuard.kt`:requireLeader(key) / requireLeader(projectId);**身份取 JWT principal(member objectId)而非 MemberService.current()**——后者语义是「唯一凭据者」,多登录者会身份错认(spec 未预见,实现期纠正)。ForbiddenException→403 入 GlobalExceptionHandler(形状统一)。
- 门槛接入:WorkflowService(建/改/删状态含按 statusId 反查 projectId、transitions)+ GroupTypeService(类型×3、组×3)+ SprintService(create/update/delete)+ ReleaseService(同)+ GithubConfigService(addRepo/deleteRepo/replaceRules)+ ProjectMemberService(add/remove/setRole)+ ProjectService.delete;item 操作(assignItem 等)与全部读端点不设限。
- leader 设置:`PUT /projects/{key}/members/{memberId}/role`;**「最后 leader 不可降级」保护扩展到 remove()(设计只写 setRole)**——同一「禁止悬空 leader」不变量,移出最后 leader 等价静默开放管理。MemberDto 加 role 字段。
- **R2 读法偏离(请人复核)**:无 leader 行时放行**不校验池内**(任何登录者可管)——与历史「登录即可管」一致,防存量/无池项目创建者被锁死;spec 字面「池内成员皆可管理」未采纳(语义含糊判例:取与现有实现行为一致的读法)。
- 测试:单测 LeaderGuardUnitTest 8 例(R2 放行×2/leader 通过/池内非 leader 403/池外 403/无身份 403/坏 principal 403/NotFound 先判)+ 6 个既有 service 单测补 relaxed guard mock;集成 LeaderPermissionApiTest 4 例(R1 池见 leader;非 leader 管理端点 403 + item 建立与读放行 + 删项目 403;设 leader 链 + 唯一降级 409 + 非法 role 400;R2 全清后放行)。第二名凭据成员经 jdbc 造行后走 /api/auth/login 正常登录(无创建 API,黑盒唯一例外)。
- api.http:§6 加「设 leader」正例 + 负例区「唯一 leader 自撤 409」;「非 leader 设 leader 403」因脚本单登录身份不可演示,由集成测试覆盖。
- 验证:`gradle build` 绿;`gradle integrationTest --rerun-tasks` 全量真跑绿(3m47s)。
- 过程插曲(不影响提交内容):6 个既有单测文件 + ProjectMemberController.kt 的工作区副本行尾本已损坏(CR/混合,index 为 LF),处理时按 HEAD 重建为 LF 后重放改动;另发现 meMemberId() helper 在双凭据场景取首个非虚拟成员会取错人(集成测试绕过,helper 未改,注释已含「me」前提)。
