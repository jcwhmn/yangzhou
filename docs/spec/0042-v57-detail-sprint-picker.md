# 0042 — V20 / YPJ-57:Item 详情页 sprint 选择器(active 置顶 + 状态 chip)

> 需求源:YPJ-57(2026-10-16 拍板建票,用户 btw 提出);spec 0037 §五 Flow Metrics 尾巴 XS 票。分支 `task/YPJ-57`。

## 一、需求

### 背景
Item 详情页把 item 归入 sprint(与 `PUT /api/items/{itemId}/sprint` 同语境)时,sprint 下拉不分状态:active(进行中)sprint 混在 planned/已关单里,不易辨认、易选错。

### 范围
1. sprint 下拉选项 **active 置顶**,排序:active → planned → completed。
2. 每个 option 带**状态 chip**(进行中/规划中/已完成),颜色沿用 sprint 详情页惯例:active=primary、planned=warning、completed=default。
3. 现有过滤/禁用语义保持:completed 不在选项中,当前已归属的 sprint 例外(可见、禁用);可清空(clearable)。

### 非目标
- Dropdown 其它使用点(优先级、release 等)不加 chip(能力通用,但只有 sprint 用)。
- sprint 列表/详情页、看板弹选 Dialog 不动。
- 后端零改动(状态字段已有)。

### 验收(票面)
- [ ] sprint 选择器 active 置顶、状态可见区分
- [ ] 开工先出三件套(spec 编号顺延;0041 归 YPJ-54)——本文件

## 二、详细设计

### 1. `web/components/Dropdown.tsx`:DropdownOption 扩展
- `DropdownOption` 增可选 `chip?: { label: string; color: "primary" | "warning" | "default" }`。
- MenuItem 内 label 后渲染 `<Chip size="small" label={o.chip.label} color={o.chip.color} sx={{ ml: 1 }} />`(CheckIcon 前)。
- 不改 e2e 硬约定(combobox/listbox/option 角色);chip 是装饰性 span,不影响 selectOption 文案定位(label 文本仍是第一子内容)。风险:e2e 用全量文本匹配 option 时会带上 chip 文案——本页无 sprint 下拉的 e2e 断言,无存量破坏。

### 2. `web/app/p/[key]/i/[itemId]/page.tsx`:sprint Dropdown
- `SprintLite` 已含 `status`。状态文案沿用 `t.sprintStatus`(texts.ts 已有:规划中/进行中/已完成)。
- options 构造改为:
  1. 过滤:`s.status !== "completed" || s.sprintId === item.sprintId`(现状保持);
  2. 排序:active → planned → completed(稳定排序,同级按现有 API 顺序);
  3. map 出 `chip: { label: t.sprintStatus[s.status], color: s.status === "active" ? "primary" : s.status === "completed" ? "default" : "warning" }`;completed(仅当前归属)chip 同 default。
- 否决备选:①在 Dropdown 加通用 ReactNode 插槽——过度抽象,只有一处用;②option label 里拼「[进行中]」文本——不像 chip,视觉不达标。

### 3. 测试设计
- 后端零改动,无后端测试。
- E2E:不加新用例(XS,纯展示排序;既有 e2e 不回归即可)。手工用例见下。

## 三、手工测试用例(用户 build/debug 执行)

1. 详情页 sprint 下拉:进行中的 sprint 排最上,每个选项右侧有状态 chip(进行中=蓝/规划中=黄/已完成=灰)。
2. 已关单 sprint 不出现在下拉;当前 item 已归属关单 sprint 时该选项可见、禁用、chip 灰。
3. 选定/清空行为照旧;「sprintName ↗」链接照旧。
4. 优先级/release 下拉无 chip(通用能力未被误扩散)。
5. 回归:`npx playwright test --grep "@smoke"` 绿。

## As-built(2026-10-16,PR #112 合入)

- **按票交付**:DropdownOption 可选 chip(MenuItem 内 outlined 小 chip,CheckIcon 前);sprint 下拉排序 active→planned→completed + 状态 chip(文案 `t.projectNav.sprintStatus`,key 断言 `as keyof typeof`);completed 过滤/当前归属禁用/clearable 语义原样。偏差:`chip` 补了实现选型 `variant="outlined"`(spec 未定 variant,选 outlined 避免实底色喧宾)。
- **实现期记坑**:filter 返回新数组,`.sort()` 原地排序不污染组件 state(顺序副作用收敛在一次渲染的 options 构造内);`sprintStatus` 索引需显式 keyof 断言(SprintLite.status 是 string)。
- 验证:`tsc --noEmit` 干净(e2e/release.spec.ts 3 处存量类型错,stash 对照确认与本次无关);全量 E2E + 手工用例(spec §三)用户执行通过;PR #112。
