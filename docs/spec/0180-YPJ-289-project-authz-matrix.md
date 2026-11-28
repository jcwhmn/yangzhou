# spec 0177-S3 — YPJ-289 后端·项目授权矩阵(三级守卫/可见性)

> 主 spec:`docs/spec/0177-YPJ-166-multi-user-authz-spec.md` §2 矩阵 / §3.2 / §5 / §2.3 C 表。S1(已合)/S2(已合)续篇:账号基座与治理 API 已落,本票落**项目级授权**——池去角色化、三级守卫平移、可见性、建/删项目门、§4.4 两个 project-scoped 端点。

## §0 切片映射(2026-11-28 用户拍板拆分;开放决策 1–5 全同意)

| 切片 | 票号 | 范围 |
|---|---|---|
| a 授权地基 | **YPJ-299** | V74 迁移、ProjectGuard 四门、LeaderGuard 删/25 处平移、milestone/attribute 补门、池 setRole rework、守卫单测+平移集成测试 |
| b 可见性与门 | **YPJ-300** | 列表过滤、直访门、建删项目门+创建即管、目录两级门、capabilities/github 他人编辑门 |
| c 两端点 | **YPJ-301** | POST /projects/{key}/members/account、PUT /projects/{key}/members/{memberId}/credentials |

依赖:a→b、a→c(b/c 互不依赖);一子票一分支一 PR(仓规),本文档总纲随 a 的 PR 入 main。

## ⚠️ 架构偏离/契约变更显要标注

1. **LeaderGuard 废除**(父 spec ⚠️-2 已拍板):R2「无 leader 放行」与「最后 leader 保护」两条规则删除;25 个 `requireLeader` 调用点平移 `requireEditor`(语义扩张 leader→editor,R2 grill 拍板,非新偏离)。
2. **契约变更:项目内角色授/收端点**(PUT `/projects/{key}/members/{memberId}/role`):请求值 `leader|member` → `editor|viewer|member`(member=撤角色);落库 `project_member.role` → `member_role` 行。web 零 leader 引用(已 rg 实锤),旧值字符串渲染不破,UI 重做归 S7。
3. **新门:GET /api/members(全局成员目录)** 现状全员可读 → admin ∨ editor-anywhere(矩阵行 3b)。纯池成员/viewer 调用 403——web 成员页对非 editor 用户中间态 403,S6 重做;PM prod 单 admin 无感。
4. **新门:POST /api/projects** 现状任意登录者可建 → editor-anywhere ∨ admin(矩阵行 25);**DELETE 项目** leader → admin only(C3)。
5. **§4.4 两个 project-scoped 端点在本票落地**(S2 开放决策 1 的既定归票,非偏离):POST `/projects/{key}/members/account`、PUT `/projects/{key}/members/{memberId}/credentials`,均 requireEditor。

## ① 需求(范围/非目标)

### 范围

- **迁移 V74**:project_member 去 role 列 + backfill(leader→(member, editor, project) 行,父 spec §3.2)。
- **守卫**:新立 `ProjectGuard`(requireMember/requireEditor + 两个成员域门);requireAdmin 已有(AdminGuard);删 `LeaderGuard`。25 调用点平移 + milestone 补门(原无门,§5)。
- **可见性**:项目列表过滤(角色∪池∪admin 全量,行 6);项目直访 requireMember(404 先于 403,行 7/8);成员目录两级(行 3a/3b)。
- **建/删项目**:建=editor-anywhere ∨ admin,创建即管落 (creator, editor, project) 行;删=admin only。
- **capabilities/github-username 他人编辑门**(矩阵行 4/5,C1):本人恒可;他人=editor-池内 ∨ admin。
- **api.http 同步**(新 §24;既有 leader 字样节改 editor)。

### 非目标(归 S4+)

- item 级门全量接线(item CRUD/评论/工时/看板列间 requireMember;拉入拉出/归置/删除门 requireEditor;viewer 建号去向强制)→ S4(YPJ-294)
- milestone 之外的其余项目数据**读门**全量接线(items/board/groups/releases 等直访)→ S4 随 item 级门一起
- attribute 词表 admin 门(行 31)→ 本票顺手落(一行守卫,见开放决策 5)
- web 全部(S5/S6/S7);refresh(defer)

## 开放决策(请复核——本票文档门拍板项)

1. **项目内建号仅真账号**:POST `/projects/{key}/members/account` 必带 username+password(缺→400);不给凭据=虚拟的路径保持 admin 全局端点 only(Q19/行 30——editor 建虚拟会绕开「建虚拟仅 admin」)。既有池加成员端点可把既有虚拟成员入池,不冲突。
2. **editor 重置凭据仅限池内真人**:目标非池内成员 404;虚拟成员 409(设凭据=升级是 admin 全局端点的语义,C5 只覆盖「重置密码」)。
3. **授/收角色对虚拟成员 400**:Q19「虚拟只进池不进 role」落为显式校验(虚拟成员拿 editor 行会使矩阵行 3b/管理语义失真)。
4. **全局目录门取「editor-anywhere」**:admin ∨ 持有 ≥1 条 (editor, project) 行即可读全局目录(含虚拟+能力);viewer/纯池成员 403。依据行 3b「全局目录=editor+」。
5. **attribute 词表 POST/DELETE 挂 requireAdmin**(行 31/Q15),GET 保持登录即可(词表全员要读)。行 31 父 spec 归 S3 后顺手落地,免留无门端点过 S4。

## ② 详细设计(文件级落点)

| 件 | 位置 | 说明 |
|---|---|---|
| 迁移 V74 | `db/migration/V74__project_member_de_role.sql` | ①backfill:`insert into member_role(object_id,member_id,role,project_id) select gen_random_uuid(),pm.member_id,'editor',pm.project_id from project_member pm where pm.role='leader' and not exists(...同 member+project editor 行)`(V73 backfill 同款);②`drop constraint ck_project_member_role` + `drop column role`(V39 的逆)。**零新表,cleanDatabase 不动**(member_role 已在清单)。真实 backfill 断言依赖存量数据,测试库全新无法构造——语义正确性走 PM prod 部署后人工核对(见③-9) |
| ProjectGuard 新立 | `api/guard/ProjectGuard.kt` | 四门+身份助手:①`requireMember(projectKey/projectObjectId)`=池行∨(editor/viewer)角色行∨admin,项目先 resolve(NotFound 先于 403);②`requireEditor(同参)`=(me, editor, project) 行∨admin;③`requireEditorAnywhere()`=admin∨≥1 editor 行(全局目录/建项目门);④`requirePoolEditor(memberObjectId)`=admin∨「我与目标池行项目交集 ∩ 我的 editor 行」非空(capabilities/github 他人编辑,C1)。admin 判定一律走 `adminGuard.isAdmin()`(单缝纪律);身份取 JWT principal(LeaderGuard 先例,不用 MemberService.current());403 文案「需要项目 editor 权限」/「无权访问该项目」 |
| LeaderGuard 删除 | `api/guard/LeaderGuard.kt` | 整文件删;`ProjectMember.ROLE_LEADER/ROLE_MEMBER` 常量与实体 role 属性删除(Entities.kt) |
| 25 调用点平移 | 7 个 service | GithubConfigService 3 / WorkflowService 4 / ItemTypeService 3 / ItemGroupService 8 / ProjectMemberService 3 / ReleaseService 3 → `projectGuard.requireEditor(key)`;ProjectService 1(delete)→ `adminGuard.requireAdmin()`(C3)。字段注入 leaderGuard→projectGuard/adminGuard 改名 |
| milestone 补门 | MilestoneService | POST/DELETE milestone、PUT milestone-items(归置)→ requireEditor(行 16,原无门);GET 两读端点暂不挂(读门全量接线归 S4) |
| 词表门 | AttributeController | POST/DELETE → adminGuard.requireAdmin(开放决策 5);GET 不动 |
| ProjectService | create/list/delete + get 直访 | create:creator 池行去掉 role 参数 + 落 `MemberRole(memberId, editor, project.id)`(创建即管);门=requireEditorAnywhere(controller 挂);list:先取可见 projectId 集(池行∪角色行;admin 全量)再过滤组装;delete:requireAdmin;GET /projects/{key} 直访=requireMember(controller 挂,404 先行) |
| ProjectMemberService rework | add/list/setRole | add:池行无 role 参数;setRole 重写:收 `editor\|viewer\|member`——editor/viewer=upsert member_role 行(虚拟 400,开放决策 3)、member=删该 member_role 行;list:DTO.role 改从 member_role 读(editor/viewer;无行=「member」池成员),形状不变值域变(⚠️-2);remove/restrictedPool/assertAssignable 不动(纯池语义) |
| MemberService 目录门/他人编辑门 | MemberController/Service | GET /api/members → requireEditorAnywhere(⚠️-3);capabilities PUT/DELETE 与 setGithubUsername:目标==me 放行,否则 requirePoolEditor(memberId)(行 4/5);GET capabilities 不动(无矩阵读门);whoami 不动(projectRoles 查 member_role,backfill 后自然有数据) |
| §4.4 两端点 | MemberController/Service | POST `/projects/{key}/members/account`(requireEditor):displayName+username+password 必填(开放决策 1),username 唯一 409/重名 409/<8 位 400,入池+勾选 grantEditor→+(editor, project) 行;PUT `/projects/{key}/members/{memberId}/credentials`(requireEditor):目标池内校验(404)+真人校验(虚拟 409,开放决策 2),改 passwordHash+blacklist.upsert(踢会话) |
| 新查询 | Repositories.kt | `ProjectMemberRepository.findByMemberObjectId(memberObjectId)`(list 可见性/poolEditor 交集用);其余派生查询够用(memberRoles.findByMemberId 已有)。不过 JDBC client 红线 |
| DTO | 各 controller 底部 | ProjectAccountCreateRequest(displayName/username/password/grantEditor)、ProjectCredentialResetRequest(password);语义后缀惯例 |
| 契约 | script/api.http | 新 §24(S3 授权矩阵;节内自建项目+三成员,节末清场,随 §23 先例);既有节 leader 字样改 editor;**改 REST API 必同步**仓规 |
| 测试(单元) | LeaderGuardUnitTest → ProjectGuardUnitTest | 判定矩阵直测:requireMember(池✓/角色✓/admin✓/全无✗/404 先行)、requireEditor(editor✓/viewer✗/池✗/admin✓)、requireEditorAnywhere、requirePoolEditor 交集/自编辑;mock 风格随既有 |
| 测试(集成) | LeaderPermissionApiTest → ProjectAuthzApiTest | 平移抽样(workflow/itemtype/release 管理端点 editor 200/池成员 403/admin 兜底 200)、R2 删除反证(全 editor 行清空→池成员 403,与旧 R2 相反)、setRole 三值+虚拟 400、建项目 creator editor 行+池成员 403、删项目 editor 403/admin 204、可见性(list 过滤/直访 404)、目录门(池成员 403/editor 200)、capabilities 他人编辑门、§4.4 两端点全分支、whoami projectRoles 有数据 |
| 图 | entities.puml | ProjectMember 去 role 行(改 Flyway schema 同步规约);domain.puml 不涉 |

否决备选:守卫合并进 AdminGuard(管理门/治理门语义混居,单文件多职责);requireMember 落 controller AOP/注解(仓内先例是 service 直调守卫,无 AOP 基建);ProjectMember 池表也迁 member_role(池=可见性+item 门,角色=管理,两语义父 spec §2 注已分立,混表丢池成员);setRole 用 POST/DELETE roles 端点(暴露叉表形状,S2 已否决同款)。

## ③ 手工测试用例(api.http §24,用户 build/debug 阶段执行;环境 8082)

1. editor 建项目(节内自建 editor)→ 201;whoami projectRoles 含 (该项目, editor)(创建即管)
2. editor 项目内建号(勾 editor)→ 201;新账号登录可见项目、可管 milestone;不勾 → 仅入池,管理端点 403
3. 池成员(无角色):管理端点(建 milestone/release/sprint/池管理)403;GET 项目详情 200;GET /api/members 403(开放决策 4)
4. admin 兜底:admin 无池行无角色行直访项目/管理端点全通
5. setRole:授 viewer → whoami 见 viewer;池成员管理端点仍 403;收(member)→ 角色行删、池行在
6. 虚拟成员授角色 → 400;editor 重置虚拟成员凭据 → 409(开放决策 2)
7. 删项目:editor 403;admin 204(清场步骤顺带验证)
8. 无关项目直访 → 404;项目列表不含无关项目
9. **PM prod 部署后人工**:原 leader 账号 whoami projectRoles 含 editor 行(V74 backfill 实数据验证)

> 手工测试用例落档豁免:纯 API 票、web 零改动,主路径由 api.http §24 + 集成测试覆盖,理由随 PR 注明(2026-11-22 例外条款)。

## 验收

- [x] `gradle build` + `integrationTest` 双绿(a 片 #277;b 片 #278 会话全量绿,顺带修 A60 潜伏红)
- [x] 全端点对照主 spec §2 矩阵逐个标门(审计表原定「随 PR」,#277/#278 均漏带,2026-11-29 补入下方 As-built 节随 docs commit 入 main)
- [x] `rg "requireLeader|WorkspaceGuard" backend --g '*.kt'` 清零(a 片验,b 片复核 2026-11-29)
- [x] api.http §24 全绿(8082);既有节回归不破(421 请求 0 失败,agent 复刻+用户 CLI 双确认)
- [x] 开放决策 1–5 经用户确认(2026-11-28 拍板)
- [ ] §4.4 两端点全分支(c 片 YPJ-301,验收随该票)

## As-built 审计表:全端点 × 门(S3 落地实况,2026-11-29;b 片合并后补)

门缩写:admin=requireAdmin / E=requireEditor / EA=requireEditorAnywhere / M=requireMember / PE=requirePoolEditor。
直访语义(随 #278 合并确认):项目不存在 404(NotFound 先行)/存在但无权 403「无权访问该项目」。

### 成员域 MemberController

| 端点 | 门 | 矩阵行 | 落地 |
|---|---|---|---|
| GET /api/members/me(whoami) | 登录即可;projectRoles 查 member_role | 1 | S1/S2 |
| GET /api/members(全局目录) | EA | 3b | b ⚠️纯池成员/viewer 403(web 中间态归 S6) |
| POST /api/members(建号) | admin | 24/30 | S2 |
| PUT /api/members/{id}/credentials(全局) | admin | 30 | S2 |
| PUT /api/members/{id}/status | admin | 27 | S2 |
| PUT /api/members/{id}/admin | admin(最后 admin 守卫) | 29 | S2 |
| DELETE /api/members/{id} | admin(非终态引用守卫) | 28 | S2 |
| PUT /api/members/me/password | 本人(踢全会话) | 2 | S1 |
| PUT /api/members/me/last-project | 登录即可 | — | 既有 |
| PUT /api/members/{id}/last-project | 登录即可(现状;矩阵无行) | — | 既有 |
| PUT /api/members/{id}/github-username | 本人∨admin∨PE | 4/5 | b |
| GET /api/members/{id}/capabilities | 登录即可(矩阵无读门) | — | 既有 |
| PUT /api/members/{id}/capabilities | 本人∨admin∨PE | 4/5 | b |
| DELETE /api/members/{id}/capabilities/{attr} | 本人∨admin∨PE | 4/5 | b |

### 项目域 ProjectController/Service

| 端点 | 门 | 矩阵行 | 落地 |
|---|---|---|---|
| POST /api/projects | EA;创建即管 (creator, editor) 行+入池 | 25 | b |
| GET /api/projects | 可见性过滤(service 内联:池∪角色;admin 全量) | 6 | b |
| GET /api/projects/{key} | M | 7/8 | b |
| PATCH /api/projects/{key}/settings | 无门(现状;矩阵无行,归类待定) | — | 未接线 |
| GET /api/projects/{key}/deletion-preview | 无门(CLI admin 链路;删项目本身有门) | — | 未接线 |
| DELETE /api/projects/{key} | admin(C3) | 26 | a |
| GET /api/projects/{key}/gantt、export.xlsx | 无门(读门归 S4) | — | 未接线(S4) |

### 项目池 ProjectMemberController

| 端点 | 门 | 矩阵行 | 落地 |
|---|---|---|---|
| GET /api/projects/{key}/members | M | 3a | b |
| POST /api/projects/{key}/members | E | 21 | a |
| DELETE /api/projects/{key}/members/{memberId} | E(出池即撤角色) | 21 | a |
| PUT /api/projects/{key}/members/{memberId}/role | E;editor\|viewer\|member 三值;虚拟 400 | 21/Q19 | a |
| POST /api/projects/{key}/members/account | E(必带凭据;username 唯一/重名 409) | §4.4 开放决策1 | **c 待做** |
| PUT /api/projects/{key}/members/{memberId}/credentials | E(目标池内 404;虚拟 409) | §4.4 开放决策2 | **c 待做** |

### workflow / itemtype / itemgroup / release / github / milestone(写门,均为 a 片 requireLeader→requireEditor 平移)

| 端点 | 门 | 行 | 备注 |
|---|---|---|---|
| POST /api/projects/{key}/statuses;PATCH/DELETE /api/statuses/{statusId} | E | 17 | WorkflowService ×3 |
| PUT /api/projects/{key}/transitions | E | 18 | WorkflowService ×1 |
| GET /api/projects/{key}/transitions、statuses 读 | 无门(读门归 S4) | — | |
| POST/PATCH/DELETE /api/projects/{key}/item-types[/{typeKey}] | E | 19 | ItemTypeService ×3;GET 无门 |
| POST /api/projects/{key}/group-types;PATCH/DELETE /{typeKey};PUT /{typeKey}/workflow | E | 20 | ItemGroupService ×4;GET 无门 |
| POST /api/projects/{key}/groups;PATCH/DELETE /{groupId};POST /{groupId}/actions/{function} | E | 20 | ItemGroupService ×4;GET 无门 |
| PUT /api/items/{itemId}/group-membership、/board-membership | 无门(item 级) | 13 | S4 |
| POST/PATCH/DELETE /api/projects/{key}/releases[/{releaseId}] | E | 22 | ReleaseService ×3;GET/history/membership 读无门;PUT /items/{id}/release-membership 无门(S4) |
| POST /api/projects/{key}/repos;DELETE /{repoId};PUT /api/projects/{key}/workflow-rules | E | 23 | GithubConfigService ×3;GET/列表无门;workspace github-token 三端点 workspace 级无门(矩阵无行) |
| POST/PATCH/DELETE /api/projects/{key}/milestones[/{id}];PUT /{id}/items(归置) | E(a 片补门,原无门) | 16 | MilestoneService ×4;GET 两读端点无门(S4) |

### attribute 词表 AttributeController

| 端点 | 门 | 行 | 落地 |
|---|---|---|---|
| POST/PATCH/DELETE /api/attributes[...] | admin | 31 | a |
| GET 词表 | 登录即可 | 31 注 | a(开放决策 5) |

### item 级(行 9-15)与看板读端点 | 无门 | 9-15 | S4(YPJ-294)全量接线

S3 欠账清零:除上表「c 待做」两条(归 YPJ-301)与「未接线/S4」既有范围外,矩阵行 1-31 已标门端点全部落地。
