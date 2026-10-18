# backend 全面 review 报告(只读,未改任何代码)

- 范围:`backend/` 四模块(domain / api / persistence / cli),82 个主源码文件 + ~55 个测试文件 + 33 个 Flyway 迁移,对照 AGENTS.md 约定与 ADR-0001~0004。
- 方法:全量源码扫描(imports/注解/约定 grep)+ 关键文件精读(SecurityConfig、JwtService、JwtAuthFilter、AuthService、GlobalExceptionHandler、ItemNumberRepository、RealGithubGateway、AccessLogFilter、Verdict.kt、AbstractApiTest、build 脚本、迁移 V1)。
- 总评:**架构纪律罕见地好**——四条 ADR 与 AGENTS 约定在代码里全部兑现,零 TODO/FIXME,var:val = 14:1264,错误契约/测试双轨/api.http 负例区全都在。真正的问题集中在:**授权模型(全局权限)、GitHub PAT 明文落库、500 泄露面**三处,外加一组可控的未竟事宜。
- ⚠️ 本报告 Q4"FK 无 ON DELETE"经第二轮(r2)复核为**误判**(正则未跨行,只读了 V1);已在本轮报告修正,保留原文仅作历史记录。**以下阅读请以后续 `backend-review-r2.md` 的修正版结论为准。**

---

## 一、与架构不符的部分

总体判定:**无实质违规**。逐条核对结果:

| 约定 | 判定 | 证据 |
|------|------|------|
| domain 零 Spring/DB 依赖 | ✅ | 全部 imports 仅 `java.time/util` + 自包;引擎(`MatchingEngine`/5 形态 Verdict/rollup)在 domain,api 层 `FeasibilityService` 只消费不重实现 |
| Spring Data JDBC + Flyway,禁 JPA | ✅ | entities.kt 仅 `spring-data-relational` 的 @Id/@Table,jakarta.persistence 0 命中 |
| 实体不可变 + objectId | ✅ | `var` 0 命中(28 实体全 val);28/28 声明 objectId;主构造含 createdAt/updatedAt |
| api 垂直切片 + config.* | ✅ | ~30 个切片均 Controller+Service 同包;技术配置 8 文件全在 config/ |
| Controller 类级 `@RequestMapping("/api")` | ✅ | 28/28 |
| 统一错误形状 + 集中映射 | ✅ | ApiError(code/message/path/fields),NotFound/Conflict/BadRequest 三族业务异常 fail-fast |
| 测试双轨(集成 extends AbstractApiTest;CI 只跑单元) | ✅ | Testcontainers + `@Tag("integration")` 分离,`test` 排除 / `integrationTest` 包含;`check` 依赖 integrationTest(本地全量),与 spec 0014 一致 |
| 迁移 SQL identity + 命名约束 + CHECK | ✅ | V1:8 表 / identity×9 / 命名 CONSTRAINT×30 / CHECK×5 |
| api 无手写 SQL(复杂查询下沉 persistence) | ✅ | api 主源码 0 处 SELECT/JdbcClient;Metrics/Search/ItemTree/ProjectDeletion 均为 persistence 仓储 |
| 引擎等级刻度 1–4、presence 缺省 | ✅ | CapabilityService `level !in 1..4` 抛 BadRequest;Requirement.minLevel null = presence |
| Clock 注入 | ✅ | ClockConfig 存在 |

发现的**边缘偏差**(都不算破规,值得知道):

1. **`api/bin/`、`persistence/bin/`、`persistence/out/` IDE 输出目录存在于工作区**。git 未跟踪(git ls-files = 0),`bin/` 已被 ignore,但 `persistence/out/`(IDEA 生产输出)疑似未显式 ignore——建议补进 `.gitignore`,防误提交。卫生问题,非违规。
2. **命名后缀抽查不一致**:`CapabilityDto` 等少数地方用 `Dto` 后缀,而约定是"API 线型一律语义后缀(XxxRequest/XxxResponse)"。抽查见 `TokenResponse`/`SetTokenRequest` 合规。低严重度,统一时改即可。
3. **entities.kt 单文件 28 个实体(360 行)**。"小切片同文件,吵了再拆"——28 个已到"吵"的边缘;spec 0049(V64 persistence-convergence)刚做过收敛,若这是有意的单文件决策,建议在 ADR/spec 里留一句,否则半年后没人敢拆。

## 二、质量可改进的地方

| # | 位置 | 现状 | 建议 | 严重度 |
|---|------|------|------|--------|
| Q1 | `entities.kt` / 全部 28 实体 | **无乐观锁**(@Version 0 命中),并发正确性靠:原子取号行锁(ItemNumberRepository `UPDATE...RETURNING`,✅ 已并发测试)、终写胜出的 copy+save。已有 `ConcurrentPutHammerApiTest`/`CapabilityParallelPutApiTest` 兜底 | 单人工具可接受;若未来多端同写变常见,给 Item/Project 加 @Version | 中 |
| Q2 | `ItemService.kt`(501 行)、`MetricsService.kt`(446 行) | 切片内最大两个文件;Metrics 含数学+聚合+DTO 于一体 | 不急拆;下次动 Metrics 时先抽纯函数到可单测对象(MetricsMathTest 已存在,方向对) | 低 |
| Q3 | `repositories.kt`(458 行,32 仓储) | 同 entities.kt:单文件多实体仓储 | 与 A3 同理,拆分时机自定 | 低 |
| Q4 | ~~FK 全部无 `ON DELETE` 行为~~ | **二轮修正:误判**。V11 起 24 处 `on delete cascade` 全覆盖子表,assignee 为 `on delete set null` | 撤回本条 | — |
| Q5 | `GlobalExceptionHandler` | 无兜底 `Exception` handler;未捕获异常走 Spring 默认 500,叠加 `include-message: always`,内部异常 message 可能直达客户端 | 加一个 catch-all `Exception` handler 返回通用 "INTERNAL";或把 include-message 收敛为 param | 见安全 S3 |
| Q6 | `IllegalArgumentException → 400 透传 e.message` | 库代码抛的 IAE(如 URI 解析失败)会把英文技术 message 直接返回 | IAE 单独映射为固定文案,或仅业务侧 BadRequestException 透传 | 低 |
| Q7 | `RealGithubGateway` | `URI.create(".../repos/$repo$path")`:repo 字符串未校验格式(owner/name);`?sha=$sha` 拼接 | 校验 repo 形如 `[\w.-]+/[\w.-]+`,sha/ref 白名单字符;风险低(host 固定 api.github.com),但便宜 | 低 |
| Q8 | 14 处 `var` | 全部是循环游标/累加器(BFS frontier、depth、计数),合法 | 无需动;记录"var 白名单=循环变量"作为 review 口径 | 无 |

## 三、有缺陷的地方(功能性)

| # | 位置 | 缺陷 | 严重度 |
|---|------|------|--------|
| B1 | 授权模型(全 API) | **鉴权有、授权无**:28 个 Controller 无一接 Principal/Authentication;JwtAuthFilter 给所有登录者空 authorities;任何持有 token 的用户可读写全部项目/成员/通知。而数据模型是多用户的(Notification.recipientMemberId、ProjectMember、Member.passwordHash、Item.createdBy)——**数据按人建模,接口按全局开放**。单人自用是合理简化,但这是"未声明的架构决策":一旦出现第二个真人账号,越权即刻成立 | **高**(决策层面),当前威胁模型下实际风险低 |
| B2 | 通知接口 | 承接 B1:`recipientMemberId` 存在但读取端未见按调用者过滤(等价于全员可见) | 与 B1 同源 |
| B3 | `GithubSyncService` | `syncRepo` 对每个挂载仓库全量拉 branches(100)+PRs(50),5 分钟轮询;`listCommits` 有 ponytail 注释"只取第一页"——仓库量大/历史长时静默漏数据,无任何告警 | 低(有注释自知) |
| B4 | `AuthService.bootstrap` | 若 workspace 从未初始化过,任何先到者可自设管理员凭据(全新库裸奔窗口)。本机部署可接受;若 8081 曾暴露局域网则有接管窗口 | 低(部署面) |
| B5 | 迁移 V30 | 数据 seed 迁移——seed 放 Flyway 里,新建测试库会自动带默认 workflow,行为耦合值得知道;且与代码侧 defaultStatuses/seedDefaultRules 双源(二轮 Q3) | 提示 |

未发现:SQL 注入(仓储全命名参数,0 spEL)、N+1 高危区(规模小)、日志泄密(AccessLogFilter 脱敏做得好)。

## 四、安全漏洞

| # | 级别 | 问题 | 详情 | 建议 |
|---|------|------|------|------|
| S1 | **中** | **GitHub PAT 明文落库** | `Workspace.githubToken: String?` 直接存 Postgres。API 侧做得对(tokenStatus 只回 `…` + 末 4 位 hint;日志 deny-list 含 githubtoken),但 DB 侧裸奔:拖库即得 PAT | 短期:文档声明威胁模型 + PAT 用最小 scope(fine-grained、只挂需要的 repos);中期:应用层对称加密存库(密钥走 env,与 JWT secret 同机制) |
| S2 | **中** | **JWT 默认密钥可预测** | `YANGZHOU_JWT_SECRET:yangzhou-dev-secret-...` 硬编码兜底。PM 实例(8081)若未设 env,token 可被任何知道仓库的人伪造 | 启动时检测到默认 secret 且非 test profile → fail-fast 或显式警告 |
| S3 | **中低** | **500 响应可能泄露内部信息** | 无 catch-all handler + `server.error.include-message: always` → 未预期异常的 message 会原样返回客户端 | 加兜底 handler,统一 "INTERNAL" |
| S4 | ✅做得好 | 访问日志脱敏 | AccessLogFilter(YPJ-33):写操作记 body,键名命中 deny-list(password/token/secret/githubtoken/authorization/apikey)→ "***",截 500 字符 | 保持 |
| S5 | 低 | `/api/auth/login` 无速率限制 | 本机部署可接受;BCrypt + 24h TTL + 过期校验都在 | 若暴露局域网再加 |
| S6 | 低 | bootstrap/login/`/error`/scalar/api-docs permitAll | 文档端点公开泄露 API 面(仅形状,无数据) | 可接受 |
| S7 | 低 | CLI token 明文存 `~/.yangzhou/session.json` | CLI 生态惯例,与 gh/aws 同级风险 | 无需动 |
| S8 | 信息 | csrf 关闭、CORS 未配 | 纯 token API + 同源代理,正确选择 | 无需动 |

## 五、未竟事宜与值得报告的观察

1. **spec 编号断档:0020 缺失**(现存 47 个;0048 实为存在,初判误报)。若属废弃建议在相邻 spec 注明跳号原因。
2. **引擎是最小实现**:domain 仅 132 行——5 形态判定/绿黄红聚合/逐 item 排序已与 DOMAIN.md 对齐且有 4 个单测;但"团队分配建议"只有 `rankCandidates`(逐 item 排序),没有跨 item 的人员分配优化。若 DOMAIN.md 把分配建议当承诺项,这是最大的功能欠账。
3. **GitHub 分支创建走后端**:POST `/git/refs` 由 API 持 PAT 代执行(yz items branch 依赖此)——PAT 需要写权限,放大了 S1 的暴露后果。收紧 fine-grained PAT scope 时注意。
4. **RealGithubGateway 分页截断**(ponytail 注释自认):branches 100 / PRs 50 / commits 20,超量静默丢失。建议在 repo 挂载时校验仓库规模或加日志计数,让"漏了"可见。
5. **ADR 与代码的类图同步义务**:entities.puml 需核对(二轮已坐实过期 23/28,缺 Comment/ItemActivity/Favorite/ProjectView/TeamMember)。
6. **测试覆盖地图健康**:55 个测试文件对 82 个主文件,切片级集成 + 引擎纯单测 + 并发 hammer + OpenAPI 契约测试;mockk 已引入但 service 层单测仍少(注释自认"mockk 计划 P0")。
7. **@Transactional 68 处**:服务层事务边界普遍存在;GithubSync 无事务包远程调用 ✅。
8. **`build.gradle.kts` 两个编译器 workaround**(-Xjspecify-annotations=warn、-Xjvm-default=all)有注释有出处——升级 Kotlin/Boot 时这两行是第一个复查点。

---

### 一句话总结

后端是**纪律优先**的代码库:架构零违规、卫生极好、测试双轨真实运转。值得动手的只有三件事:**给 S1(PAT)和 S2(默认密钥)一个明确决策**(哪怕决策是"接受,本机工具"),**补一个 500 兜底 handler**,以及**把 B1 授权模型写成显式决策**(单用户是有意设计就写进 ADR,免得第二个用户出现时踩雷)。其余全部可以按票据节奏来。

*本报告为第一轮;修正与深挖见 `backend-review-r2.md`。中间产物在 `.review/tree.txt`,确认后可删。*
