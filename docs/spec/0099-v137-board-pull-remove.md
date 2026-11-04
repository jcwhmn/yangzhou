# 0099 — V137:看板拉入/退出(item on_board 显式执行面)

> YPJ-144(2026-11-05)。承接 0098:方案A 后无组项目看板 = 终态归档,item 进看板无途径。
> 用户拍板:看板 = **显式拉入的执行面**,拉入/退回成对。

## ⚠ 显要标注(新域字段 + 三个边界断言,请复核)

- **item 新增 `on_board` 布尔列**——域模型变更(迁移 V47)
- 三个边界按建议落(用户授权实现,PR 请复核):
  1. **完成态留板**:拉入的 item 完成后留板(对齐 sprint 成员历史行为);终态 item UI 不给退回按钮(API 层不设 409)
  2. **退回连清**:退回 = 清标记 + 移出全部**非 completed** sprint 成员行(completed 历史不动)——否则清了标记成员关系仍把它留在板上,退回无效
  3. **开始 sprint 不自动挂**:板上「纯标记」item 不随 start 自动挂 sprint(拉入时已给机会;start 动作保持纯)

## ① 需求

- **需求**:
  - backlog 行「拉入看板」按钮;看板页「拉 item」对话框(候选取 backlog,多选,对齐 V13-S3 sprint 拉入交互)
  - 拉入 = 置 `on_board`;**有 active sprint 时同时挂最早创建的 active sprint**(对齐 YPJ-120 取序语义;已在该 sprint 则跳过,幂等)
  - 退回 = 清 `on_board` + 移出全部非 completed sprint 成员行
  - 谓词真值表:

| 场景 | backlog | 看板 |
|---|---|---|
| 有 active sprint | 非终态 ∧ 未入 sprint ∧ 未标记 | active sprint 成员 ∪ 标记 |
| 无 active(有/无组) | 非终态 ∧ 未入 planned/active ∧ 未标记 | 终态 ∪ planned 成员 ∪ 标记(= backlog 补集,代码不变自动吸收) |

  - 互斥恒成立(board ∩ backlog = ∅)
- **看板独立路由 `/p/{key}/board`**(复用根页看板组件;根页方案A redirect 条件不变)——修复 0098 记录的「补集视图无独立页面」缺口;侧栏「看板」行改指 `/board`
- **非目标**:level-1 组映射(将来「加 level」票)、看板列语义、拖拽排序、侧栏段显隐重构(侧栏票)

## ② 详细设计

- **迁移 V47**:`alter table item add column on_board boolean not null default false;`(无新表,cleanDatabase 清单不动;entities.puml 同步 Item.onBoard)
- **persistence**:`Item` + `val onBoard: Boolean = false,`(Entities.kt)
- **ItemService**:`ItemDto` + `onBoard`;映射处赋值;`backlogOnly` 谓词加 `&& !it.onBoard`
- **ItemGroupService**:
  - `BoardMembershipRequest(val onBoard: Boolean)`
  - `boardMembership(itemId, request)`:`@Transactional`;置/清标记;on→挂最早 active sprint(已在该组跳过;无 active 静默);off→复用 `replaceMembership(groupId=null, groupType="sprint")`(其 live 行过滤天然只删非 completed)
  - board active 分支 filter 加 `|| it.onBoard`;降级分支 filterNot backlogIds 不变(补集自动吸收标记)
- **ItemGroupController**:`PUT /items/{itemId}/board-membership`(镜像 group-membership 形态)
- **api.http**:item 区块新端点
- **测试(集成 BoardMembershipApiTest)**:①无组拉入/退回复原 ②有 active 拉入=成员+标记、退回清成员 ③planned 成员退回清行 ④重复拉入/退回幂等
- **web**:`back.ts` API;backlog 行拉入按钮;`/board` 路由(re-export 根页组件);根页「拉 item」对话框 + 卡片「退回」(仅非终态);texts.ts 文案
- **e2e**:新 `board-membership.spec.ts`(无组拉入/退回、有 active 拉入挂 sprint、互斥)
- 否决备选:复用 group_membership 建隐式系统组(违背 no-group,凭空多组)/ 状态派生(开工即进板,方案1 已否)/ 多端点拆分(一个布尔开关一端点足够)

## ③ 手工测试用例(合并后 rebuild dev)

1. NGX:backlog 行「拉入看板」→ 侧栏「看板」→ `/board` 见该 item(执行面);卡片「退回」→ 回 backlog
2. 有 sprint 项目:拉入(有 active)→ item 同时在 sprint 成员列表;退回 → 成员列表消失 + 回 backlog
3. 拉入后完成(Done)→ 留板;终态卡片无退回按钮
4. api.http 新端点区含负例
