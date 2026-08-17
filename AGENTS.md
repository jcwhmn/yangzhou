# yangzhou — 仓库指南(给 agent)

开源项目管理系统。核心:**匹配引擎**(Requirement × Capability → 单人输出可行性/技能差距,团队输出分配建议)。API-first:CLI 与 Web 都是 OpenAPI 契约的瘦客户端。栈:Kotlin + Spring Boot 4 / Postgres + JSONB / Next.js + MUI + TS / MIT。

## 真相源(动笔前按触发条件读)

- **命名实体或写用户可见文案前** → 词汇表 `D:\obsidian\projects\yangzhou\CONTEXT.md`(**Item** 不是 Task;Workspace/Project/Team/Member/Attribute/**Requirement**/**Capability**)
- **实现引擎、实体关系或输出形态前** → `D:\obsidian\projects\yangzhou\DOMAIN.md`(判定 5 形态、绿黄红聚合规则、领域规则 6 条)
- **实现某张票时** → Linear JCW-78~84(每票自带验收清单;父票 JCW-77 = spec 全文 `docs/spec/0001-v1-core-and-matching.md`)
- **动 schema 或架构前** → `docs/adr/`(0001 三层容器 / 0002 Item 同质树 / 0003 统一属性 / 0004 monorepo 与工具链)

## 布局(ADR-0004)

```
backend/   Gradle 多模块:domain(纯 Kotlin,零 Spring)· api(REST 薄层)· persistence(Postgres)· cli(fat-jar)
web/       Next.js + MUI + TS,瘦客户端
docs/      adr/ · spec/
```

## 硬规则

- `domain` 零 Spring/DB 依赖——引擎是纯函数,从 prototype 抬来(分支 `prototype/matching-engine-output` 有参考实现与场景矩阵)。
- 测试 seam:REST API 黑盒(Testcontainers 真 Postgres,不 mock 领域/仓储);引擎纯函数直测;CLI/Web 瘦壳无自动化测试,附手工 smoke 清单。
- 角色永不进匹配输入(ADR-0003);等级刻度 1–4;presence 需求为默认,min-level 只用于关键路径。
- 用户可见文案中文优先,文案外置。

## 构建与运行

(票 1 落地 Gradle 时补:JDK 21、`./gradlew` 命令、Testcontainers 起法。)

## 工作流

main 干线开发;原型留 `prototype/*` 分支;每张票 = 一个 Linear issue,验收清单全绿才关票。
