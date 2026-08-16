# 0002 — Item 同质树,而非类型化分层

工作项建模有两种模式:Jira 式类型化分层(Epic→Story→Task→Subtask,每层不同实体、固定语义)与同质树(单一 Item 实体 + `parentId` 可空自引用 + type 属性)。决定采用**同质树**。

理由:①产品需要"无限分级",固定层级做不到;②匹配引擎只认一种实体,更简单更深;③类型化分层正是 Jira 复杂度之源,与"易用"论点冲突;④Milestone/Sprint 将来以时间/分组属性表达,不必是层级。

Consequences:层级语义靠 type 属性 + UI 自律区分;需在写入时校验祖先-后代不成环;Epic/Story 等词仅作为预置 type 值,不是实体。
