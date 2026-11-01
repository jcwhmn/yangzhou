# 需求全景分析(2026-11-02 归档)

> 来源:2026-11-02 会话(130 手测前,用户重估范围并 defer)。本文件是重启时的**需求底稿**,不是最终 spec;重启后须按当 时现实重验(代码/表结构优先于本转述)。

## 1. 问题定义

表格视图的搜索/排序/筛选不是「给现有查询加参数」,而是一套**复杂搜索系统**:

1. 字段来源三类(见 §2):item 自有列 / 用户自定义扩展属性 / join 计算属性
2. **所有字段可 sort**
3. **所有字段可 filter**
4. filter 有多种操作符:`=` `>` `<` 区间 like 等,按字段类型分派
5. 终态:Jira JQL 类**搜索语言**(可组合、可持久化、可共享的方向)

## 2. 字段源三类 → 仓内现实映射

| 源 | 仓内对应 | 例子 | 技术含义 |
|---|---|---|---|
| ① item 自有属性 | `item` 表列 | number/title/type/priority/start_date/due_date/feas_signal/created_at | 直接进 WHERE/ORDER BY |
| ② item 扩展属性 | `item_attribute` EAV(V40),定义住 `attribute_definition`(kind=custom) | 用户自定义任意字段 | 按需 EXISTS(过滤)/ LATERAL(排序);字段集合是数据不是代码 |
| ③ join/计算属性 | join + 派生 | status/assignee/sprint(LATERAL 仅活跃)/release/milestone/parentNumber/overdue/blocked/dueSoon | join resolver 或常驻 join;派生列是表达式 |

130 时点现状:排序白名单 13 字段(78 CASE 静态骨架);过滤仅 status/assignee/priority 三单选 + 全局子串搜索(含属性值 EXISTS)。

## 3. Sort 需求

- 三类字段全量可排序;多列组合(现 3 槽);跨页稳定;null 排最后
- 自定义属性排序:EAV 值 varchar → 字典序;**加 value_type 后**(决策 3)可数值/日期序
- 计算属性排序:overdue/blocked 已做;dueSoon 等待补

## 4. Filter 操作符矩阵(草案,按字段类型分派)

| 类型 | 字段例 | 操作符 |
|---|---|---|
| 枚举(单/多选) | status、type、priority、sprint、release | in / notIn / isEmpty |
| 人员 | assignee | in / notIn / `__none__`(未指派) |
| 日期 | startDate、dueDate、createdAt | from–to 区间 / isNull |
| 文本 | title、feasSignal、parentNumber | contains / eq / isEmpty |
| 布尔派生 | overdue、blocked | 是 / 否 / 任意 |
| 自定义属性 | 任意 custom 定义 | contains / eq / isEmpty;value_type 加后扩数值/日期操作符 |

组合语义:跨字段 AND,同字段多值 OR(in);任意 AND/OR 嵌套(AST)属 JQL 语言层,后置。

## 5. 漏项清单(易低估项,需求盘点时逐条过)

1. 视图持久化:filter DSL 序列化 + 旧视图(仅 sort+query)向后兼容 + 记忆视图恢复带参
2. 前端筛选器 UI:字段选择 × 操作符 × 按类型值输入控件(日期区间/多选/chip)——前端工作量 ≥ 后端
3. 契约形状:平铺 query 参数装不下结构化 filter → 编码 JSON 或 POST body;130 未合并,契约可低成本定型
4. 全局搜索 `query` 与 per-field filter 的共存/合并
5. count 查询必须与 filter 同源(同一 builder 产出)
6. 空值语义:isNull = 未设置(≠ 空串);多值 in 的 OR 语义
7. 测试面:builder 单测 + 操作符×字段源矩阵集成 + e2e + api.http + OpenAPI
8. 性能:百级规模 EXISTS/LATERAL/OFFSET 均不构成问题,**不为本轮做性能设计**

## 6. 复杂度结论:② 扩展属性 vs ③ join/计算属性

用户质询「②的复杂度比③大吗?②是确定的,③还远远没有分析出来」后修正的结论:

- **②(扩展属性)**:模式确定——EXISTS 过滤 + LATERAL 排序一套打完;唯一变数是值类型,拍板加 value_type(决策 3)后只剩确定性工作
- **③(join/计算属性)**:**广度**问题——每个成员(sprint 仅活跃语义/release/milestone/blocked 子查询注参/dueSoon/feasSignal…)要单独钉语义;且 JQL 方向意味着字段清单持续膨胀,无天然边界
- **defer 主因 = ③ 的开放性 + 语言化终态**,不是 ②

## 7. 拍板点问答留痕(2026-11-02)

| # | 问 | 答 |
|---|---|---|
| 1 | 路线:静态骨架延伸 vs 修订 ADR-0004 上动态 builder | 都不对——本系统**不受 ADR-0004 约束**(复杂搜索系统 ≠ 普通查询);130 现实现离真实需求太远,停置不 PR 不 merge |
| 2 | ② 进不进第一波 | 反问复杂度归属 → 结论见 §6;范围重启时圈 |
| 3 | 自定义属性字符串语义 vs 加类型 | **加其它(值)类型**(schema 变更) |
| 4 | filter 进视图持久化 | **确认** |
| 5 | 任务归属 | **129+130 一回事;132 一并并入**;全部 defer |
| 6 | UI 交互形态 | 基本上就是 **Jira 搜索语言** |

## 8. 重启时第一波切片建议(供拆票参考,非决定)

1. 契约与 builder 地基(动态 SQL 层 + ADR 修订 + Field Registry)
2. 日期/数值区间过滤(收编原 129 语义)
3. 枚举族多选/empty 语义
4. 自定义属性(value_type schema + 筛选 + 排序)
5. JQL 文本语法层(最后;UI 与语言孰先待重启时拍板)

每片独立合并、验证绿;130 分支件按需回收,不整体复活。
