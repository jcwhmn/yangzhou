# spec 0177 — YPJ-166 多用户登录与授权矩阵(父票)

> 来源:YPJ-166(2026-11-11 立票);2026-11-28 用户拍板「仔细分析设计,做好 grill」。经 **6 轮 grill**(本文件「决策记录」节存全量拍板)达成设计共识。本文件=父票主文档:需求+设计+矩阵+迁移+子票索引;**子票实现时另写简短说明文档引用本文件**(用户 2026-11-28 拍板,替代逐子票完整 spec)。

## ⚠️ 架构破坏显要标注(必读)

本票**修订 ADR-0005「单用户全局权限」**并触动三处既定架构,变更点逐一列明:

1. **ADR-0005 修订**:「单用户+虚拟成员」→「多账号同权 + workspace admin + 项目级 editor/viewer」。核心决策「无 per-project ACL 矩阵」**被部分推翻**:引入角色叉表;但「ACL 管理成本>收益」的拒绝范围收窄为:不做自定义角色、不做操作级细粒度权限(矩阵=角色×固有权责,预置枚举)。current() 收敛点(~20 处)保留不动——ADR 预留的演进埋点兑现。
2. **LeaderGuard 语义变更**(spec 0081「创建即管」):leader 角色并入 editor;25 个 requireLeader 调用点改挂 member_role editor 查询;「最后 leader 保护」「无 leader 放行(R2)」两条规则**删除**(admin 越权兜底,私有可见性使空角色项目仅 admin 可达)。
3. **词汇表更新**:`Member` 条目的「避免 User」语义保留(账号=Member 凭据,无 User 实体);新增 **workspace admin / project editor / project viewer** 三个授权术语与「黑名单」语义。Member 术语:登录账号=有凭据的 Member(status=active),禁用=disabled。

以上为有意偏离,非顺手改动;ADR-0005 修订随 S1 合并提交。

## 1. 需求与范围

### 1.1 背景

现行为:单真人账号(唯一 password_hash 非空的 member)+ 虚拟成员(不登录,匹配引擎 Capability 载体)。2026-11-11 拍板做多用户;2026-11-28 grill 定设计。

### 1.2 范围

- **账号**:admin 创建登录账号(用户名/密码/显示名)、重置密码、禁用/复职、删号、授/收 admin;自助改密;虚拟成员仅 admin 可建(实体保留)。
- **授权**:统一角色叉表(member_role);项目私有可见性;editor/viewer 项目角色;workspace admin 越权;建/删项目仅 admin。
- **令牌**:JWT 按客户端分型(web/cli),TTL 分键;member 级黑名单(禁用/重置立即失效,自动过期,复职删行);status 生命周期。
- **端**:后端 API+迁移+测试、web UI(账号管理+项目成员管理)、CLI 仅传 clientType(不加命令)。

### 1.3 非目标(defer,均有归档)

- superadmin(跨 workspace 管理者,无项目权力)——**模型预留**(member_role 的 admin+NULL 即 workspace admin;superadmin 未来=跨实例角色),实现 defer;
- 单点登录/同类型会话踢旧 → **已立 backlog 票 YPJ-287**(jti 注册表方案存票面);
- 自定义角色/可编辑角色数据表(现预置枚举)、操作级细粒度权限、RBAC 矩阵引擎;
- 自助注册、邮箱验证、SSO/OAuth、密码找回(无邮箱,admin 重置兜底);
- 虚拟成员退役(用户判「真实项目不需要」,实体保留——匹配引擎 ADR-0003 依赖;若退役另立票);
- 多 workspace 租户化;
- refresh 滑动续期的绝对寿命上限(见 §4.5 开放决策)。

## 2. 终版权限矩阵(grill 定稿)

### 2.1 workspace 级

| 操作 | workspace admin | 普通 member(无行) | 现状→变化 |
|---|---|---|---|
| 账号管理:建登录账号/重置密码/禁用/复职/删号/授收 admin | ✓ | ✗ | 新增 |
| 建项目 | ✓ | ✗ | 收权(原唯一账号可,实为 admin) |
| 删项目 | ✓ | ✗ | 显式化(原 leader 门) |
| 建虚拟成员 | ✓ | ✗ | 收紧(Q19) |
| 属性词表管理(建/改属性与分类) | ✓ | ✗ | 收紧(Q15) |
| 成员目录查看(含虚拟) | ✓ | ✓ | 不变 |
| 改自己的密码 | ✓ | ✓ | 新增(自助,验 old) |
| 登录/登出 | ✓ | ✓ | 不变 |

### 2.2 project 级(editor / viewer / 无关者)

| 操作 | editor | viewer | workspace admin | 现状→变化 |
|---|---|---|---|---|
| 项目可见(列表+直访) | ✓ | ✓ | ✓(全项目) | **收紧:无行=403 且列表不回** |
| item 全 CRUD/状态迁移/评论/工时/看板 | ✓ | 只读 | ✓ | 不变(editor 原本开放,viewer 收) |
| milestone/release/group/sprint 管理 | ✓ | ✗ | ✓ | **milestone 补门**(原无门);group/sprint 从 leader 下放 |
| 项目设置/workflow/item type/github 配置 | ✓ | ✗ | ✓ | 门名 leader→editor |
| 项目成员管理:池加/移、授 editor/viewer | ✓ | ✗ | ✓ | 不变(门名改) |
| 删项目 | ✗ | ✗ | ✓ | 收权(Q16) |

- **创建即管**:admin 建项目 → 自动落 (creator, editor, project) 行;**建者不特殊**(editor 间平等,admin 兜底)。
- 指派/候选池:**project_member 纯池表语义不变**(含虚拟成员);editor 行≠池成员(授权与池分离,见 §3)。assignable 守卫(assertAssignable)继续按池表。

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
    constraint fk_mr_project foreign key (project_id) references project (id) on delete cascade
);
-- 注:UNIQUE 对 NULL project 天然放行多行(member 不同),同 member 的 (admin, NULL) 由应用层保证唯一

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

### 4.3 黑名单语义(Q13/Q17 用户设计,member 级)

| 事件 | 动作 |
|---|---|
| 禁用成员 | status=disabled + 黑名单 upsert(expires_at=now+CLI TTL 30d) |
| 重置密码 | 黑名单 upsert(同上;旧 token 即刻失效) |
| 复职 | status=active + **删黑名单行**(用户强调) |
| 删号 | member 行删除,filter 存在性检查天然拒;黑名单行随 FK 级联清 |
| 自动过期 | 行 expires_at 过后无害(该成员所有旧 token 亦过期);惰性清理(查询时顺带删过期行,无定时任务) |

### 4.4 账号管理 API(S1;形状可微调,契约入 api.http)

| 端点 | 权限 | 语义 |
|---|---|---|
| POST /api/members(扩展可选 username/password) | admin | 一步建登录成员;不给凭据=建虚拟(admin only,Q19) |
| PUT /api/members/{id}/credentials | admin | 重置密码(+黑名单);虚拟成员设凭据=升级 |
| PUT /api/members/{id}/status {status} | admin | 禁用/复职(禁用+黑名单;复职删行) |
| DELETE /api/members/{id} | admin | 守卫复用:非终态 assignee 409、引用计数 409;自己不可删 |
| PUT /api/members/{id}/workspace-role {admin\|member→撤销} | admin | 授/收 workspace admin(member_role admin NULL 行);**最后 admin 不可收/自删不可** |
| PUT /api/members/me/password {old,new} | 登录者 | 自助改密(验 old;≥8 位策略) |
| POST /api/auth/bootstrap | (既有) | 首账号 → 同时落 (admin, NULL) 行 |

密码策略:创建/重置 ≥8 位;登录不校验强度(YPJ-68 兼容)。

### 4.5 refresh(开放决策,默认做)

silent refresh:web 端临期(剩余<15min)调 `POST /api/auth/refresh`(持有效 token 换新 8h 票,黑名单检查同常规);价值=活跃用户无感,风险=被盗 token 可自我续期(内部工具威胁模型下可接受,防线=黑名单)。**待用户在 spec 过目时确认:本票做(默认)/ 立票 defer。**

## 5. 授权执行(S2)

- **LeaderGuard → EditorGuard**(或改造):`requireEditor(projectKey)` = member_role 有 (me, editor, project) 行 或 (me, admin, NULL) 行;25 个调用点语义平移;R2/最后-leader 保护删除。
- milestone/service 补门(原无门)→ requireEditor。
- 可见性:项目列表 service 层过滤(有 role 行的 project 集合 ∪ admin 全量);直访 project 入口 404/403(NotFound 先于 403,随 LeaderGuard 现行惯例)。
- 建项目/删项目:requireWorkspaceAdmin(黑名单…否——(admin, NULL) 行判定)。
- 项目成员管理 API(editor/admin):池加/移(project_member)+ 授/收 editor/viewer(member_role project 行);虚拟成员只进池不进 role。

## 6. 兼容与中间态

- **S1 上线后、S2 前**:多账号可登录,授权仍「全局同权」(ADR-0005 现状)——语义自洽,无断裂窗口;**部署建议 S1→S2 同批**(中间态=新账号可见全部项目,PM prod 单账号无感)。
- CLI:无新命令;login 传 ct=cli;30d 免重登语义保持。
- api.http:两子票各自同步新增端点段(仓规:改 REST API 必同步)。

## 7. 子票拆分(2026-11-28 用户授权 agent 分析决定,建票待用户对拆分点头)

| # | 子票 | 内容端到端 | 预估 | 依赖 | 分支名预留 |
|---|---|---|---|---|---|
| S1 | 后端·多账号与令牌治理 | §3.1 member 列+黑名单+叉表迁移、§4 全部(含 bootstrap admin 行)、api.http、单测/integrationTest | 1.5d | 无 | feat/YPJ-{n}-accounts-tokens |
| S2 | 后端·项目授权矩阵 | §3.2 池表去角色迁移、§5 全部、api.http、测试 | 1.5d | S1 | feat/YPJ-{n}-project-authz |
| S3 | web·账号与成员管理 UI | 登录 clientType、401 行为、whoami、成员页账号管理 UI、refresh(若做) | 1d | S1 | feat/YPJ-{n}-web-accounts |
| S4 | web·项目权限 UI | 项目成员管理 UI(池+角色)、403 页面、导航隐藏 | 1d | S2 | feat/YPJ-{n}-web-project-authz |

- 父票 166 不直接产码;随子票全 Done 而 Done。
- **子票说明文档约定**(用户拍板):子票开工时各写简短说明(做什么/怎么做/引用本文件节号),落 `docs/spec/0177-s{N}-YPJ-{n}-{slug}.md` 一页以内;不重复主文档。
- 子票建票:yz 建(带预估,挂 V33 sprint,parent=166)——**待用户对拆分点头后执行**。

## 8. 手工测试用例(主路径;子票说明文档各自带自己的 M-166-x 明细)

前置:dev 环境;两浏览器上下文+CLI;admin=bootstrap 账号。

| # | 用例 | 预期 |
|---|---|---|
| M-166-1 | admin 建登录账号 → 新用户 web 登录 | 登录成功;初始无任何项目可见 |
| M-166-2 | admin 授 editor → 该用户可见项目、可建 milestone | editor 全权;viewer 只读 |
| M-166-3 | 非成员直访项目 URL | 403/404;列表不含 |
| M-166-4 | admin 禁用成员 → 该成员已登录的 web/CLI 立即 401 | 黑名单即刻生效;复职删行后可重登 |
| M-166-5 | CLI 与 web 并存 | CLI 30d 不被 web 登录影响(不互踢);TTL 各按配置 |
| M-166-6 | 授/收 admin;最后 admin 保护 | 收最后 admin 被 409 |
| M-166-7 | 存量单账号(bootstrap)升级后 | 原 admin 全功能无感;既有项目行为不变 |

## 9. 决策记录(六轮 grill 索引)

- R1:Q1 账号=member 凭据无 User 实体;Q2 被用户扩围为权限矩阵;Q7 JWT 重考虑(离职立即失效+单点)
- R2:Q1 两级 admin(superadmin defer);Q2 role 带 admin 属性;Q3 私有;Q4 矩阵简化 viewer/editor;Q8 CLI/web 不互踢+分型 TTL
- R3:Q9 预置枚举;Q10 leader 并 editor;Q11 建项目资格(后改);Q12 R2 不收紧;Q13 黑名单(用户设计);Q14 分型各 1 会话;Q15 词表 admin;Q16 删项目 admin
- R4:Q17 member 不加列(黑名单演进);Q18 建项目=admin;Q19 虚拟成员仅 admin 可建实体保留;Q20 离职=禁用
- R5:Q24 踢旧 defer 立票(YPJ-287);Q25 web 8h+问滑动续期;Q26 统一叉表采纳;Q27 可见性=有权限才可见(推翻 R2-Q3 的全员可读倾向,回到私有)
- 终版:矩阵 §2、黑名单 §4.3、叉表 §3.1、拆分 §7

## 10. As-built

(子票各自完成后汇总补)
