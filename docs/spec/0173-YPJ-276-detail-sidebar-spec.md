# Spec 0173 — YPJ-276 详情页右 sidebar:易变信息仪表

> 票:YPJ-276(V33 修补与基建,预估 5h)。分支 `feat/YPJ-276-detail-sidebar`。设计经 grill 两轮收敛(2026-11-26,7+7 问全拍板)。

## 1. 需求

### 定位
详情页重构为「主列 = 不易变信息(标题/描述),右栏 = 易变信息仪表(可随时观察信息当前状态)」——Linear/Jira 式 inspector 布局。原 desc 所列参数经 grill 扩展:凡录入型元数据全部入右栏,头部收敛为纯导航。

### 范围(web only,后端零改动)
- **迁右栏·录入**:状态下拉、负责人(AssignPopover 入口)、计划起止(开始日期/开始时刻/截止日期/截止时刻)、预估 EstimateField、priority、sprint 组籍、milestones 挂接 chips、release、type
- **迁右栏·只读**:创建/实际开始/实际完成(132 派生)、超期/临期/阻塞 chips、可行性 SignalChip(与上述 chips 组成「信号区」)
- **迁右栏·汇总**:工时 = 总工时 + 计时中 chip + 「工时明细 →」跳转 tab;**右栏零工时录入**
- **随迁**:保存按钮 + saved 提示 + sprintHint(保存载荷不变,仍批量 PATCH 含主列 title/description)
- **留头部**:back 链接、number chip、父票链接、prev/next 导航
- **主列**:标题、描述、删除按钮(描述下方,原样)

### 非目标
- 即时保存(→ YPJ-282,backlog)
- 工时 tab 结构调整、label 概念(→ YPJ-281 探索票)、externalRef 展示(拍板不做)、Tabs 增删

## 2. 详细设计

### 2.1 组件结构
```
page.tsx(状态与回调全部留 page,新组件只收 props——沿 TimeLogTab 模式)
├── ItemHeader.tsx(瘦身后:back / parentNumber 链 / number chip / prev-next 导航)
├── 主列:title、description、删除按钮
├── Tabs + 各 Tab(全不动)
└── ItemSidebar.tsx(新,web/app/p/[key]/i/[itemId]/components/)
    props = 各字段值 + 回调(onMoveStatus/onAssign/onChange*/onSave/timelineLocked/estInvalid/...)
```
- 右栏宽 **280**(暂定,用户验收后可调;现有 `alignItems: "flex-start"` 布局保留)
- 分区从上到下:状态 → 负责人 → 计划起止 → 预估 → priority → sprint → milestones → release → type → 保存行 → 信号区 → 生命周期 → 工时汇总
- 工时跳转:`setTab(t.item.detailTabs.indexOf("工时"))`,不硬编码下标

### 2.2 逻辑迁移零语义变化
- `moveStatus`(含 280 弹窗流)、`assign/clear`、`applySprint`、`toggleMilestone`、`saveBasics`(estInvalid 守卫、dirty/lastEdited 跟踪)、日期 dirty 全部原样,仅控件换位
- ⚠ 假设待复核:终态票的 closeReason 编辑 chip(现头部)随状态迁右栏状态区下方——grill 未显式覆盖,按「易变信息入右栏」原则归入,请用户验收时确认

### 2.3 e2e 影响(预判)
现 selector 多为文本/role/定位无关(`getByText("分配")`、`👤` chip、`data-testid=item-milestone-chip`、`ariaLabel="item 状态"`),位置迁移预计不破;涉及 spec:`item-assign`、`item-type`、`milestone`、`epic`、`item-prev-next`。编码后全量 e2e 回归,红了改 selector 不改断言语义。

## 3. 操作流程(状态×动作真值表)

| 前置状态 | 动作 | 行为 |
|---|---|---|
| 普通票(非 FINAL) | 右栏状态下拉选目标 | 原 moveStatus 流(理由弹窗/280 补指派弹窗逻辑不变) |
| START ∧ 未指派 | 右栏迁进行态 | 280 补指派弹窗 → 选人 → 指派+迁移 |
| FINAL 票 | 查看右栏状态区 | closeReason chip 显示,点击开编辑理由弹窗 |
| 父票持有子时间线(timelineLocked) | 右栏日期控件 | 四框禁用 + 「已有子项,时间线由子项汇总」提示 |
| estRequired ∧ 预估空 | 保存按钮 | disabled(estInvalid) |
| 改主列标题 + 改右栏预估 | 保存 | 一次批量 PATCH,两处均持久(载荷不变) |
| sprint 下拉 | 选 completed sprint | 选项 disabled;清空 = 移出 |
| 计时器运行中 | 右栏工时区 | 「计时中 mm:ss」chip;总工时与 tab 一致 |
| 点「工时明细 →」 | — | 落工时 tab |

## 4. 页面示意图(ASCII,贴合实况)

```
┌──────────────────────────────────────────────────────────────┐
│ ‹ 项目 / 看板(返回)                    [YPJ-123] ‹ › 3/17   │ ← 头部:导航
├──────────────────────────────────────────┬───────────────────┤
│ [标题____________]                       │ 状态   [In Progress▾]│
│ [描述____________________________]       │ 负责人 👤me × [分配] │
│                                          │ ── 计划起止 ──      │
│                                          │ 开始 [11-26][09:00] │
│                                          │ 截止 [11-28][17:00] │
│                                          │ ── 预估 ──          │
│                                          │ [5][hour]           │
│                                          │ ── priority ── [P1▾]│
│                                          │ ── sprint ── [V33▾] │
│                                          │ ── milestones ──    │
│                                          │ [M1][M2]            │
│                                          │ ── release ── [v1▾] │
│                                          │ ── 类型 ── [task▾]  │
│ (删除)                                   │ [保存] 已保存 ✓提示  │
│                                          │ ── 信号 ──          │
│ ── Tab:评论│日志│判定│需求│依赖+清单│     │ 🟢 已超期 临期 阻塞  │
│        工时│GitHub│属性 ──                │ ── 生命周期 ──       │
│ (tab 内容)                               │ 创建 11-24 09:00    │
│                                          │ 实际开始 11-26 10:11│
│                                          │ 实际完成 —          │
│                                          │ ── 工时 ──          │
│                                          │ 计 1h23m [计时中…]  │
│                                          │ 工时明细 →          │
└──────────────────────────────────────────┴───────────────────┘
```

## 5. 手工测试用例(dev 3001,落档 `docs/testing/manual-test-cases.md`)

- [ ] 右栏各录入件显示正常、可编辑;保存后刷新持久
- [ ] 改主列标题 + 改右栏预估 → 保存 → 两处均持久(批量保存跨栏)
- [ ] estRequired 项目:清空预估 → 保存禁用;填回恢复
- [ ] timelineLocked:父票四日期框禁用 + 提示文案
- [ ] 终态票:右栏 closeReason chip 显示,点击编辑理由
- [ ] 右栏状态下拉迁移未指派票 → 280 弹窗流正常
- [ ] 负责人:分配/清除/未足项确认弹窗全路径
- [ ] sprint:挂接/移出/completed 选项禁用;sprintHint 提示随保存行
- [ ] milestone 挂接/摘除;release 挂接/清空
- [ ] 工时汇总与 tab 总工时一致;计时中 chip;「工时明细→」落工时 tab
- [ ] 头部只剩 导航/number/父票链/prev-next;主列剩 标题+描述+删除
- [ ] e2e 全量回归绿

## 6. 开放决策清单

- 右栏宽 280 暂定,用户验收实感后可调(spec 2.1)
- 终态 closeReason chip 归右栏状态区(2.2 假设,待复核)
- As-built 完工后补
