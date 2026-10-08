# 0013 · V16 — 分配体验上浮 · GitHub 按需拉取 · 工时建议(As-built)

> 状态:已交付(YPJ-6/7/8,PR #77/#78/#80 合并)。本 spec 为事后补录——V16 开工前未立 spec 文件,自本版起 spec 为版本必须项(见 AGENTS 工作流)。

## 背景

V15 完成切换收尾后,三个日常使用痛点:指派入口埋得深(要进详情页);GitHub 进度只能等 5 分钟轮询"送"上来;工时记录与实际编码时间脱节,报工全凭回忆。

## S1 分配体验上浮(YPJ-6,PR #77)

三轮用户反馈迭代:

- **入口上浮**:看板卡片 / sprint 卡片 / sprint·table / backlog 表格的 assignee 显示位可点 → 共享 `AssignPopover`(候选分色行 + 当前人标注 + 未足确认 Dialog);无 assignee 半透明「未指派」占位
- **详情页七区块单页直出**(页签全部移除):评论 → 活动日志 → 判定 → 需求 → 依赖+清单 → 工时 → GitHub(顺序为用户指定);分配入口在头部状态下拉之前;取消指派 = chip × 或 Popover 内按钮

## S2 GitHub 按需拉取(YPJ-7,PR #78)

- `yz log YPJ-N` + 详情 GitHub 区块「加载提交历史」
- `GET /api/items/{id}/commits`:只读直查 GitHub(不落库 / 不写活动 / 不依赖轮询开关);无 PAT / 无挂载 → 400 可操作报错
- 轮询降级为兜底(`yangzhou.github.poll-enabled`,默认 5min)

## S3 工时建议(YPJ-8,PR #80)

- `GET /api/items/{id}/time-entry-suggestions`:复用 S2 按需拉取,按分支把 commit 时间轴成段——**≥2 个带时间戳 commit 才成段(首 commit ≈ 开工,末 commit ≈ 收工)**;只出草稿,不落账
- TimeEntry create 补**起止模式**:`startedAt`/`endedAt`(ISO-8601)真正落库(V8 时为死参数,只参与互斥校验);与 minutes 互斥、需成对、end>start;minutes 读时按段折算
- 详情页工时区块「从 GitHub 生成建议」→ 草稿行「落账」→ 走起止补录写 TimeEntry,**机器不自动写**;空态文案:无关联分支或 commit 不足以成段

## As-built 裁决

- 单 commit 分支不出草稿:票面「首/末」在单 commit 时无解,取保守读法,人可手动补录
- 草稿不提供行内编辑;改数走既有手动补录
- 工时建议仅做 UI(票面 UI/CLI 二选一);CLI 侧 `yz log` 已可看 commit 时间轴

## 测试

`TimeSuggestionApiTest`(fake gateway,4 例:乱序日期取首尾 / 单 commit 不成段 / 无分支空态 / 无 PAT 400);`TimeEntryApiTest` +2(起止真实落库分钟折算、不成对/end≤start/互斥/坏格式 400);E2E 不覆盖(外部 GitHub 依赖,与 S2 同)。
