# yangzhou — 仓库宪法(给 agent)

> 本文件 = 宪法 + 按需加载索引。操作细节按 §真相源 触发读专项文档(`docs/agent/`);规则分级:§硬约束(MUST,违反=事故)> §原则(默认,例外需理由)> 专项文档(参考,按需查)。

## 项目

开源项目管理系统。核心:**匹配引擎**(Requirement × Capability → 单人输出可行性/技能差距,团队输出分配建议)。API-first:CLI 与 Web 都是 OpenAPI 契约的瘦客户端。栈:Kotlin + Spring Boot 4 / Postgres + JSONB / Next.js + MUI + TS / AGPL-3.0。main 干线开发,原型留 `prototype/*`;每张功能票 = 一个 YPJ item(dogfood)。

## 布局(ADR-0004)

```
backend/   Gradle 多模块:domain(纯 Kotlin,零 Spring)· api(REST 薄层)· persistence(Postgres)· cli(fat-jar)
web/       Next.js + MUI + TS,瘦客户端
docs/      adr/ · spec/ · architecture/ · requirement/ · planning/ · agent/ · testing/
```

## 真相源(动笔前按触发条件读)

**领域与需求:**

- **命名实体或写用户可见文案前** → 词汇表 `D:\obsidian\projects\yangzhou\CONTEXT.md`(**Item** 不是 Task;Workspace/Project/Team/Member/Attribute/**Requirement**/**Capability**)
- **实现引擎、实体关系或输出形态前** → `D:\obsidian\projects\yangzhou\DOMAIN.md`(判定 5 形态、绿黄红聚合规则、领域规则 6 条)
- **实现某张票时** → YPJ item(票自带验收清单;spec 对应 `docs/spec/00NN`)
- **做 ItemGroup/视图/导航类功能前** → `docs/requirement/ItemGroup PRD.md`(§28.2 Phase 划分)
- **做甘特图功能前** → `docs/requirement/甘特图 SRS.md`(v4 开发基线;渲染真值表/包络/排序/徽标/互算全分支)
- **动 schema 或架构前** → `docs/adr/`(0001 三层容器 / 0002 Item 同质树 / 0003 统一属性 / 0004 monorepo 与工具链)
- **写调研/对标类文档前** → 放 `D:\obsidian\projects\yangzhou\`(Obsidian vault),repo 不存(2026-10-14 定)
- **需求类文档一律 SRS 格式**(2026-11-21 定,甘特图 SRS v4 起):目的与范围/术语/数据语义/分支真值表/验收标准/开放决策清单,单一真相源不与旧格式并存

**流程与操作(docs/agent/,按触发必读):**

- **开票/推进状态/指派/排期/分支/PR/合并/sprint 收口前** → `docs/agent/workflow.md`
- **写 spec/设计/实现/交付/验收/诊断前** → `docs/agent/development.md`
- **写或跑任何测试(单元/集成/E2E/api.http)或手工验收前** → `docs/agent/testing.md`;写/改后端测试、新建迁移、动 AbstractApiTest、跑 gradle 验证另必读 skill `.agents/skills/backend-testing/SKILL.md`
- **起停容器/查端口/动数据库/杀进程/CLI 中文乱码** → `docs/agent/environment.md`
- **测试红/bug/异常行为,处理前** → `docs/agent/troubleshooting.md`(处理后必记台账)

## 架构与领域硬约束(MUST)

- `domain` 零 Spring/DB 依赖——引擎是纯函数(独立 Gradle 模块,与 chess 单体「禁止 domain 包」约定不同,属有意为之,ADR-0002/0003 的物理形态)
- 角色永不进匹配输入(ADR-0003);等级刻度 1–4;presence 需求为默认,min-level 只用于关键路径
- 用户可见文案中文优先,文案外置
- **永不用 JDBC client**(JdbcTemplate/JdbcOperations)——跨表/投影/批量/级联一律 repository `@Query`/`@Modifying`(Repositories.kt,ADR-0004 唯一授权扩展点)
- Spring Data JDBC + Flyway;禁 JPA/Hibernate/Exposed/BaseEntity 继承
- 实体主构造直接声明 `id`/`objectId`/`createdAt`/`updatedAt`;`objectId` 在 Kotlin 生成
- 对外只暴露公共 ID(`projectId`/`itemId`),永不暴露内部数字 id;FK 用 `object_id` UUID 列
- migration 用 SQL identity + 命名约束;枚举 = Kotlin enum + VARCHAR + CHECK 约束
- 改 `backend/domain` 实体/引擎签名 → 同步 `docs/architecture/domain.puml`;改 Flyway schema → 同步 `entities.puml`(只画领域字段,parentId 反转为父侧 children);两图顶部一律 `hide empty members`,不提交生成图片

## 代码与契约(MUST)

- 尽量 `val`;实体/DTO 用不可变 data class;更新用 `copy(...)` + save,用返回实例
- 命名分层:domain 与 persistence 共用裸名(包即命名空间,同文件两用时 import 别名);API 线型一律语义后缀(`XxxRequest`/`XxxResponse`),裸名永无三义
- `api` 模块业务垂直切片收敛 `yangzhou.api.business.<slice>`(project / item / attribute / …,实体+DTO+service+controller 同包);非业务归位 `config`(装配)/ `security` / `general` / `error`(ADR-0007)
- service fail-fast 抛业务异常;HTTP 映射集中 GlobalExceptionHandler;统一错误形状(code/message/path/可选 fields);请求校验用 Bean Validation
- OpenAPI + Scalar(不用 Swagger UI/Knife4j);Controller 类级 `@RequestMapping("/api")` 只扛 API 前缀,资源段全在方法级
- **改 REST API(新增/改参数/删端点)必须同步手测脚本 `backend/script/api.http`**
- 不加 mapstruct,slice 内手写小 mapper;日志默认 SLF4J/Logback,YAML 配级别,不加 MDC/关联 ID/自定义 logback 除非新决策

## 开发与交付原则(默认,细则见 `docs/agent/development.md`)

- 票即计划;无 spec 不开工;外科手术式改动,不顺手重构无关代码
- 最简可行实现,不加投机抽象;非平凡行为变更必须带测试
- 格式化走工具链(backend `gradle ktlintFormat`、web `npm run format`;CI 有 format check)
- 声称完成前跑通相关验证(后端 = `gradle build` + `integrationTest` 绿 + bootRun 能启动);**agent 本地验证 ≠ 用户环境验收**——功能+测试代码写完即停,等用户 build/debug,过了再 PR
- **架构破坏必须显要标注**:凡偏离 ADR/既定架构处,在 spec/PR/报告中专节置顶 + 加粗,不得混在设计细节里带过(2026-10-17 定)
- 每验证绿点就地 commit(消息带票号);PR squash 进 main,本地分支永久保留不删

## 环境红线(MUST,细则见 `docs/agent/environment.md`)

- 三 profile:**3000(web)+8080 = prod/PM 真实数据 · 3001(web)+8081 = dev 仅手工测试、禁自动化 · 8082 = test 唯一自动化主场(库 `yangzhou_test`)**
- e2e/api.http 等自动化**只打 8082**;动 PM 数据前确认 yz session 指 prod(api 8080)
- **不要猜前端或后端是否已运行**——需要运行环境的,告知所需环境、命令,向用户确认
- 前端命令必须在 `web/` 目录下执行;停服务按端口找 PID,**严禁 `taskkill /IM node.exe`**
- context-mode 批处理实跑 PowerShell(`&&`、`find`、`/dev/null` 会挂;简单命令直接 Bash)

## 验收纪律(MUST,七规全文见 `docs/agent/development.md` §诊断交付七规)

- 验收 = **用户主路径**测试绿;归因必须实锤(查库/直调),未实锤只标假设
- 品味型两可设计动工前摆方案给用户拍板,不代选;交用户复验前先做最小数据探针
