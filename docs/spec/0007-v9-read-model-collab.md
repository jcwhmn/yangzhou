# Spec — yangzhou V9:读模型与协作深化

> 来源:V9 grilling(2026-09-13,Round 1 + Round 2 全票);决策记录见 Obsidian CONTEXT.md「V9 已定」。词汇表/ADR 同 V1–V8。用户反馈三项(看板刷新/feasibility N+1/需求编辑入口丢失/铃铛调用过多)直接进本 sprint。

## Problem Statement

三类问题:
1. **读模型 N+1**:项目列表页对每个项目单独调 feasibility API(全量重算),item 级不满足标记也无冗余——查询贵、架构歪。
2. **体验缺陷**:看板无刷新(要手动刷新浏览器);铃铛未读数每页重挂载+每次导航都调 API;item 详情的「编辑需求」入口在某次重构中丢失。
3. **协作功能缺口**:item 无依赖(阻塞关系)、无检查清单、无每日站会视图、误删无回收站。

## Solution(六块)

1. **读模型冗余(CQRS)**:`item.feas_signal` + `project.feas_signal` 冗余列(GREEN/YELLOW/RED);写路径同步重算 + 手动重算端点兜底;项目列表/看板卡片直读冗余;短板聚合搬服务端(`GET /api/shortfall`)。
2. **体验修复**:看板「刷新」按钮 + 30s 自动轮询(websocket defer);铃铛未读数改模块级单例 store(全局唯一 30s 定时器 + focus 刷新),AppNav 挪根 layout 持久挂载;恢复 item 详情「编辑需求」入口。
3. **item 依赖**:blocks / blocked-by(`item_dependency` 表);详情「依赖」区块(增删);看板「⛔ 被阻塞」标识 + 过滤;**仅标识+过滤,不强制流转**;环检测。
4. **每日站会视图**:`/standup`,按人切换(默认我):昨日完成 / 今日名下 / 阻塞中,自然日窗口。
5. **检查清单**:`checklist_item`(text + done,随 item 级联删);详情区块 + 完成度 x/y。
6. **回收站**:item 软删(`deleted_at`);全查询过滤;回收站页恢复(连子树)/彻底删除;项目级删除 defer;无自动清理。

## User Stories

1. As a 管理者, I want 项目列表/看板直接显示可行性标记, so that 不等 N 次计算。
2. As a 管理者, I want 看板一键/自动刷新, so that 看到最新状态。
3. As a 用户, I want 铃铛未读数不多调接口, so that 系统干净。
4. As a 用户, I want 详情页能编辑需求, so that 需求变更有着落(修复回归)。
5. As a 管理者, I want 标记 item 间的阻塞关系, so that 先后有序。
6. As a 成员, I want 站会视图看昨天/今天/阻塞, so that 每天开机即知干什么。
7. As a 成员, I want item 内勾检查清单, so that 步骤性工作不漏。
8. As a 管理者, I want 误删的 item 能从回收站恢复, so that 手滑不事故。

## Implementation Decisions

- **V18 migration**:`item.feas_signal varchar(10)`、`project.feas_signal varchar(10)`(均可空,CHECK GREEN/YELLOW/RED;null = 无 item 未计算);`item_dependency`(item_id FK / depends_on_item_id FK,CHECK 两者不同,PK=(item_id, depends_on_item_id));`item.deleted_at timestamptz`;`checklist_item`(item FK cascade / text / done bool / position)
- **冗余语义**:`feas_signal` = **当前唯一登录成员(me)视角**的可行性信号(单真实用户产品,CONTEXT V2-Q2;多真实用户出现时改 per-member 读模型,defer)。无需求 item = GREEN(全满足);无 item = null。
- **重算范围与接线**(同步,事务内):
  - capability set/rm → **workspace 全项目**全 item + 各项目聚合(能力是 workspace 级)
  - requirement replace → 本 item + 本项目聚合
  - item create/delete/软删恢复 → 本项目聚合(item signal = GREEN/按需求)
  - 兜底:`POST /api/projects/{key}/recompute`(重算该项目 + 聚合)
- **聚合规则**:project.signal = RED 若任一 item RED;YELLOW 若任一 YELLOW;GREEN 若全部 GREEN;无 item = null。
- **列表/主页改造**:`GET /api/projects` 返回 `feasSignal`;新增 `GET /api/shortfall`(workspace 短板聚合,服务端算,替代首页 N 次调用);看板 `GET /api/projects/{key}/items` 每行带 `feasSignal`;首页卡片 chip 读冗余。
- **刷新**:`GET /api/projects/{key}/items` 照旧;前端「刷新」按钮 + `setInterval(30s)` 自动重拉(websocket defer)。
- **铃铛收敛**:`lib/notifications-store.ts` 模块级单例(count + 订阅 + 全局唯一 30s 定时器 + `window.focus` 刷新 + bump());AppNav 订阅显示;AppNav 挪进根 layout 的 Providers 内持久挂载;通知已读操作后 bump。
- **依赖**:详情「依赖」区块列出 depends_on(标题+状态)+ 增删;「⛔ 被阻塞」= 任一 depends_on 未终态;看板过滤项「被阻塞」;环检测:加依赖前沿父链查环(同树 ensureNoCycle 思路)。
- **站会**:`GET /api/standup?member=<memberId>` → { doneYesterday:[itemId…], today:[…], blocked:[…] };数据源 = item_activity(昨日自然日有 status_changed 且现非起点)+ assignee 非终态 + 依赖未清。
- **检查清单**:详情区块 + `x/y` 完成度;条目增删/勾选即时保存。
- **回收站**:`GET /api/recycle-bin`(deleted 非空按 deleted_at 倒序)/ `POST /api/recycle-bin/{id}/restore`(恢复子树)/ `DELETE /api/recycle-bin/{id}`(物理删,级联照旧);全列表类查询加 `deleted_at IS NULL` 过滤。

## Testing Decisions

黑盒照旧:signal 冗余随 capability/requirement 变更重算;recompute 兜底;shortfall 聚合;铃铛为前端行为(E2E);依赖增删/环 400/被阻塞标识;站会三组数据;checklist 增删勾;软删后全查询不可见/恢复可见/物理删真删。

## Out of Scope

Linear 同步(用户定为低优先级)· websocket 实时刷新 · 通知种子化 E2E · 拖拽改日期 · 到期提醒 · 多真实用户下的 per-member signal · 项目级软删。

## Further Notes

- CQRS 纯度让位给正确性:写路径同步重算,读路径零计算;recompute 端点仅兜底。
- Kaneo 参照:其 Notification/事件总线模式已够用,不引事件总线;`item_dependency` 对齐其 blocks 语义。
- 切票:S1 读模型(V18+重算+shortfall)→ S2 体验修复+收藏 → S3 依赖 → S4 站会 → S5 检查清单 → S6 回收站 → S7 E2E 收尾。
- 收藏(并入 S2):`favorite` 表(member FK / project FK,uk pair);导航 ⭐ 下拉 + 项目卡 ☆;**服务端存储**(重装/跨浏览器不丢)。
