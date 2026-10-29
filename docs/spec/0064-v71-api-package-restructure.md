# 0064 — YPJ-71 yz-api 包结构重构:业务收敛 business,非业务各归其位

> 票:YPJ-71(V23)· 影响:api 模块全部包声明 + 测试 import + AGENTS/ADR 文档
> 性质:**纯移动 + import 修正,零行为变更**;不触碰 domain / persistence 模块。

## 1. 需求

### 背景(票面)

api 模块现为「业务切片 + config 大杂烩」扁平结构:30 个业务切片包与 config/support 混在 `yangzhou.api` 一级。业务与非业务无包级边界,依赖方向不显式。

### 范围

1. 业务切片全部平移进 `yangzhou.api.business.<slice>`;
2. 非业务代码各归其位:`config` / `general` / `security` / `error` 四包;
3. 架构决策落 ADR-0007;AGENTS.md「垂直切片」描述同步。

### 非目标

- 任何行为/契约变更(REST 路径、Bean 装配语义、配置键全不变);
- 类/文件重命名、拆分合并(YPJ-55 已做 Controller/Service 分文件);
- domain / persistence / cli / web 任何改动;
- 组件扫描范围变更(Spring 默认扫 `YangzhouApplication` 所在包 `yangzhou.api` 的子树,`business` 子包天然覆盖,无需 @ComponentScan 调整)。

## 2. 详细设计

### 现状盘点(32 包)

- 业务切片 30 个:attachment / attribute / auth / candidate / capability / checklist / comment / dashboard / dependency / favorite / feasibility / github / grouptype / item / member / metrics / milestone / notification / project / projectmember / recyclebin / release / search / sprint / standup / team / time / view / workflow / workspace
- 非业务:`config`(10 文件,杂烩)、`support`(exceptions.kt)、根 `YangzhouApplication.kt`

### 目标形态与归类

```text
yangzhou.api
├── YangzhouApplication.kt          # 不动(扫描根)
├── business/                        # ← 30 个业务切片整包平移,内部结构不变
│   ├── attachment/ · attribute/ · auth/ · candidate/ · …(全部 30 个)
├── config/                          # 纯 Spring/技术配置
│   ├── AuditingConfig · ClockConfig · SchedulingConfig · JdbcRepositoriesConfig
│   └── AttachmentProperties         # @ConfigurationProperties,属配置定义
├── security/                        # 认证/授权基础设施
│   ├── SecurityConfig · JwtAuthFilter · JwtService
├── general/                         # 通用横切处理(非业务 filter 等)
│   └── AccessLogFilter
└── error/                           # 错误处理(原 config + support 收拢)
    ├── GlobalExceptionHandler       # ← 自 config 移入
    └── exceptions.kt                # ← 自 support 移入,support 包删除
```

### 归类判据(ADR-0007 正文写清)

- **business**:有对应 REST 资源/业务用例的垂直切片(实体+DTO+service+controller 同包规则不变);
- **config**:Spring 技术装配(启用某基础设施的 @Configuration / @ConfigurationProperties);
- **security**:认证/授权(JWT 签发、请求过滤、过滤链);
- **general**:与业务无关的横切技术处理(访问日志 filter);
- **error**:错误形状与映射(业务异常 + 全局 handler)。

边界裁决:
- `auth` 切片(AuthController/AuthService,登录/注册/刷新端点)→ **business**(是业务用例);其依赖的 JWT 基础设施在 `security`;
- `AttachmentProperties` → **config**(配置定义,不因 attachment 是业务切片而跟随——它无业务逻辑);
- `GlobalExceptionHandler` 不留 config(config 包从此只有装配类)。

### 机械操作步骤

1. `git mv` 平移 30 个业务切片目录到 `business/`;
2. `git mv` 移动 security 四类、AccessLogFilter、GlobalExceptionHandler、exceptions.kt 到目标包;
3. 删除空目录 `support/`;
4. 全局 package/import 修正(IDE 不可用,python 脚本按「旧包名 → 新包名」映射表批量替换,替换表即上面归类清单);
5. 检查 `@EntityScan`/`@EnableJpaRepositories` 类配置(如有显式 basePackages 指向被移包,同步);
6. api.http 不动(URL 不变);entities.puml/domain.puml 不动(未改实体)。

### 测试影响

- api 模块全部测试(单测 + AbstractApiTest 系)import 被移类 → 同表批量修正;
- 测试文件本身不移动(不引入 test 侧包结构调整);
- 无新测试(零行为变更,既有套件即回归网)。

### 文档

- **新增 `docs/adr/0007-api-package-structure.md`**:五包职责判据 + 上面的边界裁决记录;
- **AGENTS.md**「api 模块内部按垂直切片组织」→「业务切片收敛 `yangzhou.api.business.<slice>`,非业务归 config/security/general/error(ADR-0007)」;
- spec 本文件完工后补 As-built。

### 否决的备选

- **config 不动、只平移业务切片** → 票面明确要求非业务各归其位;GlobalExceptionHandler 留 config 会延续杂烩;
- **auth 整体进 security** → 登录/注册是业务用例,security 应只留基础设施;混入会让 security 变成第二个杂烩包;
- **测试同步分包镜像结构** → 无收益,纯噪音 diff。

## 3. 手工测试用例(用户执行)

| # | 步骤 | 期望 |
|---|---|---|
| TC1 | `gradle :api:bootRun --args='--server.port=8081'` 起实例 | 启动无 Bean/扫描报错 |
| TC2 | api.http 抽样(任选 3 个区块含正负例) | 响应与重构前一致(URL/错误形状不变) |
| TC3 | `gradle build --no-daemon`(单测) | 全绿 |
| TC4 | 本地 `gradle integrationTest` | 全绿 |
| TC5 | web/ 下 E2E 全量跑法(照 AGENTS 标准) | 全绿 |


## As-built(2026-10-23,PR #135 合入 aa5c6bc)

- 按设计落地,无实现期偏差;唯一遗留:测试侧目录/包声明未镜像(spec 既有裁决「测试文件本身不移动」),已立票专门处理。
- 验证:gradle build(单测)绿;integrationTest --rerun 全量绿;E2E 全量 145 requests 0 failed;ijhttp api.http 过;8080/8081 bootRun 正常。
