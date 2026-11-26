# Spec 0172 — YPJ-280 转非起始态缺负责人 → 弹窗补指派(而非 409)

> 票:YPJ-280(V33 修补与基建,预估 2h)。分支 `feat/YPJ-280-assign-before-move-dialog`。

## 1. 需求

### 定位
后端「开工须有主」守卫(ItemService L516–523:START→非 START 且非 FINAL、未指派 → 409「开工前请先指派负责人」)在前端的体验是硬拒绝:用户改状态失败后需自行去别处指派再回来重试。本票在前端把这条 409 变成一步到位的补指派弹窗。

### 范围
- 看板页(`web/app/p/[key]/page.tsx` `moveTo`,拖拽/点列头迁移)命中 409 → 弹窗。
- 详情页(`web/app/p/[key]/i/[itemId]/page.tsx` `moveStatus`)命中 409 → 弹窗。
- 弹窗列出项目成员(复用 `AssignPopover` 的候选来源 `/api/items/{id}/candidates`,含可行性理由标注);选定后:**先 PUT assignee、成功后立即以原目标状态 PATCH 迁移**,两步串行,第二步失败原地报错不关窗。
- 顺带范围:弹窗提供「取消迁移」出口;不选人直接关闭 = 取消,状态不变。

### 非目标
- 后端不改:`UpdateItemRequest` 不加 `assigneeItemId`(见 §2 方案取舍,契约变更需用户拍板,本票默认不做)。
- 通知页 advance 入口不改(低频、已有原样报错;本票后如需再立票)。
- 表格视图无状态迁移入口,不涉及。

## 2. 方案取舍(摆给用户拍板,默认 A)

- **方案 A(默认,纯前端)**:409 命中后前端串行两调(PUT assignee → PATCH status)。改动只在 web,零契约变更,两调间隔毫秒级、实际无中间态风险。缺点:非严格单事务(第二步极端失败时票已被指派,可接受)。
- **方案 B(后端)**:`UpdateItemRequest` 增加可选 `assigneeItemId`,服务端同事务先指派再迁移守卫放行。严格原子,但属 REST 契约变更(宪法:契约变更非可逆决策,须用户拍板),且需动 api.http + 集成测试,超出 2h 预估。

**本票按方案 A 实施;如用户要 B,中途切换成本低。**

## 3. 详细设计

### 3.1 新组件 `web/components/AssignBeforeMoveDialog.tsx`

```
props: {
  open: boolean
  itemNumber: string            // 显示用
  targetStatusName: string      // 目标状态名
  candidates: AssignCandidate[] | null
  onPick: (memberId: string) => Promise<void>   // 组件内调;失败不关窗
  onCancel: () => void
}
```
- Dialog + List(MUI,沿 `AssignPopover` 的候选行样式:显示名 + 可行性标注);加载态 CircularProgress。
- 选中即触发 `onPick`;成功由调用方关窗(置 state null)。

### 3.2 触发点改造(两处同构)

`moveTo` / `moveStatus` catch 分支:`err instanceof ApiError && err.status === 409 && err.message.includes("指派")` → 拉候选、开弹窗;其余 409(如 WIP 满)维持原报错。判据用 message 包含「指派」而非新错误码(后端不改 = 无新 code;后端如未来加结构化 code 再切换,记 shortcut)。

状态迁移入口 × 行为真值表(spec 七规⑤):

| 前置状态 | 目标状态类型 | 当前 assignee | 行为 |
|---|---|---|---|
| START | 进行态(非 FINAL) | 已指派 | 直接 PATCH 迁移(现状) |
| START | 进行态(非 FINAL) | **未指派** | PATCH → 409 → **弹窗补指派 → 选人 → PUT assignee → PATCH 迁移**;取消则原地不动 |
| START | FINAL(任意) | 未指派 | 直接 PATCH(后端豁免:直迁终态不须有主) |
| 非起点 | 任意 | 任意 | 直接 PATCH(守卫不触发) |
| 弹窗内 | — | 选人后 PUT 成功、PATCH 失败 | 报错留在弹窗所在页 setError,不关窗(用户可重试或取消) |

### 3.3 否决的备选
- 迁移前预检(assignee 为空即先弹窗,不等 409):省一次失败请求,但前后端规则重复且后端豁免逻辑(直迁终态)要在前端复刻,漂移风险 > 一次 409 的代价。否决。
- 复用 closeAsk 弹窗结构塞指派语义:两件事(要理由/要人)形状不同,塞一起反而绕。独立小组件。

## 4. 操作流程(用户视角)

1. 看板拖未指派票从「To Do」到「In Progress」→ 弹窗「开工前请先指派负责人」列出成员 → 点选 → 票落列。
2. 详情页状态下拉选进行态,同样弹窗 → 选人 → 状态更新,负责人栏同步。
3. 弹窗点取消/关闭 → 票停在原列,无副作用。

```
┌─ 看板(改前)────────────────────────────┐
│ To Do          In Progress              │
│ ┌──────────┐                            │
│ │ YPJ-281  │   ← 拖到右列               │
│ │ 👤 未指派 │                            │
│ └──────────┘                            │
└─────────────────────────────────────────┘
              ↓ 409「开工前请先指派负责人」
        ┌─ 弹窗 ────────────────┐
        │ 转入「In Progress」前请 │
        │ 先指派负责人:          │
        │  ● 张三   🟢 匹配       │
        │  ○ 李四   🟡 差距 1     │
        │         [取消]          │
        └───────────────────────┘
              ↓ 选人
│ To Do          In Progress              │
│                ┌──────────┐             │
│                │ YPJ-281  │ 👤 张三      │
│                └──────────┘             │
```

## 5. 手工测试用例(落档 `docs/testing/manual-test-cases.md`,dev 3001)

- [ ] 看板:未指派票 START→进行态,弹窗出现;选人后票落列且负责人已设。
- [ ] 看板:弹窗取消 → 票留原列。
- [ ] 详情页:同路径弹窗、选人、状态与负责人双更新。
- [ ] 已指派票迁移:无弹窗(回归)。
- [ ] 未指派票直迁终态(如 Done):无弹窗(豁免回归)。
- [ ] 409 非指派类(WIP 满):仍走原报错,不弹指派窗。
- [ ] e2e:看板弹窗流(可后补,手工验收先行)。

## 6. 验证边界

agent 侧:`npm run format` + `tsc` 绿,就地 commit。用户侧:dev 3001 手工用例过 → PR。

## 7. As-built(2026-11-26 完工)

- 实现与 §3 一致,方案 A(纯前端两调)。改动:`web/components/AssignBeforeMoveDialog.tsx`(新)、`web/app/p/[key]/page.tsx`、`web/app/p/[key]/i/[itemId]/page.tsx`、`web/lib/texts.ts`。后端零改动。
- 409 判据确为 `err.status === 409 && message.includes("指派")`;候选行直接点行即选(非按钮式)。
- 验收中发现弹窗无显式取消按钮(spec §4 图本就画了 [取消],实现遗漏)→ PR #265 补 `DialogActions` 取消按钮,对齐 CloseReasonDialog 双轨惯例。
- 手工用例全部通过(含豁免/回归/WIP 满 409 不误弹);e2e 全过。PR #264 + #265 已合。
