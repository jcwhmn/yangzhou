# spec 0181 — YPJ-301 项目内建号与池内重置凭据(§4.4 两端点)

> 主 spec:`docs/spec/0177-YPJ-166-multi-user-authz-spec.md` §2 行 22/23 + §4.4;切片总纲:`docs/spec/0180-YPJ-289-project-authz-matrix.md`(开放决策 1/2 本票落实)。
> S1(#274)/S2(#276)/S3-a(#277)/S3-b(#278)已合;本票 = S3 切片 c,后端最后一片。
> **流程注(2026-11-29 用户定):本文档经用户 review 通过后方可编码;spec commit 先于任何实现 commit。**

## ① 需求

### 背景与用户故事

- ** editor(项目经理)**在项目页面直接创建新成员账号并令其立即参与本项目——不必请 admin 走全局建号页(S2 行 24 admin-only,editor 无权);建号时勾选「授予 editor」决定新人是只干活(池成员)还是可管理(行 22,R7 checkbox 语义)。
- **editor** 对池内成员遗忘密码的场景直接重置(行 23,C5),不必升级为 admin 事务;重置后该成员所有旧会话即刻失效(黑名单,§4.3)。

### 契约形状

**POST /api/projects/{key}/members/account**(门=requireEditor,矩阵行 22)

```jsonc
// 请求(字段全必填;grantEditor 缺省 false)
{ "displayName": "小李", "username": "xiaoli01", "password": "secret123", "grantEditor": true }

// 201 响应 = MemberResponse(同 S2 全局建号;memberId/virtual=false/admin=false)
```

- 行为 = S2 全局建号校验链全复用(见②),**必带凭据**:username 与 password 缺一即 400(开放决策 1——不给凭据=建虚拟的路径保持 admin 全局端点 only,防 editor 绕开「建虚拟仅 admin」Q19)。
- 创建即入池(本项目);`grantEditor=true` 追加落 `(member, editor, project)` 行;`false` 仅池(池成员,管理端点 403)。
- password ≥8 位(Bean Validation 400);username workspace 全局唯一(占用 409);displayName workspace 内唯一(重名 409)。

**PUT /api/projects/{key}/members/{memberId}/credentials**(门=requireEditor,矩阵行 23/C5)

```jsonc
// 请求(仅密码;username 不可改——改名是治理语义,全局端点 admin only)
{ "password": "newpass456" }

// 200 响应 = MemberResponse
```

- 目标校验顺序:项目不存在 404 → 成员不存在 404 → **非池内 404**(开放决策 2:「池内」是本端点的寻址域)→ **虚拟成员 409**(开放决策 2:设凭据=升级,是 admin 全局端点的语义,C5 只覆盖「重置密码」)。
- 成功 = 改 passwordHash + 黑名单 upsert(踢该成员全部会话,§4.3;含其当前会话,需重登)。

### 分支真值表(请求×前置→行为)

POST account:

| 前置 | 结果 |
|---|---|
| 调用者无 editor 行且非 admin | 403 需要项目 editor 权限 |
| 项目 key 不存在 | 404 |
| displayName 与既有成员重名 | 409 成员已存在 |
| username 已被占用 | 409 用户名已被占用 |
| username/password 只给其一 | 400 凭据需同时提供 |
| password < 8 位 | 400 密码至少 8 位 |
| 正常(grantEditor=true) | 201;入池+editor 行;whoami projectRoles 含该行 |
| 正常(grantEditor=false/缺省) | 201;仅入池;管理端点 403 |

PUT credentials:

| 前置 | 结果 |
|---|---|
| 调用者无 editor 行且非 admin | 403 |
| 项目/成员不存在 | 404 |
| 成员不在本项目池 | 404 该成员不在项目成员中 |
| 目标是虚拟成员(passwordHash=null) | 409(升级走 admin 全局端点) |
| password < 8 位 | 400 |
| 正常 | 200;旧密码登录 401;旧 token 即刻 401;新密码可登 |

admin 兜底:两端点对 admin 全通(无池行无角色行亦可)。

### 范围

- MemberController/MemberService 两端点 + DTO;复用 S2 校验链与黑名单机制。
- api.http 新 §25(节内自含+节末清场,随 §24 先例;**交付前 CLI 全文件复刻**,A61 规矩)。
- 集成测试:ProjectAuthzApiTest 追加「§4.4 两端点全分支」用例组。

### 非目标

- web 全部(S7 重做;成员页 UI 现按全局端点工作,项目内建号入口归 S7)。
- 虚拟成员升级路径(维持 S2 全局端点行为不变);username 修改(治理语义,不随本端点下放)。
- 池列表/目录/whoami 形状变更(零;新账号按既有规则自然可见)。

## ② 详细设计(文件级落点)

| 件 | 位置 | 说明 |
|---|---|---|
| Controller | `api/business/member/MemberController.kt` | 追加两端点(`@PostMapping("/projects/{key}/members/account")` 201、`@PutMapping("/projects/{key}/members/{memberId}/credentials")` 200;类级 `/api` 前缀既有)。守卫在 service(仓规:service 直调守卫,ProjectMemberService.add 先例) |
| Service | `api/business/member/MemberService.kt` | ①`createProjectAccount(projectKey, displayName, username, password, grantEditor)`:`projectGuard.requireEditor(projectKey)` → resolve project(404)→ **复用 `create(displayName, username, password, listOf(project.objectId))`**(校验链/池写入/错误文案全同源;必带凭据由 DTO @NotBlank 保证)→ `grantEditor` 时 `memberRoles.save(MemberRole(memberId=me.id, editor, projectId))`(新成员无旧角色行,无需先清;setRole 语义同源)→ 返回 MemberResponse。②`resetProjectCredentials(projectKey, memberId, password)`:requireEditor → project/member resolve 404 → 池内校验(`projectMembers.findByProjectObjectIdAndMemberObjectId`,缺 404「该成员不在项目成员中」,与 remove/setRole 文案同源)→ `passwordHash==null` 409「虚拟成员凭据升级请由 admin 在全局端点操作」→ `member.copy(passwordHash=encode)` save + `blacklist.upsert`(resetCredentials 真人分支同款三行) |
| DTO | MemberController 底部 | `ProjectAccountCreateRequest(@NotBlank displayName/username/password,@Size(min=8) password,grantEditor: Boolean = false)`、`ProjectCredentialResetRequest(@NotBlank @Size(min=8) password)`(0180 ② 预定名;独立于 S2 全局 `ResetCredentialsRequest`——语义后缀惯例,形状不同:无 username) |
| 依赖注入 | MemberService | 已有 memberRoles/projectMembers/projects/encoder/blacklist(实锤构造器);**仅新增 projectGuard**(构造器追加,同步 MemberServiceUnitTest 构造器) |
| 契约 | `script/api.http` | 新 §25(节内自建项目+editor+虚拟成员,节末清场;断言全分支=上表) |
| 测试(集成) | ProjectAuthzApiTest 追加 | 真值表全分支:建号 grantEditor 两态/400×3/409×2/门 403/项目 404;重置 200+踢会话(旧 token 401)/池外 404/虚拟 409/门 403/admin 兜底 |
| 测试(单元) | MemberServiceUnitTest | 构造器同步;createProjectAccount 走 create 复用路径断言(editor 行落库/mockk verify);resetProjectCredentials 池外/虚拟分支 |
| 图 | 不涉 | 零 schema/实体变更(entities/domain.puml 不动);Puml 同步规约不触发 |

否决备选:守卫挂 controller(仓规 service 直调);复用 S2 `resetCredentials` 加 `allowVirtual` 参数(读点分叉语义埋参数,两处调用各自语义直读更清);新建 ProjectMemberAccountService(两方法各 ~10 行,开新文件碎片化);返回体带 role(池列表已可查,MemberResponse 加字段即形状变更,无消费方需要)。

## ③ 手工测试用例(api.http §25,用户 build/debug 阶段执行;环境 8082)

1. editor(节内自建+授行)建号 grantEditor=true → 201;新账号登录可见项目、可建 milestone
2. editor 建号 grantEditor=false → 201;新账号登录:项目可见,管理端点 403
3. [负例] 缺 username / 缺 password / password 7 位 → 400;username 占用 / displayName 重名 → 409
4. [负例] 池成员(无 editor 行)建号 → 403;无关项目 key → 404
5. editor 重置池内真人密码 → 200;旧密码登录 401;新密码可登;旧 token 即刻 401(踢会话)
6. [负例] 重置池外真账号 → 404;重置池内虚拟成员 → 409
7. [负例] 池成员重置他人凭据 → 403;admin(无池无角色)两端点全通
8. 清场:删项目删账号,全文件可重复跑

> 手测落档豁免:纯 API 票、web 零改动,主路径由 api.http §25 + 集成测试覆盖(2026-11-22 例外条款,理由随 PR)。

## 验收

- [ ] `gradle build` + `integrationTest` 双绿(§4.4 两端点全分支)
- [ ] api.http §25 全绿(8082);全文件 421+N 请求 0 失败(CLI 复刻)
- [ ] 0180 审计表「c 待做」两条销账(随本票 PR 改 done)
- [ ] **本文档经用户 review 通过(编码前置门,2026-11-29 定)**

## 开放决策

无新增——0180 开放决策 1(项目内建号仅真账号)/2(重置仅限池内真人、虚拟 409)已拍板,本票是其落地;其余分支取 0177 行 22/23 + §4.3 黑名单既有语义。
