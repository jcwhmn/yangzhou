# Spec — yangzhou V3:Linear 联邦(单向拉取)

> 来源:V3 grilling(V3-Q1–Q3,见 Obsidian CONTEXT.md「V3 已定」)。词汇表/ADR 同 V1–V2。

## Problem Statement

用户在 Linear 管理着真实项目(JCW 票),但 yangzhou 对此一无所知——可行性分析、短板面板、团队分配这些 V1/V2 的核心能力,吃不到 Linear 里的真实工作。手动 CSV 导入(JCW-84)是一次性快照:再导一遍就重复,无法持续。

## Solution

**单向联邦**:Linear 为 source of truth,yangzhou 通过 `yz sync-linear` 拉取镜像。以外部引用(`linear:<identifier>`)幂等 upsert——首跑导入,重跑更新,不产生重复。yangzhou 在镜像之上提供 V1/V2 的全部价值:可行性、差距分析、短板面板、候选建议。

双向同步(本地→Linear)、webhook 自动化明确后置:等单向跑稳、真实用出"回写"需求再做。

## User Stories

1. As a 用户, I want `yz sync-linear CHE linear.csv` 一次性导入 Linear 票, so that 不手工搬运。
2. As a 用户, I want 再跑一次同样的命令是**更新而非重复**, so that 同步可以放心地反复执行。
3. As a 用户, I want Linear 的状态名映射到 yangzhou 状态, so that 进度镜像不歪(未知状态名落默认起点)。
4. As a 用户, I want Labels 变成 presence 需求(缺失属性自动建 label), so that Linear 的分类进入匹配体系。
5. As a 用户, I want 同步带进度反馈(每 100 条), so that 大批量导入心里有数。

## Implementation Decisions

- **外部引用**:`item.external_ref varchar(200)`(V7 migration,nullable,workspace+ref 唯一部分索引)——格式 `linear:<identifier>`(如 `linear:JCW-30`);来源前缀为后续 github/其他源扩展留位。
- **幂等语义**:同步按 `(project, external_ref)` upsert——存在则 PATCH(标题/描述/类型/状态回放/需求整表替换),不存在则 POST 创建(自动编号);`external_ref` 创建后不变。
- **状态回放**:按名匹配目标项目 statuses;无同名 → 保持默认起点(不报错,优雅降级)。
- **标签→需求**:复用 JCW-84 逻辑——缺失属性自动建 label(kind=label, leveled=有 >=N 时 true)。
- **CLI**:`yz sync-linear <KEY> <linear.csv>`;进度每 100 条。
- **镜像 item 的边界**:本地对镜像 item 的编辑会在下次同步被覆盖——README 明示"镜像 item 是 Linear 的影子,别在 yangzhou 改它"。

## Testing Decisions

黑盒(Testcontainers):首跑全建、重跑零新增、改 CSV 重跑生效(PATCH 语义)、未知状态落默认、labels 自动建属性;`external_ref` 唯一部分索引防并发重复。

## Out of Scope

本地→Linear 回写 · webhook/轮询自动化 · assignee 同步 · 评论/附件同步 · GitHub/Gitea(格式扩展点已留:`linear:` 前缀)· Linear API 直连(先 CSV,API 直连等真实痛点)。

## Further Notes

kaneo 参考:plugin registry + 事件总线 + 双向 webhook(笔记 §E)——其架构在 V3+ 双向阶段直接可抄。首跑真实数据:JCW-77~90 的 14 张票灌进 yangzhou 自吃(YPJ 项目)。
