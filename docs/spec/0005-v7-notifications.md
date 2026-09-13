# Spec — yangzhou V7:通知 + 通知驱动流转

> 来源:V7 grilling(Round 1+2,2026-09-11 定,初稿决策见 Obsidian CONTEXT.md「V7 已定」;V6 落地后细化)。词汇表/ADR 同 V1–V6。

## Problem Statement

状态动了没人知道,流转的下一步(评审、QA)靠人盯看板。通知不止是提醒,还要是**工作台**:收到通知 → 点按钮 → 流转发生。这就是 V6 grilling 时用户设想的核心循环(GitHub 事件 → 状态 → 通知人 → 人点一下 → 下一段流程)。

## Solution

**站内通知** + **动作按钮驱动流转**:

1. 触发:状态变更 / 被指派 / item 新评论 → 通知 **assignee + 创建者 + 既有评论者**(去重,排除操作者)。
2. 通知带**动作按钮**(推进到下一列):点击调**既有** move API,服务端复验全部规则。按钮由「目标状态存在且在后」推导,**不硬编码状态名**;无对应列退化为纯深链。
3. 新项目默认工作流升级 **5 列**:To Do / In Progress / In Review / QA / Done(存量项目不动,数据行红利)。

## User Stories

1. As a 用户, I want item 状态变化时收到站内通知, so that 不盯看板也知道进展。
2. As a 用户, I want 通知上一键推进到下一列, so that 下一步操作零导航。
3. As a 用户, I want 被指派/被评论时收到通知, so that 不漏掉和我有关的事。
4. As a 用户, I want 未读数挂在导航铃铛上, so that 一眼看到有没有新事。
5. As a 管理员, I want 新项目默认就是 5 列工作流, so that 评审环节天然有位置。

## Implementation Decisions

**V15 migration**
- `item.created_by bigint` FK member **可空**(存量 null;通知收件人自动降级为 assignee+评论者)
- `notification`:object_id / recipient_member_id FK / item_id FK(cascade)/ kind VARCHAR+CHECK('status_changed','assigned','commented')/ actor_member_id 可空 / old_value、new_value text / read_at timestamptz 可空;idx(recipient_member_id, read_at)
- **不做 JSON payload**——old/new value + item 关联已够渲染;通用事件总线是 Kaneo 式过度设计,等真需求

**触发接线**(service 层三处)
- 状态变更(ItemService.update + GithubSyncService.applyEvent)→ 通知 assignee + created_by + 评论者
- 指派(ItemService.assign)→ 通知新 assignee
- 评论(CommentService.create)→ 通知 assignee + created_by + 既有评论者(排除作者)
- 收件人去重;排除操作者(GitHub 事件 actor 为 null,不排除任何人)

**动作按钮(下一列推导)**
- 通知读 API 为每条现算 `action`:同项目 **position 大于当前的最小 position 状态**(当前非 final 才有);无 → 无按钮
- 点击 = 前端调既有 `PATCH /api/items/{id} {statusItemId}`;服务端原样复验(迁移表/WIP/开工须有主),409 消息冒回通知页
- **不存「建议状态」**——读时现算,工作流改了按钮自动跟随

**通知 API**
- `GET /api/notifications`(自己的;未读在前,最近 50 条,带 item 编号/标题/项目 key)
- `GET /api/notifications/unread-count`
- `PUT /api/notifications/{id}/read`、`POST /api/notifications/read-all`
- recipient 恒 = 当前登录者,无跨用户读取

**默认工作流 5 列**
- `ProjectService.defaultStatuses()`:To Do(start)/ In Progress / In Review / QA / Done(final);存量项目不动

## Testing Decisions

黑盒(Testcontainers):状态变更三收件人各一条、排除操作者、created_by null 降级、GitHub 事件照发;评论/指派触发;action 推导(有下一列才给,final 无);动作点击走 move API 复验(WIP 409 冒回);read / read-all;默认 workflow 5 列断言。

## Out of Scope

邮件 / webhook 出站 · @提及 · NotificationPreference(per-user 偏好)· 通用事件总线 · reviewer 字段(广播认领已定,真痛再加)· e2e。

## Further Notes

- **Kaneo 参照**:Notification(userId/title/content/type/eventData json/isRead)+ NotificationPreference——砍 preference 与 JSON payload。
- **动作按钮语义 = 「下一列」**,不是「开始评审」这类语义名:引擎不知道哪列叫评审;语义化动作等真实痛感再抽象。
- **切票**:父票(本 spec)+ S1 后端(V15 + 触发 + API + 5 列默认)+ S2 Web(铃铛 + 通知页 + 动作按钮)+ dogfood。
