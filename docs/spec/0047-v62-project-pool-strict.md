# 0047 — V62 / YPJ-62:项目成员池语义严格化(空池=无人可指派/无候选 + 创建者自动入池)

> 需求源:2026-10-17 grill 6+4 问全拍板(定案见本节,勿重问)。现状:池语义是「表无行 = 全 workspace 放行」(V3.5-B 存量兼容豁免),YPJ-59 清库后池已回填,豁免失去存在理由;且建项目不写池行,新项目指派前有一段「谁都能指」的窗口。

## 一、需求

### 背景
`ProjectMemberService.restrictedPool` 现语义:`project_member` 无行返回 null → 指派与候选**全放行**。该豁免是 V3.5-B 的存量兼容设计;如今所有真实项目均已配置池(8081 YPJ 9 行),「空=放行」反而成为隐患:新建项目忘记配池 = 指派无边界,且与「池=合法指派范围」的领域直觉相反。

### 范围(用户定案四规则)
1. **指派只限池内,空池=无人可指派**:拆掉「空池=全放行」豁免。`restrictedPool` 恒返回集合(可能为空);空池时任何指派 409、候选列表为空。CandidateService 跟随。
2. **创建者自动入池**:`ProjectService.create` 给当前登录用户插一行 `project_member`(与建项目同事务)。**不加 created_by 列**——审计字段归 YPJ-61。
3. **看板显示所有 item 的 assignee,无论是否池内**:现状已满足(assignee 显示不查池),**零改动**,spec 固化为验收断言。
4. **池成员自由增删、不看 assignee**:现状已满足(`remove` 无守卫),**零改动**,同样只写验收断言。

### 非目标
- 不加 created_by/审计字段(YPJ-61)。
- 不做「移除池成员时校验/清理其持有的 assignee」(规则 4 拍板:自由增删)。
- 不动 Web 池管理 UI(仅语义变化,UI 行为随之自然收紧)。
- 不做存量数据迁移(见下)。

### 存量与迁移
- **无需迁移**:8081 default 库 YPJ 池已回填 9 行(YPJ-59 收尾完成);8080 test 库 E2E 每次重建;集成测试库每次清库重建。
- 语义切换对存量用户的影响:今后**新建项目**若不加池行,指派一律 409——这是拍板要的行为;创建者自动入池保证「自己给自己指派」永远可行。

## 二、详细设计(代码级修改计划)

### 1. ProjectMemberService — 严格语义(核心,~6 行)

`backend/api/src/main/kotlin/yangzhou/api/projectmember/ProjectMemberService.kt`

```kotlin
// 现:
fun restrictedPool(projectId: Long): Set<Long>? =
    projectMembers.findByProjectId(projectId).takeIf { it.isNotEmpty() }?.mapTo(HashSet()) { it.memberId }

fun assertAssignable(projectId: Long, memberId: Long) {
    val pool = restrictedPool(projectId)
    if (pool != null && memberId !in pool) throw ConflictException("该成员不在项目成员中")
}

// 改:
/** 池规则共用点(0047 严格化):恒返回池内成员 id;空池 = 无人可指派/无候选。 */
fun restrictedPool(projectId: Long): Set<Long> =
    projectMembers.findByProjectId(projectId).mapTo(HashSet()) { it.memberId }

fun assertAssignable(projectId: Long, memberId: Long) {
    if (memberId !in restrictedPool(projectId)) throw ConflictException("该成员不在项目成员中")
}
```

- 409 消息沿用「该成员不在项目成员中」(空池时对任何成员恒真,无需新文案)。
- 类 KDoc 与 `ItemService.assign` 注释中「表无行 = 全放行」「未配置池全放行」字样同步删除。
- **否决**:区分「空池」专用 409 文案——两态同因(成员不在池),多一条文案多一处维护。

### 2. CandidateService — 池过滤恒生效(1 行)

`backend/api/src/main/kotlin/yangzhou/api/candidate/CandidateService.kt:51`

```kotlin
// 现:
val pool = projectMembers.restrictedPool(item.projectId)
val scopedMembers = if (pool == null) workspaceMembers else workspaceMembers.filter { it.id in pool }
// 改:
val scopedMembers = workspaceMembers.filter { it.id in projectMembers.restrictedPool(item.projectId) }
```

空池 → `scopedMembers` 空 → 引擎空输入 → 候选列表空(200 空数组,非报错)。

### 3. ProjectService.create — 创建者自动入池(2 行 + 构造器注入)

`backend/api/src/main/kotlin/yangzhou/api/project/ProjectService.kt`

- 构造器追加 `memberService: MemberService`、`projectMembers: ProjectMemberRepository`(均不依赖 ProjectService,无环)。
- `create` 末尾(`seedDefaultRules` 后、`get(key)` 前)追加:

```kotlin
// 0047:创建者自动入池(池=合法指派范围,保证创建者至少能给自己指派)
projectMembers.save(ProjectMember(projectId = project.id!!, memberId = memberService.current().id!!))
```

- 同一 `@Transactional`,插池失败则建项目整体回滚。
- 入口覆盖:REST `POST /api/projects` 唯一入口,CLI `projects create` / E2E fixture / 集成测试 `createProject` 助手全走它 → 三端一致。
- **否决**:在 Controller 层插池——泄漏 service 职责;在 ProjectMemberService 加 `addForCreator`——多一个只有一处调用的方法。

### 4. 测试波及与修补

- **不破**(创建者=me 自动入池):所有指派 `meId` 的用例;E2E helper(`web/e2e/support/helpers.ts` 只指 me);`GithubSyncService` 的 assertAssignable 本就 `runCatching` 包裹(作者不入池则静默跳过,断言不受影响);取消指派(null)不走池检查。
- **会红**(指派虚拟成员 → 409):已核实 `ActivityApiTest`(xiaoli)、`NotificationApiTest`(xiaoLi)、`CandidatesApiTest`;`DashboardApiTest`/`ItemsApiTest`/`MembersApiTest`/`ItemDateApiTest` 等同类模式逐个跑红确认。精确名单以 `gradle integrationTest` 跑红为准,**修法统一**:受影响用例在 createProject 后、指派前调 `POST /api/projects/{key}/members` 加池。
- AbstractApiTest 加一个小助手(免得每处手写 PUT/POST 样板):

```kotlin
protected fun addPoolMember(authed: RestTestClient, projectKey: String, memberId: String): JsonNode
```

- 新增断言用例(放 `ProjectMembersApiTest`):
  1. 新建项目 → `GET members` 含创建者;
  2. 空池项目指派任一成员 → 409;
  3. 空池项目候选列表 → 200 空数组;
  4. 创建者可直接被指派(不预加池)。
- api.http:删除链负例区已有加池惯例,补「空池指派 409」负例一条。

### 5. 不改的东西(自查)
- `ProjectMemberController` 三端点形状不变。
- 看板/表格 assignee 渲染零改动(规则 3 现状满足)。
- `ProjectMemberService.remove` 不加守卫(规则 4 现状满足)。

## 三、手工测试用例(用户 build/debug 阶段执行)

前提:8081 default 实例起好,`yz` session 指向 8081。

1. **创建者自动入池**:`yz projects create POOLT 建票` → `yz projects`/Web 设置页确认 POOLT 池内=我(1 行)。
2. **自指派畅通**:`yz items create POOLT 自指派` → Web 把该票指给自己 → 成功。
3. **空池严格 409**:新建另一项目 `POOLX` → Web 上直接指派给「小王」→ 409「该成员不在项目成员中」。
4. **入池后放行**:把小王加入 POOLX 池 → 再指派 → 成功;候选页只出小王+我(不出池外成员)。
5. **空池候选为空**:POOLX 未加人时看某票候选 → 空列表(无报错)。
6. **看板不受池外 assignee 影响**(规则 3 断言):给 POOLT 某票指派小王后把小王移出池(规则 4 断言:移除不被拦截)→ 看板/表格 chip 仍显示小王。
7. **回归**:YPJ 项目日常指派/候选行为不变(池已有 9 行)。

## 验收清单(票面)

- [ ] 空池项目指派任何成员 409(不再全放行)
- [ ] 空池项目候选列表为 200 空数组
- [ ] 建项目后创建者自动在池,可直接被指派
- [ ] 加池后:池内可指派/进候选,池外 409(现状语义保持)
- [ ] 看板/表格正常显示池外 assignee 的 item(零改动断言)
- [ ] 池成员移除不被 assignee 持有拦截(零改动断言)
- [ ] 集成测试全绿(受影响用例补加池)、`gradle build` 绿、e2e 全量绿
- [ ] api.http 补空池 409 负例;spec 0047 补 As-built

## 四、As-built(2026-10-18 实施)

按 §二 实现,三处主改动与设计一致。偏离/补充:

1. **AbstractApiTest 助手 ×2**(设计只列 addPoolMember):补 `meMemberId(authed)`(GET /api/members 取非 virtual 者)——多处断言/入池都需要创建者 id
2. **「空池」语义在 API 入口已不可达**(创建者自动入池),验收用例拆两层:
   - `仅创建者在池 - 池外指派409 - 候选仅我`(API 可达的最严态)
   - `真·空池(jdbc 删行)- 指派409 - 候选空数组`(字面验收,jdbc 绕过 API)
3. **受影响测试修法**:ProjectMembers(池行数 +创建者/顺序断言后移)/ Activity / Candidates(seed 显式入池)/ AuthorBackfill / Members / Notification / Standup 共 7 类 13 用例,统一 `addPoolMember` 补池;ProjectMembersApiTest 重写「存量零配置回归」用例为严格语义
4. 集成全绿(2m57s);e2e 未跑(票面验收含全量 e2e,留用户 build/debug 阶段)
