# yangzhou

开源项目管理系统——以**匹配引擎**为核心:Requirement × Capability 撮合,单人模式输出技能差距与可行性分析("这项目我做得起吗?该提升什么?"),团队模式输出分配建议("这事该谁做?")。

Kotlin · Spring Boot 4 · PostgreSQL · Next.js · CLI-first · AGPL-3.0

状态:核心已可用,开发者日常自用 dogfood 中——开发本系统的票就开在本系统里。

## 特性

- **匹配引擎**:item 挂需求(skill/label + 可选等级 1–4),member 挂能力;逐条判定 ✓满足 / ✓有余 / △差 N 级 / △未评级 / ✗缺能力;项目级红黄绿聚合,rollup 取最差
- **分配建议**:谁来做?按缺门少 → 总差距小排序,附逐条判定作理由;**无加权打分**,保持可解释——引擎建议,人拍板
- **Item 同质树**:唯一工作单元 Item,`parentId` 无限嵌套,type 是属性不是实体;编号前缀即项目(CHE-1)
- **看板 / Backlog / Sprint / Milestone / Release**:列即状态,workflow 是数据(模板预填、可微调),不是代码
- **时间维度**:起止日期、只读甘特、工时记录(计时器 + 手动补录)、Excel 导出
- **协作**:站会视图、item 依赖、检查清单、回收站、通知、驾驶舱
- **GitHub 集成**:仓库挂载、分支/PR 关联 item、按需拉取提交历史(轮询兜底)
- **CLI-first**:`yz` 覆盖全操作面;JSON/CSV 导入导出;REST API 配 OpenAPI + Scalar 文档

## 核心概念

| 概念 | 一句话 |
|---|---|
| Workspace | 数据与管理归属的最高容器(单租户时隐形) |
| Project | 工作容器:item 编号序列 + workflow + 视图 |
| Item | 唯一工作单元(不叫 Task/Issue),同质树无限分级 |
| ItemGroup | Item 的命名集合(v1 即 Sprint);只是工作组织维度,**不进匹配输入** |
| Attribute | 统一属性词表,`kind` 细分 + `leveled` 开关,Workspace 级 |
| Requirement | Item 侧需求:`(attribute, min-level?)`,缺省按 presence 匹配 |
| Capability | Member 侧能力:`(attribute, level?)`,未评级只算 presence |
| Member | 人员;角色永不进匹配输入(角色翻译成分级技能需求) |

## 快速开始

依赖:JDK 25(LTS)、Node.js、PostgreSQL。

```bash
# 1. 建库(连接参数见 backend/api/src/main/resources/application.yml,可环境变量覆盖)
createdb yangzhou

# 2. 后端(8080;Flyway 自动建表)
cd backend && gradle :api:bootRun

# 3. 前端(3000,代理到后端;BACKEND_URL 可覆盖)
cd web && npm install && npm run dev

# 4. CLI(首次自动构建 fat-jar)
backend/script/yz login --server http://localhost:8080   # 全新服务器自动 bootstrap
backend/script/yz members list
backend/script/yz feasibility <PROJECT_KEY>              # 可行性 / 技能差距
```

## 测试

```bash
cd backend && gradle build        # 单元 + 集成(集成测试用 Testcontainers,需 Docker)
cd web && npx playwright test     # E2E;需 test profile 后端:
                                  #   gradle :api:bootRun --args='--spring.profiles.active=test'
```

## 文档

- `docs/adr/` — 架构决策记录(三层容器 / Item 同质树 / 统一属性 / monorepo)
- `docs/spec/` — 版本规格,每版本一个文件
- `docs/architecture/` — PlantUML 类图
- `docs/requirement/` — PRD
- `backend/cli/README.md` — yz 命令详解

## License

[AGPL-3.0](./LICENSE)。2026-10 从 MIT 迁移:已发布的 MIT 历史版本对既有 fork 保持 MIT 不变,此后的版本以 AGPL-3.0 发布。
