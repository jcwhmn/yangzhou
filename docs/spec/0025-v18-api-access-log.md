# 0025 — YPJ-33:REST API 调用日志(脱敏/分级/截断,铺 Grafana)

> 2026-10-12 立项。状态:**已过目并按 Filter 机制实施(2026-10-12),编码完成待 build/debug**。As-built 完工后补。
> ⚠ 一处机制偏移需用户裁决,见「详细设计 · 机制选择」。

## 需求

**背景**:票面 grill 定稿——每次 API call 至少一条日志;Spring AOP 切 controller 记 method/path/参数/耗时/响应状态;脱敏硬规则(password/token 等永不进日志原文);长 body 截断(~500 chars);写操作 INFO / GET DEBUG,yml 可调;格式稳定、单行结构化友好,为 Grafana 铺路。

**范围**
1. 每个命中 `/api/**` 的请求一行访问日志:method、path、status、durationMs,写操作附 body。
2. 脱敏硬规则:body JSON 里键名命中拒绝表 → 值替换 `***`(现库确有:登录 password、`SetTokenRequest.token`)。
3. body 截断 500 chars(超长加 `…(truncated)` 标记);非 JSON body 原样截断,不抛错。
4. 级别:POST/PUT/PATCH/DELETE = INFO,其余(GET)= DEBUG;logger 名 `yangzhou.api.access`,yml 可调。
5. 响应体不落日志(票面未要求;收敛敏感外泄面)。

**非目标**
- 不做 MDC/关联 ID/trace 传播(AGENTS.md 禁项,无新决策不动)。
- 不做响应体记录、不做指标暴露(Micrometer/Prometheus)——Grafana 接入是后续票。
- 不记 docs 端点(/scalar/**、/v3/api-docs/**、/error)——零观测价值刷屏。

## 详细设计

### 机制选择(⚠ 需复核)

票面写「Spring AOP 切 controller」。调研后**建议改用 Servlet Filter**(`OncePerRequestFilter`),理由:

| | AOP(票面原文) | Filter(建议) |
|---|---|---|
| 依赖 | 需新增 `spring-boot-starter-aop` | 零新依赖 |
| 响应状态 | 只能推断:正常返回读 `@ResponseStatus`(201 才标),异常路径不知 GlobalExceptionHandler 映射结果 → 状态字段失真 | 真实 status,含 401/400 等异常响应 |
| 房型 | 新机制引入 | `JwtAuthFilter` 即 `OncePerRequestFilter`,同款 |
| 请求参数 | 方法 args 序列化(含 DTO 反射) | body 缓存 + 键名脱敏,只对 JSON 文本操作 |

票面「AOP」是实现手段想象,验收条款(日志存在 + 密码 mask)两种机制都满足。**偏差点:状态字段在 AOP 下会失真,Filter 下精确**——请裁决;不反对则按 Filter 实施。

### 实现

**新增 `backend/api/src/main/kotlin/yangzhou/api/config/AccessLogFilter.kt`**(技术配置归 `config.*`):
- `OncePerRequestFilter`;`shouldNotFilter` 排除 `/scalar`、`/v3/api-docs`、`/error`。
- 包装 `ContentCachingRequestWrapper` → `chain.doFilter` → 之后读回:
  - `status = response.status`,`durationMs = System.currentTimeMillis() - start`
  - body:仅写方法且 wrapper 有内容时;按 UTF-8 读出 → 尝试 Jackson `readTree`(复用现有 ObjectMapper bean?filter 里自持 `ObjectMapper` 静态实例即可)→ 递归遍历,键名(lowercase)∈ 拒绝表 `{password, token, secret, githubtoken, authorization, apikey}` → 值置 `"***"` → 序列化 → 截 500 chars;parse 失败 → 原文截 500。
- logger:`private val log = LoggerFactory.getLogger("yangzhou.api.access")`;单行
  `method=POST path=/api/items status=201 durationMs=12 body={"title":"x"}`(GET 无 body 段)。
- 级别:`when (method) { POST/PUT/PATCH/DELETE -> info else -> debug }`。

**`SecurityConfig`**:注入 `AccessLogFilter`,`addFilterBefore(accessLogFilter, JwtAuthFilter::class.java)`——置于 JWT 之外,401 也留痕(「每次 call 至少一条」)。

**`application.yml` / `application-test.yml`**:加 `logging.level.yangzhou.api.access: INFO`(首个 yml logging 配置;DEBUG 场景 yml 调)。

否决备选:AOP 方案(理由见上表);拦截器 `HandlerInterceptor`(status 在 afterCompletion 可得,但 request body 拿不到,仍要 wrapper,不如 filter 一站式)。

### 测试

**集成(新增 `AccessLogFilterTest extends AbstractApiTest`,`@ExtendWith(OutputCaptureExtension::class)`)**:
1. POST `/api/auth/login`(permitAll,错误密码即可)→ captured output 含 `method=POST path=/api/auth/login`、`"password":"***"`,且不含原始密码(验收条款 2)。
2. 同用例断言 INFO 级行存在(验收条款 1)。
3. GET 默认级别**不出现**;`@SpringBootTest(properties = ["logging.level.yangzhou.api.access=DEBUG"])` 变体下 GET 行出现。

**手工用例(build/debug 阶段)**
1. bootRun 后 web 登录一次 → 控制台一行 `method=POST path=/api/auth/login status=… "password":"***"`。
2. 建超长描述 item → body 截 500 + truncated 标记,接口功能不受影响。
3. POST 非 JSON(乱文本)→ 原样截断,无异常,正常 4xx/2xx。
4. 未带 token 请求受保护端点 → 401 也有一行日志。

## 验收对照(票)

- [ ] 集成测试断言日志输出存在(至少写操作 INFO 一行)→ 集成用例 1/2
- [ ] 登录接口日志中密码字段为 mask → 集成用例 1

## As-built

> 2026-10-13 完工。机制经用户裁决:Filter(票面 AOP 偏移,理由见「机制选择」表)。

**实际落地**

- `config/AccessLogFilter.kt`(新):`OncePerRequestFilter` + `ContentCachingRequestWrapper(request, 8192)`;单行 `method= path= status= durationMs= body=`;拒绝表 `{password, token, secret, githubtoken, authorization, apikey}`(键名小写比对)→ `"***"`;body 截 500 + truncated 标记;非 JSON 原样截断;响应体不落。
- `SecurityConfig`:`addFilterBefore(accessLogFilter, LogoutFilter::class)`——**编码期修正**:Spring Security 要求锚点为已知序 filter,自定义 JwtAuthFilter 不能做锚;LogoutFilter 标准序在 UsernamePassword 之前,AccessLog 仍居 JWT 外层,401 留痕达成。
- `application.yml` / `application-test.yml`:`logging.level.yangzhou.api.access: INFO`(本仓库首批 logging 配置)。
- **handler= 段(2026-10-13 评审追加)**:`AccessLogHandlerInterceptor` 把 `beanType#method`(如 `AuthController#login`)写入 request attribute,filter 取到则追加 `handler=…`;JWT 401 短路在 interceptor 之前 → 无该段。同文件新增 `AccessLogWebConfig` 注册。
- 测试 4 例(2 类):bootstrap 密码 mask + INFO 行 / GET 默认不落 / DEBUG 变体(properties 覆盖)GET 落行 / 401 短路留痕(body 未消费不落段,同设计)。

**编码期发现(设计外修正)**

1. Framework 7 的 `ContentCachingRequestWrapper` 必传 `cacheLimit`(取 8KB:截断 500 chars 足够)。
2. **测试级别全局泄漏**:DEBUG 变体类把 logger 全局改 DEBUG;其他类复用缓存 context 时 startup 不重放 yml 级别 → 顺序依赖偶发。修:两个测试类各自用 context properties 钉死级别(任意顺序确定);DEBUG 类跑完后全局 logger 留 DEBUG,对无关类仅控制台噪音。
3. Jackson 3 导入包为 `tools.jackson`(误用 com.fasterxml 编译期即报)。

**验证**

- 集成 4 例 + 全量 `:api:integrationTest`(40 类)绿;`:api:test` 单元套件绿;用户本地 `gradle clean build` 通过(修泄漏后复跑)。

**遗留**

- Grafana 接入(日志→指标管道)另票;DEBUG 变体类的全局 logger 残留无断言影响,如后续测试类断言该 logger 需同样钉级别。

