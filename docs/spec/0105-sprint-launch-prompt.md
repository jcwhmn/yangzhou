# 0105 — sprint 拉起弹窗:无活动 sprint 时建 item / 挂 planned 的激活提示(YPJ-104)

> 票面(2026-10-27 拍板):「先激活下一个 sprint,再关当前」已支持;缺的是无活动 sprint 时的便捷拉起入口。两个触发点弹窗提示,确认后一键 PATCH active;取消维持现状。非自动迁移。不改 API。

## 一、需求

### 触发点①:board 降级态建 item
- 条件:看板无 active sprint(activeSprints.length === 0)且项目存在 planned sprint;建 item 提交成功后
- 弹窗提示是否拉起(激活)planned sprint;取消 → 维持现状(sprint 仍 planned,item 落 backlog)

### 触发点②:挂 planned sprint
- 条件:详情页把 item 挂到 **planned** sprint 成功,且项目当前无 active sprint(挂 active / 移出 null 不弹)
- 弹窗提示是否顺带激活该 sprint;确认 → sprint 变 active,**item 随组立即可见于看板**(membership 不变,组状态决定可见性;无需任何迁移)

### 范围与非目标
- 范围:仅 web 交互;激活复用既有 `sprintAction(key, id, "start")`;API 零改动
- 非目标(票面拍板):存量/遗留 item 自动迁移;backlog 页(无挂 sprint 入口,非触发点);仅 planned=1 之外的特殊化

### ⚠ 品味拍板点(触发点①确认后的 item 去向,两案请拍板)
刚建的 item 已落 backlog(无 sprintId);确认激活后:
- **方案A(严格票面「一键 PATCH active」)**:只激活 sprint。看板收窄到该 sprint → 刚建的 item 从看板消失,需去 backlog 查看。文案须明说,否则「建完就不见了」。
- **方案B(推荐)**:激活 sprint **并把刚建的 item 挂入该 sprint**。item 立即可见于看板,操作连贯。「自动迁移」非目标针对存量 item;本次刚建的 item 是当前操作对象,不属遗留迁移。
- 共同点:多 planned sprint 时弹窗列出全部供选(与多 active 的「放入哪个 sprint」弹窗同构);取消均维持现状。

## 二、详细设计

### `app/p/[key]/page.tsx`(触发点①)
- `handleSubmit`:现有分支不动;`createItem` 成功且 `activeSprints.length === 0 && sprints.some(planned)` → 记录 `launchState`(含刚建 itemId),打开 `launchOpen`
- 新 Dialog(结构照既有 `sprintPickOpen` 弹窗):
  - 单 planned:文案「项目还没有活动 sprint——要激活 planned sprint「{name}」吗?」+ 确认(激活)/取消
  - 多 planned:列表选择「要激活哪个 sprint?」+ 确认
- 确认动作:`sprintAction(key, pick, "start")` → 方案B再加 `assignSprint(key, 新itemId, pick)` → `load()`;失败走既有 `setError`
- 弹窗期间 item 已持久化;关闭不清 title

### `app/p/[key]/i/[itemId]/page.tsx`(触发点②)
- `applySprint(sprintId)` 成功后:`target = sprints.find(s.sprintId === sprintId)`;`target?.status === "planned" && !sprints.some(s => s.status === "active")` → 打开确认弹窗(单 sprint,无需选择)
- 确认:`sprintAction(key, sprintId, "start")` → 重拉 item + sprints(sprint 状态标注更新;item 无需迁移)
- 取消:什么都不做(挂载已生效,仍 planned)

### 文案
- `texts.ts`:`board.launchTitle` / `board.launchBody` / `board.launchConfirm` / `board.launchPickTitle`;`item.launchAsk` / `item.launchConfirm`。中文,外置。

### e2e(`web/e2e/sprint-launch.spec.ts`,新文件)
1. `@smoke`:fixture 独享项目;API 建一个 planned sprint(level-1 类型)→ board 输入标题建 item → 断言弹窗出现 → 确认 → 断言 sprint 段/看板出现 active(方案B再断言新建 item 在看板可见)
2. 触发点②:API 建 planned sprint + item → 详情页 Dropdown 挂 planned → 弹窗 → 确认 → 断言 sprint 标注变 active
3. 取消路径:弹窗取消 → sprint 仍 planned(board 提示条仍在)
- 弹窗淡出期注意 `toBeHidden()` 后再点下层(MUI 惯例)

## 三、手工测试用例(用户 build/debug 阶段)

1. **回归**:项目无 planned sprint 时建 item → 无弹窗,行为与现在完全一致
2. **触发点①·取消**:建 planned sprint → 看板建 item → 弹窗 → 取消 → sprint 仍 planned,item 在 backlog
3. **触发点①·确认**:再建一个 item → 弹窗 → 确认 → sprint 变 active;方案B:刚建 item 在看板可见 / 方案A:在 backlog
4. **触发点②·确认**:详情页挂 planned sprint → 弹窗 → 确认 → sprint active,item 随组在看板可见
5. **触发点②·取消**:挂 planned → 弹窗 → 取消 → 挂载生效(sprint 标注 planned),sprint 未激活
6. **不弹场景**:挂 active sprint;移出 sprint;有 active sprint 时建 item——均无弹窗

## 增量(2026-11-06,手工验证中发现):看板建 item 必进看板

> 用户重申既有拍板:在看板建的 item 必进看板,同时参考 sprint 状态决定去向(有 active → 进 active sprint;无 active → 不挂 sprint 但看板可见)。

- **根因**:0098(2026-11-05)改了 board 降级分支为「全量 − backlog 桶」,前端 board 建item 还是 0098 之前世界的语义(「不带 sprintId 落 backlog,与看板全量显示自洽」注释)→ 看板建的 item 隐身进 Backlog、看板不可见。T104Z(预置无组项目)实测照亮。
- **修复(前端单点,后端零改动)**:board 建item 无 sprintId 分支,建成功后 `PUT /api/items/{id}/board-membership {onBoard:true}`(0099 端点;无 active 时后端只置标志不挂组,实锤 ItemGroupService.kt:431-453)。与 0098 不变量自洽:onBoard=true 被 backlog 谓词排除 → **board ∩ backlog = ∅ 仍成立**,item 在看板可见、Backlog 页不可见。
- **文案对齐 0098 端点语义**(0101 案B 遗留失真):board.noGroups「以下显示全部非终态 item」→「以下显示不在 Backlog 中的 item」(与 noActiveSprint 同口径)。
- **0105 弹窗语义随之更顺**:触发点①「暂不」→ item 仍 onBoard 在看板可见(不再隐身);「激活」→ 挂入 sprint。触发点②不受影响(挂 sprint 的 item 天然看板可见)。
- **e2e**:sprint-launch 用例 2 断言改看板可见;新增无组项目用例(建item 不弹 + onBoard 可见 + Backlog 不可见)。
- **欠账提示(另票,不扩本票 scope)**:0098 增补的「根页无 active 重定向 Backlog」在修订二已撤(根页恒看板),但看板降级视图当前无显式「拉入」提示,Backlog 页的拉入入口承担此职——观感另行观察。
