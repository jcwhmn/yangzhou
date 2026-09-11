# Spec — yangzhou V6:GitHub 集成(建分支 + PR 驱动状态)

> 来源:V6 grilling(Round 1+2 全票通过,2026-09-11);决策记录见 Obsidian CONTEXT.md「V6 已定」。词汇表/ADR 同 V1–V3。Kaneo 学习笔记(`D:\obsidian\github\kaneo`)为架构参照:WorkflowRule(事件→移列)/ExternalLink/GitHubIntegration。

## Problem Statement

yangzhou 看板的状态流转全靠手动拖列,而真实进度信号都在 GitHub:分支建了=开工,PR 开了=待评审,PR 合了=待测试。目标:用户在 GitHub 正常干活,yangzhou 自动跟上——方向只有一个:**GitHub → yangzhou,单向**。

与 Linear 无关(定案):本集成完全独立于 V3 的 Linear 镜像,状态变更**不回写** Linear;Linear 侧由人(经 agent)手动维护。

## Solution

三块能力:

1. **建分支**:item 详情页一键创建 GitHub 分支(bot 身份),命名 `<KEY>-<number>-<slug>`(如 `YPJ-42-fix-login`)。
2. **关联**:纯模式扫描——分支名/PR head 分支名含 `<KEY>-<number>` 即自动挂到 item。本地手工建的分支(如 `cwjiang/JCW-123-x`)同样被识别,**关联不强制经过 yangzhou**。
3. **自动流转**:轮询 GitHub,按每项目配置的「事件→状态」映射移列。GitHub 事件不知道 yangzhou 状态名,映射完全在 yangzhou 侧(Kaneo WorkflowRule 同款)。

## User Stories

1. As a 用户, I want item 详情页点「创建分支」就建好 GitHub 分支, so that 不切工具开工。
2. As a 用户, I want 本地手工建的分支/PR 也自动关联到 item, so that 关联不依赖入口。
3. As a 用户, I want PR 打开/合并/关闭后 item 自动移到配置的状态, so that 不用手动拖看板。
4. As a 管理员, I want 在项目设置里配「事件→状态」映射, so that 我的工作流不被 GitHub 绑架。
5. As a 管理员, I want workspace 只配一个 PAT, so that 团队成员零配置。
6. As a 用户, I want item 详情页看到关联分支/PR(含状态), so that 开发进度一目了然。

## Implementation Decisions

**接入与凭据**
- **轮询**:api 模块 `@Scheduled`(yaml:`yangzhou.github.poll-enabled` 默认 true / `poll-interval-ms` 默认 300000);无 PAT 或无挂载仓库时跳过。webhook 入站 defer(5588245.xyz + 浮动 IP 就绪后单独立票;升级只换事件源,管道不变)。
- **凭据**:workspace 级 fine-grained PAT(勾选所需仓库,`contents:write` + `pulls:read`),存 `workspace.github_token`;**永不回显**(界面只见尾 4 位)、不进日志、401 时活动日志 WARN。per-user PAT / GitHub App(installationId)defer。
- **身份映射**:`member.github_username`(手填,workspace 内唯一部分索引),用于把 PR 作者/分支创建者映射到成员(展示用;通知定向留 V7)。
- **GitHub 客户端**:java.net.http + Jackson 手写 5 个端点(repo 信息含 default_branch / 列分支 / 列 PR(state=all, sort=updated 降序,合并状态在列内)/ 建 ref),不加依赖。仅 github.com;Gitea 留接口形状不实现。

**数据(V12 migration)**
- `project_repo`:project **1–0..***(monorepo 1 个;microservice/前后端分离多个);列 `project_id` / `repo`(`owner/name`);uk(project_id, repo)。**无 default 标记**(弹窗预选第一个)。
- `item_git_ref`(Kaneo ExternalLink 同位):`item_id` / `kind`('branch'|'pr')/ `repo` / `ref`(分支名或 PR 号)/ `url` / `state`(PR:open|merged|closed);uk(repo, kind, ref) 是幂等锚点——**轮询去重靠它,不存全量分支清单**(ponytail 上限:超大分支仓库多翻页,团队仓库无此量级)。
- `project_workflow_rule`:`project_id` / `event_type` / 目标 `status_object_id`;uk(project_id, event_type);event_type VARCHAR + CHECK('branch_created','pr_opened','pr_merged','pr_closed_unmerged')——4 槽位,扩展=加枚举值。
- `workspace.github_token`、`member.github_username` 如上。

**关联与流转**
- **关联规则**:分支名/PR head ref 正则 `([A-Z][A-Z0-9]+)-(\d+)`,命中 workspace 内 project key + item number 即关联(project key 在 workspace 内唯一,无歧义)。bot 建的 `<KEY>-<number>-<slug>` 天然命中。
- **自动流转防呆**:目标与当前同项目;`target.position > current.position`(只前进、不倒退);当前非 final(**final 封口**,终态 item 永不被自动移动)。未配映射的事件忽略。
- **WIP 限制**:自动流转撞 WIP 上限 → 跳过并记活动日志 WARN(事件已去重不重试;ponytail 已知上限,人工拖列可补)。
- **留痕**:GitHub 驱动的流转写 item_activity,操作者记 github 事件来源 + PR/分支链接。
- 建分支后**走同一映射管道**(配了 branch_created→In Progress 则建完即动)。

**UI**
- workspace 设置入口:PAT 配置(写后只回尾 4 位)。
- 项目设置页(既有聚合页):仓库列表 CRUD + 4 个映射下拉。
- item 详情:「创建分支」按钮 + 弹窗(仓库下拉预选第一个 / 基线分支预选仓库默认 / 分支名自动填可改);关联分支/PR 列表(状态徽标 + 外链)。

**测试**
GitHub 客户端是唯一出网 seam——测试用手写 fake gateway(不加 wiremock)。轮询服务集成测试(Testcontainers):分支命中→关联+移列;pr_merged / pr_closed_unmerged 各自命中;倒退/终态不动;WIP 超限跳过;重复轮询幂等。PAT/仓库/映射/建分支 REST 黑盒。

## Out of Scope

通知 / created_by / email(V7 spec)· webhook 入站(域名就绪单独立票)· per-user PAT / GitHub App · Gitea 实现 · pr_reopened / push 事件 · Linear 回写(人工,agent 代办)· Reviewing 等细分列(项目自配,引擎不关心列数)· e2e。

## Further Notes

- **Kaneo 参照**:github_integration / WorkflowRule / ExternalLink / domain-model §5.6。其形态是 GitHub App + webhook + 双向;我们 v1 取最薄:PAT + 轮询 + 单向。
- **Jira 参照**:分支含 issue key 自动挂 Development 面板 + PR 事件触发流转——砍面板,留「事件→移列」。
- **dogfood**:YPJ 挂 `jcwhmn/yangzhou`,配全 4 槽映射,真实自吃。
- **切票**:父票(本 spec)+ S1 schema/配置 API + S2 客户端/轮询 + S3 建分支 API + S4 UI/dogfood。
- **图**:S1 改 schema 后同步 `docs/architecture/entities.puml`(domain.puml 不动,引擎无涉)。
