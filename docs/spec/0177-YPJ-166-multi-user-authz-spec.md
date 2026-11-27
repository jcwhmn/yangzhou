# spec 0177 — YPJ-166 多用户登录与授权矩阵(父票)

> 来源:YPJ-166(2026-11-11 立票);2026-11-28 用户拍板「仔细分析设计,做好 grill」。经 **6 轮 grill**(本文件「决策记录」节存全量拍板)达成设计共识。本文件=父票主文档:需求+设计+矩阵+迁移+子票索引;**子票实现时另写简短说明文档引用本文件**(用户 2026-11-28 拍板,替代逐子票完整 spec)。

## ⚠️ 架构破坏显要标注(必读)

本票**修订 ADR-0005「单用户全局权限」**并触动三处既定架构,变更点逐一列明:

1. **ADR-0005 修订**:「单用户+虚拟成员」→「多账号 + admin(workspace 级,兜底超级用户)+ 项目级 editor/viewer」(R7:两级 project admin 提案被否,admin 并入 editor;viewer 扩权=可作业)。核心决策「无 per-project ACL 矩阵」**被部分推翻**:引入角色叉表;但「ACL 管理成本>收益」的拒绝范围收窄为:不做自定义角色、不做操作级细粒度权限(矩阵=角色×固有权责,预置枚举)。current() 收敛点(~20 处)保留不动——ADR 预留的演进埋点兑现。
2. **LeaderGuard 语义变更**(spec 0081「创建即管」):leader 角色并入 editor;25 个 requireLeader 调用点改挂 member_role 查询;「最后 leader 保护」「无 leader 放行(R2)」两条规则**删除**(admin 兜底,私有可见性使无角色项目仅 admin 可达)。R8:守卫三级=requireMember(item 级,池行∪角色行)/requireEditor(管理门)/requireAdmin(兜底,由 requireWorkspaceAdmin 收敛改名)。
3. **词汇表更新**:`Member` 条目的「避免 User」语义保留(账号=Member 凭据,无 User 实体);新增 **admin / editor / viewer** 三个授权术语(R7 改名:原 workspace admin 称 admin;admin 仅 workspace 级、无项目关联;editor/viewer 必关联项目,CHECK 落库)与「黑名单」语义。Member 术语:登录账号=有凭据的 Member(status=active),禁用=disabled;无任何角色行/池行的账号=游客(过渡态,非角色值,R8)。

以上为有意偏离,非顺手改动;ADR-0005 修订随 S1 合并提交。

## 1. 需求与范围

### 1.1 背景

现行为:单真人账号(唯一 password_hash 非空的 member)+ 虚拟成员(不登录,匹配引擎 Capability 载体)。2026-11-11 拍板做多用户;2026-11-28 grill 定设计。

### 1.2 范围

- **账号**:admin 全局建号(必挂 ≥1 项目,C8)、重置密码(任何人)、禁用/复职、删号、授/收 admin;editor 项目内建号(勾 editor);自助改密(踢所有会话);虚拟成员仅 admin 可建(实体保留)。
- **授权**:统一角色叉表(member_role);admin=兜底超级用户(天然全项目全权限,守卫单缝纪律,废止触发条件存档 §2);项目级 editor/viewer;**viewer=可作业**(item 级全操作,无管理权,R7);项目私有可见性;建项目 editor/admin(创建即管),删项目仅 admin。
- **令牌**:JWT 按客户端分型(web/cli),TTL 分键;member 级黑名单(禁用/重置/自助改密立即失效,复职删行);status 生命周期;refresh defer(§4.5)。
- **端**:后端 API+迁移+测试、web(全局成员管理页+项目成员页+登录落地规则+401/403)、CLI 仅传 clientType(不加命令)。

### 1.3 非目标(defer,均有归档)

- superadmin(跨 workspace 管理者,无项目权力)——**模型预留**(member_role 的 admin+NULL 即 workspace admin;superadmin 未来=跨实例角色),实现 defer;
- 单点登录/同类型会话踢旧 → **已立 backlog 票 YPJ-287**(jti 注册表方案存票面);
- 自定义角色/可编辑角色数据表(现预置枚举)、操作级细粒度权限、RBAC 矩阵引擎;
- 自助注册、邮箱验证、SSO/OAuth、密码找回(无邮箱,admin 重置兜底);
- 虚拟成员退役(用户判「真实项目不需要」,实体保留——匹配引擎 ADR-0003 依赖;若退役另立票);
- 多 workspace 租户化;
- refresh(滑动续期/闲置超时)——**defer 定稿**(2026-11-28晚),用户草图存档见 §4.5;
- 邀请自建账号(editor 发邀请→被邀人自助注册,带项目关联)——defer,已立 backlog 票 **YPJ-292**(无邮件通道,editor 项目内建号已覆盖,C6)。

### 1.4 用户故事(User stories,R7/R8 重写)

> 读法:每条故事 = 一个可见行为;验收锚点指向矩阵行(§2)与手工用例(§8)。

| # | 角色 | 用户故事 | 验收锚点 |
|---|---|---|---|
| US-1 | admin | 我要能创建登录账号并挂进项目(≥1),让同事用自己账号登录 | §2 行24;M-166-1 |
| US-2 | admin | 同事忘记密码我能重置(他旧会话当场失效);离职我能禁用,复职即恢复 | §2 行23/27;M-166-4 |
| US-3 | admin | 我能删号(守卫)与授/收 admin(最后 admin 不可收) | §2 行28/29;M-166-6 |
| US-4 | editor | 我能在项目内给同事建号(勾选是否 editor),同事随即能干活 | §2 行22 |
| US-5 | editor | 我能创建项目(建后自动获得该项目 editor 行);删项目是 admin 的事 | §2 行25/26 |
| US-6 | editor | 我对本项目可管理:milestone/release/sprint/设置/池/角色 | §2 行16-21 |
| US-7 | editor | 同事忘密码我可直接重置(限池内);能改池内成员的能力档案 | §2 行5/23 |
| US-8 | viewer | 我是干活的:能建/改 item、迁移状态、评论、记工时、看板列间拖动 | §2 行9-13(R7 扩权) |
| US-9 | viewer | 我建的 item 直接入看板,由 editor 评审归置;我不能拉入拉出看板 | §2 行14/15 |
| US-10 | 无关者 | 项目列表不见无权项目,直访 404/403 | §2 行6-8;M-166-3 |
| US-11 | 被禁用者 | 我被禁用后 web/CLI 全部当场失效,重登被拒;复职恢复 | §4.2/§4.3;M-166-4 |
| US-12 | CLI 用户 | 登录一次 30 天免重登;web 端动作不影响我的 CLI 会话(不互踢) | §4.1;M-166-5 |
| US-13 | 全体登录者 | 我能自助改密(改完全部会话失效需重登) | §2 行2 |
| US-14 | 被撤光角色的账号 | 我暂无项目:登录只见空列表;等 editor 再拉我入项目,或 admin 禁用我 | §2 游客列;C8 |

## 2. 终版权限矩阵(R8 大表定稿,一行一操作)

| # | 操作 | admin | editor | viewer | 游客(过渡态) | 备注(语义/落脚点) |
|---|---|---|---|---|---|---|
| **—— 认证与自助(全员)** | | | | | | |
| 1 | 登录/登出 | ✓ | ✓ | ✓ | ✓ | clientType 分型 web/cli |
| 2 | 自助改密 | ✓ | ✓ | ✓ | ✓ | 黑名单踢所有会话含当前(R7 拍板「踢」) |
| 3a | 查看项目池成员(本项目) | ✓ | ✓ | ✓ | ✗ | 指派/看板选择器(R8:池成员=项目成员可见) |
| 3b | 查看全局成员目录(含虚拟+能力) | ✓ | ✓(加池选人) | ✗ | ✗ | C7;无 public 概念,一切私有 |
| 4 | 编辑自己 capabilities/github-username | ✓ | ✓ | ✓ | ✓ | 多用户后收紧为本人 |
| 5 | 编辑他人 capabilities/github-username | ✓ | ✓ 池内成员(含虚拟) | ✗ | ✗ | C1;匹配引擎供数 |
| **—— 项目可见性** | | | | | | |
| 6 | 项目列表 | ✓ 全量 | 角色行∪池行 | 角色行∪池行 | 空 | 私有可见性 |
| 7 | 直访有行项目 | ✓ | ✓ | ✓ | ✗ | |
| 8 | 直访无行项目 | ✓ 兜底 | ✗ 404/403 | ✗ | ✗ | NotFound 先于 403 |
| **—— 项目内作业** | | | | | | |
| 9 | item 创建 | ✓ 任意去向 | ✓ 任意去向 | ✓ 仅看板去向(R7) | ✗ | viewer 建的由 editor 评审归置 |
| 10 | item 编辑/状态迁移 | ✓ | ✓ | ✓(R7) | ✗ | |
| 11 | item 删除 | ✓ | ✓ | ✗ | ✗ | C4:破坏性 |
| 12 | 评论/工时 | ✓ | ✓ | ✓(R7) | ✗ | |
| 13 | 看板列间移动 | ✓ | ✓ | ✓(R7) | ✗ | |
| 14 | 拉入/拉出看板 | ✓ | ✓ | ✗(R7) | ✗ | board-membership 变更 |
| 15 | backlog/sprint 归置 | ✓ | ✓ | ✗(R7) | ✗ | group-membership 变更 |
| 16 | milestone 管理 | ✓ | ✓ | ✗ | ✗ | S2 补门(原无门) |
| 17 | release 管理 | ✓ | ✓ | ✗ | ✗ | |
| 18 | sprint/group 管理 | ✓ | ✓ | ✗ | ✗ | leader 下放 editor(grill R2) |
| 19 | 项目设置(workflow/item type/github) | ✓ | ✓ | ✗ | ✗ | 门名 leader→editor |
| 20 | 池加/移成员(含虚拟) | ✓ | ✓ | ✗ | ✗ | |
| 21 | 授/收 editor/viewer | ✓ | ✓ | ✗ | ✗ | |
| 22 | 项目内建登录账号(勾「授予 editor」) | ✓ | ✓ | ✗ | ✗ | 勾=入池+editor 行;不勾=仅入池(R7 checkbox) |
| 23 | 重置他人密码 | ✓ 任何人 | ✓ 池内成员(C5) | ✗ | ✗ | 黑名单踢该人全部会话 |
| **—— 全局治理(admin 场景;场景=UI 呈现概念,S3/S4 定,后端无场景)** | | | | | | |
| 24 | 全局建登录账号(必挂 ≥1 项目,C8) | ✓ | ✗ | ✗ | ✗ | 全局成员管理页 |
| 25 | 创建项目 | ✓(建后自动落 (creator, editor) 行) | ✓ 同左 | ✗ | ✗ | C2:游客不可建;创建即管 |
| 26 | 删除项目 | ✓ | ✗ | ✗ | ✗ | C3:破坏性,不随创建权下放 |
| 27 | 禁用/复职成员 | ✓ | ✗ | ✗ | ✗ | 黑名单踢/删行(R7 拍板不下放) |
| 28 | 删号(非终态 assignee/引用计数/自删守卫) | ✓ | ✗ | ✗ | ✗ | |
| 29 | 授/收 admin(最后 admin 保护) | ✓ | ✗ | ✗ | ✗ | |
| 30 | 建虚拟成员(全局) | ✓ | ✗ | ✗ | ✗ | Q19;editor 只能把既有虚拟成员进池 |
| 31 | 属性词表管理 | ✓ | ✗ | ✗ | ✗ | Q15 |

- **admin=兜底超级用户**(R7,用户拍板):天然拥有所有项目的所有权限;兜底判定只存在守卫函数单缝内(`有行 OR admin`),业务代码禁止散落 admin 判断;**废止触发条件**(出现才立票):多团队/外部审计/权限粒度抱怨。bootstrap 必然性:首账号必须全权。
- **游客=过渡态**(R8):member 行在、member_role/project_member 行无;仅行 1/2;来源=撤光角色(被动,项目删除随 FK 级联清空)——C8 全局建号必挂项目使建号不直接产生游客;成员管理页提示 admin 考虑禁用;存在价值=将来被 editor 再拉入 + 历史引用完整性(assignee/评论作者引用 member 行,删号守卫保护)。
- **池行=可见性+item 级门**(R8 厘清):入池未授角色的人可见该项目、可做 item 级操作(=viewer 待遇);管理门仍需 editor 行。
- **登录落地规则**(用户拍板 2026-11-28晚):上次项目(last_project_key,本就 member 级)仍可见→直达;不可见/被驱逐/游客→项目列表页(home);收藏行本就 member 级(findByMemberObjectId)——驱逐后**收藏行保留、列表按可见性过滤**,重新入项自动恢复,无清理逻辑。
- viewer 建 item「必然在看板」机制细节(落哪一列/无看板项目退化行为)→ S2 子文档对 ItemGroup PRD 定。

### 2.3 分支清单与覆盖地图(R8 随大表更新)

> 三向追溯:故事(§1.4)→ 矩阵行(§2)→ 分支落点(下表)→ 子票(§7)→ 手工用例(§8)。落点=代码位置。

**A 认证与令牌(住:AuthService / JwtAuthFilter / JwtService;S1)**

| 分支 | 判定 | 落点 |
|---|---|---|
| 登录:用户名不存在/密码错 | 401 统一文案 | AuthService.login(已有) |
| 登录:账号 disabled | 401「账号已禁用」 | AuthService.login 补 status 检查 |
| bootstrap:已初始化 | 409(advisory 锁防并发双管理员) | AuthService.bootstrap(已有) |
| bootstrap:首账号 | 同时落 (admin, NULL) 行 | AuthService.bootstrap(S1 扩) |
| 请求链:验签失败/exp 过期 | 401 | JwtAuthFilter(已有) |
| 请求链:sub 不存在(删号) | 401 | JwtAuthFilter(新增) |
| 请求链:status=disabled | 401 | JwtAuthFilter(新增) |
| 请求链:黑名单有效行 | 401 | JwtAuthFilter(新增) |
| 自助改密成功 | 黑名单 upsert 踢全部(R7) | MemberService |
| ct 分型:web 8h / cli 30d;存量无 ct 按 web | 配置分键 | JwtService + application.yml |
| refresh | **defer**(端点不建) | §4.5 |

**B 账号管理(住:MemberController / MemberService;S1)**

| 分支 | 判定 | 落点 |
|---|---|---|
| 非 admin 调全局治理端点 | 403 requireAdmin | 各端点 |
| 非 editor 调项目内建号/池内重置 | 403 requireEditor | 项目内端点 |
| 全局建号未挂项目 | 400(C8:必挂≥1) | MemberService |
| 建登录成员:重名 | 409 | MemberService |
| 建成员不给凭据=虚拟(admin only) | Q19 | MemberService |
| 密码 <8 位(建/重置/自助) | 400 | Bean Validation |
| 项目内建号勾 editor | + (editor, project) 行 | MemberService |
| 重置密码:admin 任何人 / editor 池内(池成员校验) | +黑名单 | MemberService(C5) |
| 禁用:黑名单 upsert;复职:删行 | §4.3 | MemberService |
| 最后 admin 禁用自己 | 409(R6 提出,未遭异议,随 R8 落定;异议请在 S1 开工前提) | MemberService |
| 删号:非终态 assignee / 引用计数 / 自删 | 409×3(守卫复用) | MemberService |
| 授/收 admin:最后 admin 不可收;收自己(非最后)放行 | 409 | MemberService |
| 自助改密:old 错 | 401 | MemberService |

**C 项目授权(住:guards + 各业务 service;S2)**

| 分支 | 判定 | 落点 |
|---|---|---|
| admin 判定 | (me, admin, NULL) 行 | requireAdmin(requireWorkspaceAdmin 收敛改名) |
| item 级门 | 池行∪角色行∪admin | requireMember(新) |
| 管理门 | (me, editor, project) 行∪admin | requireEditor(25 调用点平移) |
| 项目列表可见性 | 角色行∪池行∪admin 全量 | ProjectService.list 过滤 |
| 直访无权项目 | 404 先于 403(随现行惯例) | 各 controller 守卫 |
| 成员目录两级:池成员(项目内)/全局目录(editor+/admin) | §2 行 3a/3b | member controller |
| 建项目 editor/admin;创建即管 (creator, editor) 行 | 删项目 admin only(C3) | ProjectService |
| viewer 建 item 带归组 | 403(仅看板去向,R7) | item create |
| 拉入/拉出看板、归组 | requireEditor | board/group-membership |
| 池表去角色化迁移+backfill | leader→(editor, project) | S2 迁移 |
| 虚拟成员:进池不进 role | Q19 | projectmember service |
| 属性词表管理 admin only | Q15 | attribute service |

**D web / CLI(S3/S4)**

| 分支 | 判定 | 落点 |
|---|---|---|
| 401(任意请求) | 清 token 跳登录 | web/lib/api.ts(已有) |
| 403 | 无权页 | api.ts + 403 页(S4) |
| 导航隐藏 | 按角色不渲染入口(admin 场景入口仅 admin) | web(S4) |
| 登录落地 | 上次项目可见→直达;否则/游客→home 空列表(用户拍板) | web(S3) |
| 收藏过滤 | 驱逐后收藏行保留、列表按可见性过滤 | web(S3) |
| 登录 clientType=web;自助改密踢后重登 | 恒传/流程 | web(S3) |
| 全局成员管理页;项目内建号 checkbox | admin/editor 入口 | web(S3) |
| CLI:ct=cli 恒传,无新命令,30d 免重登 | §4.1 | yz CLI(S1) |

**遗漏分支猎场(动工与验收时逐一过,防漏)**

1. `rg -g '*.kt' "requireLeader|WorkspaceGuard" backend/api/src/main/kotlin` → 43 处(8 文件):S2 逐处平移打勾;
2. 全端点清单 `rg "@(Get|Post|Put|Delete)Mapping" backend/api` → 对照 §2 矩阵逐端点标门;无门端点必须有「公开」理由(bootstrap/login);
3. `SecurityConfig.kt` permitAll 清单 → S1 核对(现仅 bootstrap/login);
4. AbstractApiTest cleanDatabase 表清单 → member_blacklist/member_role 按 FK 序加入(backend-testing skill);
5. `backend/script/api.http` → 每子票同步新增端点段(仓规);
6. web 全部 fetch 调用点 → 401/403 统一走 api.ts 拦截,不散落。

## 3. 数据模型与迁移

### 3.1 新列/新表

```sql
-- member 加列(S1)
alter table member add column status varchar(16) not null default 'active';
-- 枚举 = Kotlin enum + VARCHAR + CHECK(仓规)
alter table member add constraint ck_member_status check (status in ('active','disabled'));

-- member 级黑名单(S1):禁用/重置密码入列,自动过期;复职删行
create table member_blacklist (
    id         bigint generated always as identity primary key,
    object_id  uuid        not null,
    member_id  bigint      not null,
    expires_at timestamptz not null,          -- = 入列时刻 + CLI TTL(30d);过期行无害,惰性清理
    created_at timestamptz not null default now(),
    constraint uk_member_blacklist_object_id unique (object_id),
    constraint uk_member_blacklist_member    unique (member_id),   -- 每成员至多一行
    constraint fk_mb_member foreign key (member_id) references member (id) on delete cascade
);

-- 统一角色叉表(S1 建表+S1 落 workspace admin 行;S2 启用 project 级)
create table member_role (
    id         bigint generated always as identity primary key,
    object_id  uuid        not null,
    member_id  bigint      not null,
    role       varchar(16) not null,             -- admin | editor | viewer(CHECK)
    project_id bigint,                            -- NULL = workspace 级(仅 admin 出现)
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uk_member_role_object_id unique (object_id),
    constraint uk_member_role_assignment unique (member_id, role, project_id),
    constraint fk_mr_member foreign key (member_id) references member (id) on delete cascade,
    constraint fk_mr_project foreign key (project_id) references project (id) on delete cascade,
    constraint ck_member_role_scope check (
        (role = 'admin' and project_id is null)
        or (role in ('editor', 'viewer') and project_id is not null)
    )
);
-- 注:UNIQUE 对 NULL project 天然放行多行(member 不同),同 member 的 (admin, NULL) 由应用层保证唯一
-- R8:admin 仅 workspace 级(NULL);editor/viewer 必关联项目——CHECK 落库

-- project_member 去角色化(S2):role 列删除,行保留=池成员资格
alter table project_member drop column role;
```

### 3.2 backfill(迁移内,数据变更按宪法列明)

| 迁移 | 动作 | 量级 |
|---|---|---|
| S1 | bootstrap 账号(password_hash 非空)→ 落 (member, admin, NULL) 行 | 1 行(每环境) |
| S2 | project_member 既有 leader/member 行 → 各落 (member, editor, project) 行;然后删 role 列 | 存量项目×成员 |

**语义变化声明**:S2 上线后,曾「无 leader 放行」的空角色项目 → 仅 admin 可管可达(私有可见性兜底);曾 leader 专属的管理操作(sprint/workflow/github 等)→ 全部 editor 可做(矩阵 §2.2 下放,用户拍板)。

### 3.3 实体规约(仓规)

- 枚举:Kotlin enum(MemberStatus/WorkspaceRole?不引——role 直接 VARCHAR+CHECK+常量,随 ProjectMember.ROLE_* 先例);实体主构造声明 id/objectId/createdAt/updatedAt;对外只暴露公共 ID。
- `member_role` 命名说明:词汇表避免「User」,表名随实体 Member。若与仓内既有命名冲突再议。

## 4. 认证与令牌治理

### 4.1 JWT 分型

- 登录请求加 `clientType`(`web` 缺省 / `cli`;yz CLI 恒传 cli);token 加 `ct` claim。
- TTL 配置分键:`yangzhou.jwt.ttl-hours-web: 8` / `yangzhou.jwt.ttl-hours-cli: 720`(现 ttl-hours 保留为兼容缺省,30d)。
- 存量 token(无 ct)按 web 处理;PM prod 单账号重建后重登,无迁移负担。

### 4.2 JwtAuthFilter 链(每请求 +1~2 次轻查询)

```text
验签+exp(标准)→ member 按 sub 存在(删号即拒)→ status == active(禁用即拒)
→ 黑名单无有效行(expires_at > now 即拒)→ principal=member.objectId
```

黑名单查询与 member 加载合一(单查 member LEFT JOIN 黑名单或两次单行索引查询,实现定)。

login/bootstrap 是公开端点不走此链,但**登录本身需查 status**:disabled 账号登录 → 401「账号已禁用」(分支清单 A)。

### 4.3 黑名单语义(Q13/Q17 用户设计,member 级)

| 事件 | 动作 |
|---|---|
| 禁用成员 | status=disabled + 黑名单 upsert(expires_at=now+CLI TTL 30d) |
| 重置密码 | 黑名单 upsert(同上;旧 token 即刻失效) |
| 复职 | status=active + **删黑名单行**(用户强调) |
| 自助改密 | 黑名单 upsert——踢所有会话含当前,需重登(R7 拍板「踢」,与 admin 重置一致) |
| editor 重置池内成员密码 | 黑名单 upsert(踢该人全部会话;C5,与「editor 建号设初密」对称) |
| 删号 | member 行删除,filter 存在性检查天然拒;黑名单行随 FK 级联清 |
| 自动过期 | 行 expires_at 过后无害(该成员所有旧 token 亦过期);惰性清理(查询时顺带删过期行,无定时任务) |

### 4.4 账号管理 API(S1;形状微调权在 S1 子文档,契约同步 api.http)

| 端点 | 权限 | 语义 |
|---|---|---|
| POST /api/members(扩展 username/password + projectIds) | admin | 全局建号,**必挂 ≥1 项目**(C8,建号即入池);不给凭据=建虚拟(admin only,Q19) |
| POST /api/projects/{key}/members/account(示意) | editor | 项目内建号:入池;勾「授予 editor」=+(editor, project) 行(R7 checkbox) |
| PUT /api/members/{id}/credentials | admin | 重置密码(+黑名单);虚拟成员设凭据=升级 |
| PUT /api/projects/{key}/members/{memberId}/credentials(示意) | editor | 重置池内成员密码(+黑名单)(C5) |
| PUT /api/members/{id}/status {status} | admin | 禁用/复职(禁用+黑名单;复职删行) |
| DELETE /api/members/{id} | admin | 守卫复用:非终态 assignee 409、引用计数 409、自己不可删 |
| PUT /api/members/{id}/admin(示意,原 workspace-role 语义) | admin | 授/收 admin((admin, NULL) 行);最后 admin 不可收/自删不可 |
| PUT /api/members/me/password {old,new} | 登录者 | 自助改密(验 old;≥8 位;踢所有会话,R7 拍板) |
| POST /api/auth/bootstrap | (既有) | 首账号 → 同时落 (admin, NULL) 行 |

密码策略:创建/重置 ≥8 位;登录不校验强度(YPJ-68 兼容)。成员目录两级门(3a/3b)见 §5。

### 4.5 refresh(defer 定稿,2026-11-28晚 用户拍板)

用户终局拍板 **defer**。存档草图供未来立票(以此为准,替代 grill R5-Q25 的「8h+临期续」方案):**闲置超时模型**——TTL 30m,闲置 30m 重登,持续使用滑动续期(silent refresh,黑名单检查同常规请求链)。安全隐患(被盗 token 可自我续期;token 走 Bearer+localStorage 无 CSRF 面;防线=黑名单)未来立票时随威胁模型重估。S3 不含 refresh 定时器;绝对寿命上限并入未来票。

## 5. 授权执行(S2)

- **三级守卫**(R8):requireMember(projectKey)=池行∪角色行(admin 兜底)→ item 级操作门(创建/编辑/状态/评论/工时/看板列间);requireEditor=(me, editor, project) 行(admin 兜底)→ 管理门;requireAdmin(由 requireWorkspaceAdmin 收敛改名)=(me, admin, NULL) 行 → workspace 级治理。
- **admin 兜底单缝纪律**(R7):兜底判定只存在于守卫函数内(有行 OR admin);业务代码禁止散落 admin 判断——矩阵 §2=单一真相源。
- 25 个 requireLeader 调用点语义平移 → requireEditor;R2/最后-leader 保护删除。
- milestone/service 补门(原无门)→ requireEditor。
- 可见性:项目列表 service 层过滤(角色行∪池行∪admin 全量);直访 project 入口 404 先于 403(随现行惯例);成员目录两级门见 §2 行 3a/3b。
- 建项目:editor/admin,创建即管落 (creator, editor, project) 行;删项目 requireAdmin。
- 项目成员管理 API(editor+):池加/移 + 授/收 editor/viewer(member_role);虚拟成员只进池不进 role。
- **viewer 建 item 去向强制**(R7):创建请求携带 backlog/sprint 归属 → 403;入看板直落(board-membership,默认列;无看板项目退化行为 → S2 子文档对 ItemGroup PRD 定);拉入/拉出看板与归置端点挂 requireEditor。

## 6. 兼容与中间态

- **S1 上线后、S2 前**:多账号可登录,授权仍「全局同权」(ADR-0005 现状)——语义自洽,无断裂窗口;**部署建议 S1→S2 同批**(中间态=新账号可见全部项目,PM prod 单账号无感)。
- CLI:无新命令;login 传 ct=cli;30d 免重登语义保持。
- api.http:两子票各自同步新增端点段(仓规:改 REST API 必同步)。

## 7. 子票拆分(R8 细化:7 张垂直切片;已建票进新 sprint V34)

| # | 子票 | 票号 | 内容端到端 | 预估 | 依赖 | 分支名预留 |
|---|---|---|---|---|---|---|
| S1 | 后端·账号基座(status/黑名单/叉表/admin 判定) | YPJ-288 | §3.1 三表迁移+backfill、requireAdmin 守卫、JwtAuthFilter 新链、ct 分型、自助改密 | 1d | 无 | feat/YPJ-288-account-foundation |
| S2 | 后端·账号治理 API | YPJ-293 | §4.4 全部端点、whoami 角色集(前端导航/落地依赖) | 1d | S1 | feat/YPJ-293-account-admin-api |
| S3 | 后端·项目授权矩阵 | YPJ-289 | §3.2 去角色迁移+backfill、三级守卫平移、可见性、建/删项目 | 1.5d | S1 | feat/YPJ-289-project-authz |
| S4 | 后端·viewer 作业语义 | YPJ-294 | item 级门、删除门、viewer 建号去向强制、看板/归组门 | 0.5d | S3 | feat/YPJ-294-viewer-item-semantics |
| S5 | web·认证与会话 | YPJ-295 | clientType、自助改密 UI、登录落地规则、收藏过滤 | 0.5d | S1/S2 | feat/YPJ-295-web-auth-landing |
| S6 | web·全局成员管理页 | YPJ-290 | admin 场景页、全局成员目录、停用候选提示 | 1d | S2 | feat/YPJ-290-web-member-admin |
| S7 | web·项目成员管理与权限 UI | YPJ-291 | 项目内建号 checkbox、池与角色管理、403 页、导航隐藏、空态 | 1d | S2/S3/S4 | feat/YPJ-291-web-project-authz |

- 合计 6.5d;7 张 ≥6 → 用户拍板(2026-11-28 深夜)**建新 sprint V34(planned)专做本功能**;父票 166 随迁;7 票已挂 V34 并按依赖序铺排 schedule(2026-11-29 起)。
- 原 4 票(288/289/290/291)重定 scope+更名;新增 293/294/295。
- **V33(修补与基建)保持 active**,其收口与其余成员去向由用户执行(本票迁出不触发自动迁移)。
- 临时语义:S2 项目内建号暂挂既有 LeaderGuard,S3 平移 requireEditor(中间态无安全洞:leader⊂editor)。
- 子票说明文档约定不变(`docs/spec/0177-s{N}-*.md`,开工时写);defer 存档票 YPJ-292 留 backlog 不进 sprint。

## 8. 手工测试用例(主路径;子票说明文档各自带自己的 M-166-x 明细)

前置:dev 环境;两浏览器上下文+CLI;admin=bootstrap 账号。

| # | 用例 | 预期 |
|---|---|---|
| M-166-1 | admin 建登录账号 → 新用户 web 登录 | 登录成功;初始无任何项目可见 |
| M-166-2 | admin 授 editor → 该用户可见项目、可建 milestone | editor 全权(含建项目/项目内建号);viewer 可改 item/状态/评论/工时/看板列间,管理操作 403(R7) |
| M-166-3 | 非成员直访项目 URL | 403/404;列表不含 |
| M-166-4 | admin 禁用成员 → 该成员已登录的 web/CLI 立即 401 | 黑名单即刻生效;复职删行后可重登 |
| M-166-5 | CLI 与 web 并存 | CLI 30d 不被 web 登录影响(不互踢);TTL 各按配置 |
| M-166-6 | 授/收 admin;最后 admin 保护 | 收最后 admin 被 409 |
| M-166-7 | 存量单账号(bootstrap)升级后 | 原 admin 全功能无感;既有项目行为不变 |

## 9. 决策记录(grill 索引,R1–R8)

- R1:Q1 账号=member 凭据无 User 实体;Q2 被用户扩围为权限矩阵;Q7 JWT 重考虑(离职立即失效+单点)
- R2:Q1 两级 admin(superadmin defer);Q2 role 带 admin 属性;Q3 私有;Q4 矩阵简化 viewer/editor;Q8 CLI/web 不互踢+分型 TTL
- R3:Q9 预置枚举;Q10 leader 并 editor;Q11 建项目资格(后改);Q12 R2 不收紧;Q13 黑名单(用户设计);Q14 分型各 1 会话;Q15 词表 admin;Q16 删项目 admin
- R4:Q17 member 不加列(黑名单演进);Q18 建项目=admin;Q19 虚拟成员仅 admin 可建实体保留;Q20 离职=禁用
- R5:Q24 踢旧 defer 立票(YPJ-287);Q25 web 8h+问滑动续期;Q26 统一叉表采纳;Q27 可见性=有权限才可见(推翻 R2-Q3 的全员可读倾向,回到私有)
- 终版:矩阵 §2、黑名单 §4.3、叉表 §3.1、拆分 §7
- R6(2026-11-28晚,spec 过目轮):refresh=有条件做(「无隐患就做」→ 隐患如实陈述于 §4.5,终局①/②/③待确认);自助改密=做(踢会话与否待拍板,§4.3);spec 增补用户故事 §1.4 + 分支清单与覆盖地图 §2.3(用户要求:文档太精简、分支完整性存疑);新分支两个直接落 spec(登录 disabled→401、最后 admin 禁用自己→409)请复核
- R7(2026-11-28晚,角色模型重构,用户主导):两级 project admin 提案被否——admin 并 editor;workspace admin 改名 **admin**;admin=兜底超级用户(天然全项目全权限;守卫单缝纪律;废止触发条件:多团队/外部审计/粒度抱怨);viewer 扩权=可作业(推翻 R4-Q4 只读语义):item 编辑/状态/评论/工时/看板列间 ✓,拉入拉出/归置/管理 ✗;viewer 建 item 必直入看板,editor 评审归置;自助改密拍板「踢」;refresh defer(30m 闲置超时草图存档 §4.5)。
- R8(2026-11-28晚,确认表 C1-C8 全落定):C1 editor 池内 capabilities ✓;C2 游客不可建项目 ✓;C3 删项目仅 admin ✓;C4 item 删除 editor+/viewer ✗ ✓;C5 editor 重置池内密码 ✓;C6 邀请自建 defer 立票(YPJ-292)✓;C7 全局目录 editor ✓/viewer/游客 ✗ ✓(成员可见性拆两级:池成员=项目成员可见,全局目录=editor+/admin);C8 全局建号必挂≥1 项目+撤光角色提示禁用 ✓;厘清游客=过渡态(存在价值=再拉入+历史引用完整性);池行=可见性+item 级门;登录落地规则(上次项目可见才直达,否则 home)+收藏按可见性过滤(用户拍板)。子票已建:YPJ-288/289/290/291(parent=166,留 backlog 待定 sprint)。

## 10. As-built

(子票各自完成后汇总补)
