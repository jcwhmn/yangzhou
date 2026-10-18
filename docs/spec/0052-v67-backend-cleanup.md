# spec 0052 — V67 后端 review 小缺陷清扫:r2 D3/D5/D6/D7 + Q1/Q2/Q4 + puml 补图

> 票:YPJ-67。分支:`chore/YPJ-67-backend-cleanup`。机械修复打包票,单 commit 粒度按小节。

## 1. 需求

backend review r2 的机械缺陷清扫,不引新能力、不改契约(唯一例外:Q2 重复需求从静默取第一个改为 **400 显式拒绝**——用户 2026-10-18 已拍板)。

范围:D3(取整表过滤 ×3)、D5(同步指派失败丢事件)、D6(bootstrap 原子化+密码≥8)、D7(搜索通配符转义)、Q1(注入别名合并)、Q4(recycleBin 批量化)、entities.puml 补 5 实体、.gitignore 核查。

非目标:D4(WIP 并发窗口——用户拍板只立票不修)、Q3/Q5/Q6/Q7(备案/已在 0051 做)、任何重构性整理。

## 2. 详细设计

### 2.1 D3:取整表再过滤 ×3

- `ItemService` 增私有 `projectOf(projectId: Long): Project = projects.findById(projectId).orElseThrow { NotFoundException("项目不存在") }`;替换 L205 / L248 / L333 三处 `projects.findAll().firstOrNull { it.id == item.projectId }`(CrudRepository 自带 findById,不加 repo 方法)
- **`get()` 直组装**:抽 `list()` 的批量组装段为私有 `assemble(project, items): List<ItemDto>`;`get()` 改 `assemble(project, listOf(item)).first()`——statuses/requirements/memberships 只按单 item 所需取,不再全项目组装。`list()` 行为不变(回归靠现有 item 集成测试)
- `ProjectService.list()`:注入 SLF4J logger,`runCatching` 补 `.onFailure { log.warn("项目组装失败 key={}", key, it) }`;N+1 本票不修(组装逻辑即将被 get() 复用的问题先解决,列表批量化另票)

### 2.2 D5:同步自动指派失败不丢事件

`GithubSyncService` L95:`runCatching { projectMembers.assertAssignable(...) }` 补 `.onFailure { log.warn(...) }`(类内加 logger)。修后指派失败仅跳过指派,`applyEvent` 照常执行——事件不再永久丢失。

### 2.3 D6:bootstrap 原子化 + 密码 ≥8

- `AuthService.bootstrap`:`members.count() > 0` 检查前对 workspace 行取锁——`WorkspaceRepository` 增 `@Query("select w from workspace ... for update")`(repositories.kt 唯一授权扩展点;workspace 行在迁移期已 seed,行存在)。双请求在锁上串行,后到者看到 count>0 → 409
- `BootstrapRequest.password`:`@field:NotBlank` → `@field:Size(min = 8)`,消息"密码至少 8 位"
- 测试:集成负例——密码 7 位 400;二次 bootstrap 409(现有覆盖则不重复)

### 2.4 D7:搜索通配符转义

- `SearchService`:查询入参 `q` 转义 `\` → `\\`、`%` → `\%`、`_` → `\_` 后再包 `%...%`
- `repositories.kt` L353:三处 `ILIKE :q` 补 `ESCAPE '\'`
- 测试:集成——建 item 标题含 `100%`,搜 `100%` 恰命中该 item 而非全量;搜 `_` 不全量命中

### 2.5 Q1:注入别名合并

`ItemService` L52-53 删 `groupRepo`/`groupMembers`,保留 `groups`/`memberships`;L162/161 等引用点改名。纯机械,零行为变更。

### 2.6 Q2:重复需求同属性 → 400

`resolveRequirements`(L459):`groupBy { it.attribute }` 后 `size > 1` 的组 → `BadRequestException("需求属性重复:属性 $attr 出现多次")`。请求契约变更(原先静默取第一个),用户已拍板。测试:集成负例——create item 传同属性两条 → 400 VALIDATION。

### 2.7 Q4:recycleBin 批量化

`ItemRepository`(repositories.kt)增 `findByObjectIdIn(objectIds: Collection<UUID>): List<Item>`(派生查询即可);`recycleBin()` L396 的逐行 `findByObjectId` 改一次批量 + map。

### 2.8 entities.puml 补 5 实体

补 Comment、ItemActivity、Favorite、ProjectView、TeamMember(只画领域字段,parentId 反转规则/`hide empty members` 沿既有格式);渲染核对(VSCode PlantUML 插件)。

### 2.9 .gitignore 核查

`out/`、`bin/` 模式已覆盖 `persistence/out/`、`api/bin/`(`git check-ignore` 验证过)——**无需改动**,As-built 记录结论。

### 否决备选

- D3 的 ProjectService.list() 批量化:牵动组装逻辑复用,与本票机械清扫定位不符,另票
- D6 用唯一约束占位:workspace 表无业务唯一键,加列违反最小改动
- Q2 取最大 minLevel:用户拍板显式拒绝(语义上调用方写错就该报错)

## 3. 手工测试用例(用户 build/debug)

1. 搜索框输入 `100%`(某 item 标题含 `100%`)→ 恰命中 1 条,不是全列表
2. create item 传同属性两条需求(curl)→ 400;UI 正常路径不受影响
3. 7 位密码 bootstrap(需空库)→ 400"密码至少 8 位";正常库跑不到 bootstrap,可跳过
4. GITHub 栏/回收站/列表页过一遍确认无回归
5. `gradle build --no-daemon` + `gradle integrationTest` 绿(agent 自跑)

## 4. As-built(2026-10-18)

实现与 spec 的偏离/要点:

1. **D3**:list()/get() 重构为 `buildCtx` + 私有 `ItemCtx`(statuses/blockedIds/attrs/members/reqs/sprint/release 一次装配,`toDto` 共享);软删 item 的「[已删除]」前缀移入 `toDto`(get 单 item 路径保留该语义)。
2. **D6 锁的实现**:workspace 行 FOR UPDATE 改为 **`pg_advisory_xact_lock`(advisory 事务锁)**——workspace 行未必存在(bootstrap 前),行锁无锁可取;advisory 锁经 repositories.kt 授权扩展点(`lockBootstrap(): Int?`,SQL 包一层 `select 0 where (...) is null` 使返回行可映射)。**副作用**:AbstractApiTest 及各测试种子密码 `secret`(6 位)不满足新策略,统一改 `secret123`。
3. **D6 补充拆分**:login 复用 BootstrapRequest 导致存量短密码登录也 400——新增独立 `LoginRequest`(仅 NotBlank),密码长度策略只作用于 bootstrap。
4. **D7**:escape 字符用反斜杠(`ESCAPE ''`);测试踩坑:RestTestClient `.uri(String)` 会对已编码 URL 二次编码,测试改用 URI builder——服务端代码本身一次通过。
5. **Q1**:构造器只留 `groups`/`memberships`;list() 内原有同名局部变量遮蔽,随重构消除(buildCtx 内局部 `ms`)。
6. **Q2**:重复需求抛 `BadRequestException` → 错误体 code=BAD_REQUEST(非 bean validation 的 VALIDATION),测试按实际断言。
7. .gitignore:已覆盖(`out/`、`bin/`),未改。

新增测试:ItemDuplicateRequirementApiTest(1)、SearchWildcardApiTest(2)、BootstrapPasswordApiTest(1)、SearchLikePatternUnitTest(1)、ItemDueSignalsUnitTest 等(0051 已列)。`gradle test integrationTest` 173 用例全绿;`gradle build --no-daemon` 绿。entities.puml 补 Comment/ItemActivity/Favorite/ProjectView/TeamMember 五类块与 Member 1-* Comment/Favorite 关系;渲染请用 VSCode PlantUML 插件预览核对。
