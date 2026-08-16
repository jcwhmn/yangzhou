# Spec — yangzhou V1:PM 内核 + 匹配引擎(API-first)

> 来源:grilling(10 问)→ domain-modeling(ADR 0001–0003)→ prototype(Q10-iii 已验)。
> 词汇表以 Obsidian `CONTEXT.md` 为准;领域图见 `DOMAIN.md`;本 spec 存 `D:/code/yangzhou/docs/spec/`。

## Problem Statement

个人多项目管理者(我)在使用 Linear/Jira/Mantis 时的真实痛点:

1. **个人多项目失焦**:Linear 把编号挂在 Team 上,两个项目(chess/bookkeeping)的任务都是 jcw-N,从任务看不出归属项目。
2. **工具不回答可行性**:没有工具能回答"这个项目以我现在的技能做得起吗?差在哪?我该提升什么?"
3. **GUI 优先,难以脚本化**:批量操作、导入导出、与现有工具(编辑器/CLI 工作流)衔接困难。

## Solution

一个开源、**API-first** 的项目管理系统:

- **Project 为中心**的编号/工作流容器(ADR-0001);Item 同质树(ADR-0002);统一属性词表(ADR-0003);
- **匹配引擎**:单人模式输出**可行性 + 技能差距分析**(判定行 + 聚合信号,形态已由 prototype 验证);团队模式的**分配建议**函数同步实现(V1 不暴露);
- **CLI 与 Web 均为同一 OpenAPI 契约的瘦客户端**——API 是唯一的逻辑归属地。

## User Stories

**账号与引导**
1. As a 单人用户, I want 零配置引导创建账号(首个用户即 workspace 主人), so that 不碰企业级设置就能开始。
2. As a 单人用户, I want 用 JWT 登录所有客户端(API/CLI/Web), so that 一套凭证走天下。
3. As a 单人用户, I want 我的数据全部在我自己的 Postgres 里, so that 可备份、可迁移、不锁死。

**属性词表(Attribute)**
4. As a 单人用户, I want 维护 workspace 级属性词表(kind × leveled), so that chess 和 bookkeeping 共用同一套语言。
5. As a 单人用户, I want 随时新增属性(如「UI 设计」), so that 新需求不被预置清单限制。
6. As a 单人用户, I want 把属性从无等级改为有等级(如「前端」从 label 升为 skill), so that 词表随我的用法进化而不改代码。
7. As a 单人用户, I want label/skill 作为预置 kind 开箱即用, so that 初次使用即有语义。

**能力自评(Capability)**
8. As a 单人用户, I want 维护自己的能力清单(等级 1–4), so that 引擎有输入。
9. As a 单人用户, I want 能力可以只标「有」不评级, so that 半生不熟的技能不被逼造假数。
10. As a 单人用户, I want 修改能力等级后所有可行性输出立即更新, so that 差距分析永远反映现状。

**项目与工作流(Project / Workflow / Status)**
11. As a 单人用户, I want 创建项目时指定 key(如 CHE), so that 任务编号自带项目辨识。
12. As a 单人用户, I want 新项目自带默认 workflow(To Do / In Progress / Done), so that 不配置就能干活。
13. As a 单人用户, I want 增删改项目内的 Status 并标记终态, so that 工作流贴合我的实际。
14. As a 单人用户, I want 在项目视图里只看到本项目的 item 和相关成员, so that chess 的界面不被 bookkeeping 污染。
15. As a 单人用户, I want 归档/恢复/删除项目, so that 死项目不碍眼、历史可回溯。

**工作项(Item)**
16. As a 单人用户, I want 创建 item 时自动获得项目内递增编号(CHE-1、CHE-2), so that 引用永不歧义。
17. As a 单人用户, I want item 用 type 属性区分任务/缺陷/目标等, so that 不同性质的工作共用一套树和状态机。
18. As a 单人用户, I want item 通过 parentId 无限嵌套, so that「无限分级」天然成立。
19. As a 单人用户, I want 系统阻止把祖先挂到后代下面(防环), so that 树不会腐烂。
20. As a 单人用户, I want V1 状态自由迁移(任何 Status 之间拖动), so that 工具不强迫我遵守还没想清楚的流程。
21. As a 单人用户, I want 在 item 详情看到标题/描述/状态/需求/逐条判定, so that 一个页面看全一件事。

**需求声明(Requirement)**
22. As a 单人用户, I want 给 item 声明需求, so that 引擎知道这件事要什么。
23. As a 单人用户, I want 需求可以只标「需要」不设等级, so that 大多数场景零负担。
24. As a 单人用户, I want 只对关键路径 item 设最低等级, so that 差距信号集中在真正要紧的事上。
25. As a 单人用户, I want 把「高级工程师」这类角色需求翻译成分级技能需求(Java≥3、架构≥2), so that 匹配始终基于可度量的能力。

**匹配引擎——单人输出(可行性 / 差距)**
26. As a 单人用户, I want 每个 item 看到逐条判定行, so that 一眼知道哪条需求满足、哪条差多少。
27. As a 单人用户, I want 「差 N 级」明确写出需/有(架构:需≥2,有1), so that 差距可行动。
28. As a 单人用户, I want 「缺能力」(红)与「差级」(黄)在视觉上性质分明, so that 「整块没有」和「差一点」不被混为一谈。
29. As a 单人用户, I want 超配只标「有余」不展开炫耀, so that 输出克制、聚焦缺口。
30. As a 单人用户, I want 未评级能力给出「有但未评级,差距未知」, so that 引擎不假装会算。
31. As a 单人用户, I want 项目级聚合信号(绿/黄/红 + 缺门数 + 总差距级数), so that 项目做得起不用逐条算。
32. As a 单人用户, I want 跨项目查看我的短板集中在哪些技能, so that 知道该练什么。

**CLI**
33. As a CLI 用户, I want 用 token 登录一次后免登操作, so that 脚本/日常操作不弹认证。
34. As a CLI 用户, I want 增删改查项目与 item, so that 不开浏览器也能干活。
35. As a CLI 用户, I want 命令行更新我的能力等级, so that 顺手自评。
36. As a CLI 用户, I want 命令行查询可行性(表格或 JSON 输出), so that 差距分析可进脚本/管道。
37. As a CLI 用户, I want item 的 JSON/CSV 导入导出, so that 从 Linear/表格工具批量搬家、批量备份。

**Web GUI**
38. As a 单人用户, I want 登录后看到项目列表与全局短板面板, so that 首页即答案。
39. As a 单人用户, I want 项目内看板视图(拖卡片 = 改 Status), so that 最直觉的进度操作。
40. As a 单人用户, I want item 详情页编辑需求并即时看到判定变化, so that 声明需求的过程自带反馈。
41. As a 单人用户, I want 能力编辑页, so that 集中自评、集中校准。
42. As a 单人用户, I want 词表管理页, so that kind/leveled/增删属性不依赖 API。

**契约**
43. As a API 消费者, I want 下载 OpenAPI 契约, so that CLI/Web/第三方工具同源生成。
44. As a API 消费者, I want GUI/CLI 的每个功能都有对应 REST 端点, so that API 是完备的(没有秘密后门)。

## Implementation Decisions

**技术栈**(grilling 已定):Kotlin + Spring Boot 4;Postgres + JSONB(灵活属性);JWT 单用户;前端 React + Next.js + MUI + TypeScript;CLI 用 Kotlin(与后端同语言,复用 OpenAPI 生成的 client,fat-jar 单命令运行,启动成本可接受——V1 自用优先)。

**模块边界**:
- `domain`——纯 Kotlin,零 Spring 依赖:实体 + 匹配引擎(可独立单测,可移植)。
- `api`——REST + OpenAPI 生成,薄层,只做编组/校验/认证,不含业务规则。
- `persistence`——Postgres 访问 + JSONB 映射 + migration。
- `cli` / `web`——瘦客户端,各自只依赖 OpenAPI 契约。

**匹配引擎**(核心决策;函数形状直接来自已验证 prototype 的纯模块):
```
judge(req, caps) -> ok | extra | gap(ΔN) | unknown | missing   // 单条判定
matchItem(item, member) -> { verdicts, missing, totalDelta, worst: green|yellow|red }
rollupProject(project, member) -> { results, worst }            // 取最差
rankCandidates(item, members) -> 按 排序   // 团队模式;V1 库内函数,不暴露 API
```
- 判定语义:presence 需求有即满足;leveled 需求比较 minLevel 与 level;未评级 → unknown(不假装会算);无 capability → missing。
- 聚合:missing>0 → 红;有 gap/unknown → 黄;否则绿。附「缺门数 · 总差距级数」。
- **无加权打分**——排序与信号全部可解释。
- 刻度 **1–4**(fixture 实证修正,非 1–5)。

**编号**:每 project 一个递增序列(key-N),并发创建不重号(DB 层保证)。
**树**:parentId 自引用外键;reparent 时沿祖先链校验防环。
**Workflow**:存于 project 的数据(非独立实体):{statuses(name/icon/isFinal)} + {transitions 可空 = 全放行};V1 默认自由迁移。模板系统 defer,仅预置一个默认 workflow。
**Attribute**:kind + leveled,词表 workspace 级;leveled 切换不删除已有等级数据(休眠待唤醒)。
**角色→需求**:角色(Sr. dev 等)不进模型,由用户翻译成分级技能需求(ADR-0003 正交原则)。
**认证**:单用户 bootstrap;JWT;无注册流。OIDC defer。
**导入导出**:JSON 全保真(结构/需求/type;编号不保留,导入重新分配);CSV 平面子集(item + 需求扁平列)。
**i18n**:V1 界面中文优先,文案外置以便后补英文。

## Testing Decisions

- **唯一 seam = REST API**:黑盒集成测试(Testcontainers 真实 Postgres),通过 HTTP 驱动、断言状态码/响应体/变更后状态;不 mock 领域与仓储。
- **引擎单元测试**:纯函数直测;**测试矩阵 = prototype 场景 S1–S7 + 真实 fixture**(JCW-30 超配+差级、JCW-44 有余、JCW-46 混排、BOO-UI 整块缺失),场景定义存于 prototype 分支。
- 好测试只测**外部行为**:重命名表/改内部类名永不红;红了一定是行为坏了。
- CLI/Web V1 不写自动化测试(瘦壳),每版本手工 smoke 清单;E2E defer。
- 先例:绿地无先例;prototype 的场景清单即种子用例集。

## Out of Scope(V1 明确不做)

Team 逻辑与分配建议 UI(rankCandidates 仅留函数)/ RBAC / OIDC / per-type workflow / Project Group / 模板与阶梯包(仅一个默认 workflow 预设)/ 缓解手段建模(AI 补位等,BOO-UI 案例 defer)/ GitHub/Gitea 联邦 / 通知 / 工时 / 甘特 / 评论与活动流 / 审计日志 / 部署物(Helm 等)/ 多 workspace / E2E 测试 / 英文界面。

## Further Notes

- 活文档:Obsidian `D:\obsidian\projects\yangzhou\`(CONTEXT / DOMAIN / DELIVERABLES / Thoughts)= 词汇与决策真相源;本仓库 `docs/adr/` = 0001–0003。
- Prototype 留档:`prototype/matching-engine-output` 分支;其场景 Tabs(S1–S7)= 引擎测试矩阵;其纯 JS 模块 = 引擎参考实现。
- 开发种子数据用真实 fixture(chess: JCW-30/44/46;bookkeeping: BOO-1/2/UI;能力清单按真实自评)。
- defer 台账统一记在 CONTEXT.md「未决」段,回看 DELIVERABLES.md 补账。
