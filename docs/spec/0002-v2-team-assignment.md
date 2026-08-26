# Spec — yangzhou V2:团队分配(V1 内核之上)

> 来源:V2 grilling(Q1–Q10 全部落定,见 Obsidian CONTEXT.md「V2 已定」)。词汇表同 V1;ADR 0001–0004 不变。

## Problem Statement

V1 回答了"这项目我做得起吗"(单人可行性+技能差距),但引擎的另一半输出——**"这事该谁做"**——从未暴露:rankCandidates 从 V1 第一天就躺在 domain 库里,没有 API、没有 UI。团队协作场景(哪怕是自己管理的虚拟团队)完全无法表达:不能登记成员、不能给 item 指派、更看不到分配建议。

## Solution

**V2 = 团队分配**:虚拟成员(不登录的纯数据人)+ Team 分组 + 候选建议上 API/UI + 单 assignee 指派。thesis"一引擎两输出"补齐闭环:同一引擎,单人模式看可行性,团队模式看"谁来做"。

附带一个卫生小票:workflow 显式起点(isStart),替代 position-0 惯例。

## User Stories

1. As a 单人管理者, I want 登记虚拟成员(姓名+能力自评,无凭据不登录), so that 引擎有"一群人"可算。
2. As a 单人管理者, I want 用 Team 给成员分组, so that 视图上按组过滤人。
3. As a 单人管理者, I want 在 item 上看到候选排序(缺门少→差距小,无加权), so that 一眼知道谁最合适。
4. As a 单人管理者, I want 一键指派某候选, so that item 有了负责人(引擎建议,人拍板)。
5. As a 单人管理者, I want 卡片/详情显示 assignee, so that 看板即分工表。
6. As a 单人管理者, I want `yz members/teams/candidates/assign`, so that 不开浏览器完成分配。
7. As a 用户, I want 新 item 自动落在 workflow 的起点状态, so that 多起点工作流也能正确开局。

## Implementation Decisions

- **虚拟成员**:`member.password_hash` 改可空(V4 migration);空=虚拟(仅匹配,无登录);bootstrap 账号照旧;无自助注册。
- **Team**:纯分组(池)——`team` + `team_member` 两表;**匹配与候选在全 workspace 成员池上算,不看 Team**;Project→Team 归属、RBAC、多 workspace 均 defer。
- **assignment**:`item.assignee_object_id` 可空外键(V4);单 assignee;指派=`PATCH /api/items/{id}`(assigneeItemId);取消指派=置 null。
- **候选**:`GET /api/items/{id}/candidates` → rankCandidates 直出(member+match 结果);无加权,可解释。
- **isStart**(V3 migration,先行小票):`status.is_start` 列;默认 workflow 的 To Do 标起点;新 item 默认落起点状态(无起点时回退首列);`position` 回归纯排序用途。
- **CLI**:`yz members add/list/set`、`yz teams add/list`(可选)、`yz candidates <KEY-N|itemId>`、`yz assign <itemId> <成员名>`。
- **测试分层**(V1 记账的新规矩):本版本如出现含复杂分支的 service(如候选聚合),引入 chess 式分层——service 单测(MockK,不起 Spring)+ 现有 REST 黑盒并存;简单 CRUD 照旧黑盒覆盖。
- 引擎签名不动(rankCandidates 即用);domain.puml 无需变更;entities.puml 随 V3/V4 migration 同步。

## Testing Decisions

同 V1 seam 策略:REST API 黑盒(Testcontainers)+ 引擎纯函数直测(rankCandidates 已有 2 例,V2 补虚拟成员场景例)。虚拟成员/Team/指派/候选全部黑盒覆盖;CLI/Web 瘦壳手工 smoke。

## Out of Scope

真实成员登录/凭据/RBAC · 多 workspace(SaaS)· Project→Team 归属 · 多 assignee/观摩者 · 定制化架构(SPI/authoring/installer/DB 可移植)· 通知 · 工时 · 甘特 · 联邦。

## Further Notes

执行顺序(Q10):isStart 小票 → 成员/Team → candidates+指派 → Web → CLI;约 5 张票。
