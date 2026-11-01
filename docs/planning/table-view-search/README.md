# 表格视图搜索系统(已 defer)— YPJ-129 / 130 / 132

> 2026-11-02 defer 拍板。表格搜索/排序/筛选的最终形态是一套 **Jira JQL 类搜索语言**,规模为多 sprint epic,不在单票内实现。V26 表格视图 sprint 已收口关闭,本目录归档已有需求与设计,重启时从这里开始。

## 状态

- **defer**:129/130/132 均 To Do + backlog,无 active sprint 承载
- YPJ-130 分支 `feat/YPJ-130-backend-pagination`(检查点 `cf33ef8`,含已实现的后端分页)**停置:不 PR 不 merge**;可回收件:分页 envelope、排序静态骨架(探针结论)、搜索 EXISTS、死页面语义、e2e 基建
- 相邻票 YPJ-122(描述全文搜索)同属本系统,backlog

## 决策记录(2026-11-02,用户拍板)

1. **本系统不受 ADR-0004 约束**——这是复杂搜索系统,不是普通查询;实现时可直接上动态 SQL builder。落实现票时补一条 ADR 修订,划清 ADR-0004 的适用边界(普通 CRUD/投影走 `@Query`,动态查询层豁免)。
2. YPJ-129 + 130 + 132 是**一回事**:一个复杂搜索系统。
3. 自定义属性要**加值类型**(`attribute_definition` 增 value_type 类 schema 变更),不接受纯字符串比较语义。
4. filter DSL **进视图持久化**(V43 存 sort+query 之上扩展)。
5. 终态方向:**Jira 搜索语言(JQL)级**。
6. 130 已有实现离真实需求距离太远 → 停置不合并;需求以本目录为准重启,分支按需回收件、不整体复活。

## 文档索引

| 文件 | 内容 |
|---|---|
| `requirements-analysis.md` | 需求全景:字段源三类映射、操作符矩阵、漏项清单、②vs③复杂度结论、拍板点问答留痕 |
| `YPJ-130 分页搜索设计.md` | Dynamic Query Builder 设计参考(Field Registry / Filter / Sort / Join Resolver,38 节);分层与安全纪律仍适用,其 JDBC client 选型已被决策 1 取代 |
| `spec-0093-as-built-snapshot.md` | 停置分支上 spec 0093 的快照(130 实现 As-built:探针结论、契约真值表、坑位) |

## 重启入口(动工前)

1. 读 `requirements-analysis.md`,圈第一波字段/操作符范围
2. 按 JQL 终态倒推,**拆垂直切片**(每片可独立合并、验证绿),预估多 sprint 排期
3. 实现 ADR:动态查询层的授权边界(决策 1 落地)
4. spec 编号注意:main 上 0093 空缺(快照在本目录),下一票用 0093 还是顺延 0094,开工时定死避免混淆
