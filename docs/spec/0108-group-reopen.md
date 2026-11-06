# 0108 — 组重开:timed 模板 completed→active 出边(reopen)(YPJ-157)

> 背景(2026-11-06):用户提出组 workflow 需要「从 Done 回到 In Progress」。语义定性已讨论定稿:**撤销一次内部收尾判断**,非「从终态回去」——timed(时间盒)收尾是内部判断,可撤销;release 的 released 是对外承诺,不可重开。设计方向已记入 YPJ-157 评论。

## 一、需求

### 1.1 行为

完成组(timed 类型,状态=completed/FINAL)详情页出现**低显著重开按钮**;点击 → 确认弹窗(明示「重开=撤销完成态,成员历史不变」)→ 确认后组状态迁移回 active,页面即时回到进行中形态(可拉入、进出边恢复)。

### 1.2 范围矩阵

| 类型模板 | completed 后出边 | 理由 |
|---|---|---|
| timed(时间盒) | + `reopen → active` | 内部收尾判断可撤销(误完成、返工、QA 打回) |
| release | 不变(released 终态无出边) | 对外承诺型终态,重开语义不成立 |
| 自定义三态循环 | 不变(completed 已有 `switch→planned`) | 已可回,不动 |

### 1.3 语义不变式

1. **重开 = 纯状态迁移**:组成员行(含历史行)一律不动——已完成组的历史行继续是历史;重开后该组视为非终态,其成员行重新参与归属替换(`replaceMembership` 的 live 过滤天然按组状态生效,零额外代码)。
2. item 自身状态不动(重开组≠重开 item)。
3. 与 YPJ-105 关闭护栏正交:护栏防「关闭时成员悬置」(处置流已解),不防「重开」。
4. 确认制:重开是可见状态变化,必须确认,不静默(按钮低显著+确认弹窗双闸)。

### 1.4 非目标

- completed→planned 的多级 backward(用户只要回 In Progress)
- release 重开、item 侧重开(item 已有 reopen 语义,正交)
- 重开审计留痕(access log 已覆盖;域级审计归 YPJ-154 权衡)

## 二、设计

### 2.1 现状实锤(接线式改动,模型已预留)

- `GroupActionFn.REOPEN(sourceType=FINAL, targetType=ACTIVE)` **已注册**(GroupWorkflowService.kt:24)——意图校验现成,applyAction 直接生效,零改
- UI 按钮渲染数据驱动(按当前态 transitions 生成,仅滤 backward)——completed 加出边后按钮自动出现,零改渲染
- 动作端点 `action()` 白名单即查边+迁移——零改

### 2.2 改动清单

| 触点 | 改动 |
|---|---|
| `GroupWorkflowService.template()` | timed 分支 completed 行:`Triple("completed", StatusType.FINAL, listOf(Edge("reopen", "active")))` |
| **V51 迁移**(persistence/migration) | `update group_status set transitions='[{"function":"reopen","target":"active"}]' where name='completed' and group_type_id in (select id from item_group_type where timed = true)`——release 的终态行名为 `released` 不命中,自定义循环模板 completed 已有 switch 出边不被覆盖(条件 name='completed' ∧ timed=true 双重收窄) |
| `sprint/[id]/page.tsx` | ①`actionLabel()` 加 reopen →「↺ 重开组」;②confirmFlow 扩 `"reopen"`:确认弹窗文案「重开将撤销完成态,回到进行中;成员与历史不变」;③`actionClick()` 接 reopen → `sprintAction(key, id, "reopen")`;④完成态说明文案微调:「…成员关系不可再变(可重开)」 |
| `lib/texts.ts` | `reopenBtn`、`reopenConfirm` 文案键 |
| CLI(`sprints move`) | 核对:completed 态 `move active` 的映射——若现走 backward 映射则补 reopen 映射(实现时核实,可能零改) |

### 2.3 否决的备选

- 复用 `switch` 函数名:语义不明,REOPEN 意图校验(FINAL→ACTIVE)才是护栏,否决
- completed→planned 直回:多退一步无场景,且 planned→active 还要再一步,否决
- release 同加重开:对外承诺语义不成立,否决

## 三、测试

### 3.1 单元(src/test)

- 模板断言:timed 类型 completed 态恰含 `reopen→active`;release 的 released 无出边;自定义循环 completed 出边不变

### 3.2 集成(src/integrationTest,extends AbstractApiTest)

1. 主链:建组→start→complete→`POST actions/reopen` → status=active;成员历史行未动(库断言)
2. 意图护栏:对 release 组(终态 released)发 reopen → 409(无出边)
3. 重开后归属恢复:重开后再 `PUT group-membership` 挂新 item 成功;complete 态期间同调用 → 409(「组已完成,不能指派」既有守卫)

### 3.3 手工(dev)

1. 完成一个组 → 详情页见「↺ 重开组」低显著按钮 → 点击 → 确认弹窗文案正确 → 确认 → 组回进行中,可拉入 item
2. release 组(released)无重开按钮
3. 重开后再完成 → 处置流照常(0106 不回归)
