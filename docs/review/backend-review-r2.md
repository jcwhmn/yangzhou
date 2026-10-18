# backend 深度 review · 第二轮(严格标准)

- 性质:在第一轮(全局盘点)基础上,**逐文件深读**高密度区——ItemService(501 行全读)、ProjectService、MemberService、AttributeService、WorkflowService、AuthService、NotificationService、SearchService、GithubSync 三件套、MetricsService(时间/数学区)、ItemNumberRepository、ProjectDeletionRepository、全部 33 个迁移的约束面、AbstractApiTest、entities.puml 机械核对。未改任何代码。
- 本轮最重要的产出是三件:**①修正第一轮的两处误判;②挖出一组第一轮看不见的真实缺陷(时间戳治理、时钟纪律、O(全项目) 取数模式、同步事件丢失窗口);③把"信任边界上的系统性空缺"列成需要现在拍板的决策。**

---

## 〇、第一轮结论修正(重要)

| 第一轮说法 | 二轮核实 | 结论 |
|---|---|---|
| "FK 全部无 ON DELETE 行为,级联靠应用层"(Q4,中) | **误判**。当时只精读了 V1 且正则没跨行。实际 V11 起 24 处 `on delete cascade`(comment/notification/time_entry/dependency/checklist/git_ref/status_history/item_group_member_history/project_repo/favorite/project_member/status_transition/team_member 全覆盖),`item.assignee` 更是 `on delete set null`("人走了,事还在") | **撤回**。级联设计完整且与 ProjectService.delete 的清空次序注释吻合 |
| "AbstractApiTest 清理覆盖未核" | 逐表核对:cleanDatabase 的 28 表清单与迁移 28 表**完全一致**,子表在前父表在后 | 覆盖完整,无缺口 |
| "entities.puml 可能过期" | **坐实**:图只画 23/28 实体,缺 Comment、ItemActivity、Favorite、ProjectView、TeamMember(文件头自注"V1+V2+V12",其后 21 个迁移未同步) | 过期确认,按 AGENTS 类图规则该补 |

第一轮其余结论(TODO=0、测试双轨、错误契约、S1 PAT/S2 密钥/S3 500 面等)二轮复核后维持。

## 一、本轮新发现:缺陷(按严重度)

### D1 · Item(及多数实体)的 `updatedAt` 是摆设 —— 数据治理级缺陷,建议现在立规矩

- 全项目**没有 `@EnableJdbcAuditing`**,实体也没有 Spring Data 的 `@LastModifiedDate`;`updatedAt` 完全靠各 service 手工赋值。
- 实测:`ItemService.update()` 的所有 `copy(...)` 保存**没有一个带 `updatedAt`**——item 被改标题/状态/指派/日期后,`updated_at` 仍是创建时刻。全 api 主源码只有 10 处手工 `updatedAt =`,对 28 个实体,覆盖是零散的(GithubConfigService 记得,ItemService 全忘)。
- 后果:任何按 `updated_at` 排序/同步/缓存失效的逻辑(包括未来 CLI/Web 的"最近改动")都会拿到错数据;这也是加乐观锁(@Version)的前置——**两个字段是同一个治理决策,项目还小,现在立 = 一次迁移 + 一处配置;拖到有消费者 = 数据修复**。
- 建议:启用 `@EnableJdbcAuditing` + 统一审计回调;一并决定要不要 @Version。

### D2 · ItemService 时钟纪律未竟(spec 0049 只完成了一半)

- spec 0049"触碰即迁移"只迁移了软删那一处(`clock.instant()` ✅);`ItemService` 仍裸用 `LocalDate.now()` ×3(182/189 行,`overdue`/`dueSoon` 判定)。
- 后果:①测试不可注入"今天",凡含 dueSoon 的列表断言只能碰运气;②跨日边界 flake。Clock 已经注入构造器了,改成 `clock.instant().atZone(...)` 是机械修改。
- 同类:MetricsService 做对了(Clock + 显式 UTC,`utcDate/endOfDayUtc` 命名清晰)——对照之下 ItemService 更像遗漏而非决策。

### D3 · "取整表再过滤"模式 ×3 —— O(全库) 的隐蔽放大器

- `ItemService.update()` 与 `get()`:`projects.findAll().firstOrNull { it.id == item.projectId }`——为了拿项目行,把**所有项目**载入内存。`get()` 更进一步:为组装单个 item,**调 `list(project.key)` 生成整个项目的 DTO 列表再 `firstOrNull`**(顺带把 statuses/requirements/groupMembers 全查一遍)。
- `ProjectService.list()`:`findAll().map{key}.mapNotNull { runCatching { get(it) } }`——每项目一次 statuses 查询的 N+1,且 `runCatching` 把单个项目的组装错误**静默吞掉**(列表里少了谁没人知道)。
- 现在单人量级无感;这是模式问题不是性能问题——`findById(pid)`/`findByProjectId` 都现成。建议:增补 `ProjectRepository.findById` 路径 + `ItemService.get` 走直组装路径(与 `list` 共享私有组装函数),`list()` 的 runCatching 至少记日志。

### D4 · WIP 上限存在并发窗口(check-then-act)

- `ItemService.update()`:`wipCount >= limit` 检查与 `save` 之间无锁;两个并发迁移可同时通过检查,列内超出 wipLimit。
- 已有 `ConcurrentPutHammerApiTest` 文化,补一个"WIP 并发 hammer"测试即可暴露;修法可轻(迁移语句带条件计数)可重(SELECT ... FOR UPDATE)。单人工具优先级低,但既然测试文化在,值得立票。

### D5 · GitHub 同步:自动指派抛错会永久丢失该 PR 的状态事件

- `GithubSyncService.syncRepo`:PR 首见时先 `gitRefs.save(...)`,再作者映射 → `projectMembers.assertAssignable(...)`(不在池内时**抛 ConflictException**)→ `applyEvent`(状态迁移)。抛错点在 gitRef 落库之后、事件应用之前;异常被 `syncAll` 捕获记日志"下轮重试",但**下轮 `existing != null` 直接跳过,事件再也不会补发**。
- 触发条件:PR 作者的 github_username 已映射到某成员、但该成员不在项目指派池内。低概率但静默。修法:assertAssignable 失败走 `onFailure` 跳过指派但继续 applyEvent(现在是 `runCatching{...}.onSuccess{...}`,异常仍外抛)。

### D6 · bootstrap 并发窗口 + 无密码策略

- `members.count() > 0` 检查与 save 之间无锁,两个并发 bootstrap 可造出**两个管理员**(username 唯一约束挡不住两个不同名)。本机部署概率极低,但修法便宜:对 workspace 行做原子占位(insert-if-absent)。
- 密码仅 `@NotBlank`,无最小长度。单人工具可接受,建议至少 8 位,一行校验。

### D7 · 搜索通配符未转义(正确性,非注入)

- `SearchService`:`"%" + query + "%"`,用户输入的 `%`/`\` 原样进 ILIKE——搜"100%"会过度匹配。参数化没有问题(无注入),是语义缺陷。`escape` 子句 + 手工转义即可,或注明已知。

## 二、质量观察(不构成缺陷,记录在案)

| # | 观察 | 建议 |
|---|------|------|
| Q1 | `ItemService` 构造器注入重复:`groups` 与 `groupRepo` 是同一个 ItemGroupRepository,`memberships` 与 `groupMembers` 同为 ItemGroupMemberRepository——四个字段两个类型 | 合并别名,防未来改注入时漏一边 |
| Q2 | `resolveRequirements`:同属性重复出现时 `groupBy + first()` **静默取第一个 minLevel**,调用方传 `[{a,3},{a,1}]` 无声丢 3 | 重复即 400,或取最大(需求语义=最严)——需要拍板 |
| Q3 | 默认 workflow 双源:代码侧 `defaultStatuses/seedDefaultRules`(每项目创建时)与 `V30__seed_default_workflow_rules.sql`(迁移 seed)各自维护一份"默认五列+规则" | 若 V30 只是历史一次性 seed,在两个文件头互相注明真相源是谁 |
| Q4 | `list()` 组装已做批量化 ✅;但 `recycleBin()` 对每行做 `itemRepo.findByObjectId(parent)` 的 N+1(回收站小,可缓) | 顺手改批量 |
| Q5 | Metrics 时间语义 = **显式 UTC 日界**(`utcDate/endOfDayUtc`)。国内用户凌晨 0–8 点的操作会被记到另一天 | 数学正确且自洽;建议在 API 文档注明"metrics 按 UTC 日界" |
| Q6 | Excel 导出用 XSSF 全量 DOM(非 SXSSF)——当前量级合适;量级上万再换流式 | 备案即可 |
| Q7 | `GlobalExceptionHandler` 已覆盖业务族+校验+不可读+完整性冲突;缺 `HttpRequestMethodNotSupportedException`(405)与 catch-all(见第一轮 S3) | 一并补 |

## 三、做得对的(二轮新证据,值得保持的"暗功夫")

1. **ADR-0002 的环校验真的在**:`ensureNoCycle` 沿新父链上行 + 10_000 hop 上限兜底,错误文案清晰。
2. **成员删除是全站删除语义的范本**:拒登录账号 → 拒有进行中 item → 引用计数(评论/动态/状态历史/工时/视图/通知)逐项报数 → 清 capability → 删。`assignee` 由 `ON DELETE SET NULL` 兜底。属性删除 `catch DataIntegrityViolationException → 409`,分类有子条目先拒。
3. **key 白名单一步到位**:`^[A-Z][A-Z0-9]{1,9}$` 让 item 编号、分支名解析(`ITEM_KEY` 正则)、Excel `Content-Disposition` 文件名全部天然安全。
4. **通知收件人推导集中**(assignee+创建者+评论者,去重、排除操作者),"触发方只管喊"的注释与实践一致。
5. **原子取号**(`UPDATE ... RETURNING` 行锁)、**工作流空表=自由迁移**(DOMAIN 规则 3)、**metrics 的 Clock/UTC 纪律**、**AbstractApiTest 28 表清理**、**domain 引擎与 DOMAIN.md 五形态/聚合规则逐条吻合且有 4 个单测**——全部二轮复核通过。

## 四、需要"现在拍板"的决策清单(项目还小的窗口期)

1. **updatedAt 治理 + 是否上 @Version**(D1):一次迁移解决两件事。建议:上 auditing;@Version 暂不上、写 ADR 记"并发策略=行锁+终写胜出+并发测试兜底"。
2. **授权模型**(第一轮 B1):单用户是设计就写进 ADR;未来多用户,现在就把 `memberService.current()` 埋进所有写路径(埋点已在,成本只会涨)。
3. **时钟纪律补完**(D2):机械修改,顺手清债,让 spec 0049 真正关票。
4. **S1 PAT 加密 / S2 默认密钥 fail-fast**(第一轮):仍是安全侧最优先。

## 五、覆盖声明(本轮没看的)

- `cli/ExportImport.kt` 与 `view/ViewService`、`standup`、`dashboard`、`candidate` 四个切片只过了 grep 没有逐行读;
- 33 个迁移里 V2–V33 只做了约束面全量扫描与 V1/V5/V12 抽读,未逐行审业务 SQL;
- `nightly/backend.yml` 的 CI 全文未复核;
- 集成测试逐用例质量(抽读了 AbstractApiTest 与并发 hammer 的存在性,未评审断言强度)。
- 如需第三轮,建议优先补:ExportImport(数据进出是最容易丢数据的路径)与各切片集成测试断言强度。

---

### 总结

第二轮把"看起来很干净"推进到"逐行对过账":级联、环校验、删除语义、编号原子性、通知推导、搜索参数化全部属实——这个后端的**下限非常高**。真正欠的不是某行代码,而是**三个治理决策**(updatedAt/@Version、授权模型、时钟纪律)和六个具体小缺陷(D3–D7+Q2),全部在"一两天内清完"的量级。项目还小,现在是立规矩最便宜的时刻。

*报告完。*
