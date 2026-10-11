# 0018 — V17-5 dropdown 替代组件:统一选择控件

> 票:YPJ-18。MUI Select / TextField-select「框框」观感 low,统一自定义选择组件取代;依赖 V17-4 theme 基线。

## 1. 需求(详细)

### 1.1 背景

- 现状 10 文件约 22 处选择控件:MUI `Select size=small`(MenuItem 子元素)+ `TextField select`,outlined 框框 + 内嵌箭头,与 YPJ-17 定稿的「无边框底色控件」语言不符。
- e2e helpers(`openSelect`/`selectOption`,8 处用例依赖)建立在 ARIA 角色链 `combobox(名称=label)→ listbox → option` 上——**新组件必须保持同角色链,helpers 零改动**。

### 1.2 组件 PRD 简版

**形态**
- 触发器:theme 底色控件语言——field 底色、无边框、圆角 8,右侧 chevron;有值且 clearable 时右侧为 ×(清除优先显示)。label 在上方小字,无 label 用 placeholder 灰字。
- 弹层:触发器下对齐的纸白描边圆角浮层;选中项打勾 + field 底色;hover 浅底;禁用项灰字。
- 尺寸:small(行内/表格用)与默认两档;fullWidth 可选。
- **仅单选**(全库核查零 multiple)。

**键盘操作**
- 触发器:Enter/Space/ArrowDown 打开;点击打开。
- 弹层:↑↓ 移动高亮(打开时定位当前选中项),Enter 选中并关闭,Esc 关闭,Tab 走焦关闭;字母跳选(typeahead,MenuList 内建)。

**清除动作**
- `clearable` 且有值时显示 ×;点击清空(不打开弹层);适合「可空」字段(如指派、sprint)。

**非目标**
- 不做多选、搜索过滤输入、选项分组、虚拟滚动(选项量级 <50)。
- 不动 MUI Dialog 淡出期吞点击守卫(e2e fixtures 照旧)。

### 1.3 目标

1. 新组件 `web/components/Dropdown.tsx` + PRD 简版本文档化(spec 即 PRD)。
2. **分阶段替换**:A 阶段先替换样板页(item 详情,9 处)→ 截图交用户过目;B 阶段(用户点头)替换其余 9 文件。
3. MUI 对话框淡出期守卫照旧;E2E 全量本地绿。

### 1.4 验收(票内清单)

- [ ] 组件 PRD 简版(形态、键盘操作、清除动作)+ 样板页截图,用户过目
- [ ] 组件实现(替代 MUI Select / TextField select)
- [ ] 替换 10 个使用文件:GithubSettingsPanel / WorkflowEditor / 首页 / members / standup / capabilities / attributes / table / board / item 详情
- [ ] MUI 对话框淡出期(~200ms)吞点击守卫照旧
- [ ] E2E 全量本地绿

## 2. 详细设计(代码级修改计划)

### 2.1 组件 API(`web/components/Dropdown.tsx` 新)

```tsx
<Dropdown
  label?: string            // 无则 placeholder 态
  placeholder?: string      // 空值显示(WorkflowEditor displayEmpty 场景)
  value: string             // 受控单值
  onChange: (v: string) => void   // 直传值,不走 event(替换点签名更干净)
  options: { value, label, disabled? }[]
  clearable?: boolean       // 有值时显示 ×,点击 onChange("")
  size?: "small"
  fullWidth?: boolean
  disabled?: boolean
/>
```

### 2.2 ARIA 兼容(e2e 硬约束)

- 触发器 = 只读 TextField:`inputProps={{ role: "combobox", "aria-expanded": open, "aria-haspopup": "listbox", readOnly: true }}`,label 经 htmlFor 关联 → `getByRole("combobox", { name: label })` 命中。
- 弹层 = MUI Menu(slotProps list role `listbox`)+ MenuItem `role="option"` → `openSelect`/`selectOption` 的等待/点击/回读断言全部零改动。
- 焦点管理、定位翻转、滚动锁定复用 MUI Menu,自写代码只管触发器样式与键盘开关。

### 2.3 替换清单(10 文件 22 处,机械改写:children → options 数组,`e.target.value` → `v`)

| 文件 | 处数 |
|---|---|
| `web/app/p/[key]/i/[itemId]/page.tsx`(样板页,A 阶段) | 9 |
| `web/components/WorkflowEditor.tsx`(B) | 4 |
| `web/app/attributes/page.tsx`(B) | 2 |
| GithubSettingsPanel / 首页 / members / standup / capabilities / table / board(各 B) | 各 1 |
| **小计** | **22** |

- displayEmpty 场景(WorkflowEditor 起止状态)→ placeholder + 空串 option(label 正常可选,disabled 语义照旧)。
- 「none/unrated」等空值语义项 → 普通选项保留;可空字段落 `clearable` 由用户走查定。

### 2.4 分阶段与不动清单

- **A 阶段**:组件 + 样板页(item 详情 9 处)→ `node scripts/shots.mjs` 截 item 页 → 用户过目 PRD + 样板。
- **B 阶段**(点头后):其余 9 文件机械替换 → E2E 全量 → PR。
- 不动:e2e helpers/fixtures(角色链兼容是设计约束)、MUI Dialog 及淡出守卫、页面其余布局、后端。

### 2.5 风险与对策

| 风险 | 对策 |
|---|---|
| e2e 角色链断裂 | 2.2 为硬约束;A 阶段先跑 item 相关 spec 验证 helpers 兼容,再进 B |
| 触发器 readonly TextField 被表单 Enter 误提交 | onKeyDown 拦截处理;e2e 全量兜底 |
| 22 处机械替换漏网 | 替换后 `grep "<Select\b" web/` 归零自查 + MUI Select import 清理 |
| 键盘焦点陷阱(菜单内 Tab) | MenuList 内建循环;TC 键盘清单逐项验 |

## 3. 手工测试用例(用户执行)

- **TC1 PRD + 样板过目**(A 阶段末):item 详情页截图(含触发器静止/聚焦/弹层展开/清除四态说明)→ 用户确认形态与交互。
- **TC2 键盘走查**:Tab 入焦 → Enter/↓ 开 → ↑↓ 移 → Enter 选中;Esc 关;字母跳选;× 清除后 placeholder 回归。
- **TC3 E2E 全量本地绿**:web/ 下标准全量跑法(含 8 处 openSelect/selectOption 用例)。

## As-built

- **落地**:Dropdown 组件(`web/components/Dropdown.tsx`)+ 10 文件 **21 处**全替换(A 样板 item 页 8 + B 阶段 13;spec 清点 22 系误计,以本条为准)。
- **MUI v7 坑(排障实录)**:原生 input 属性必须走 `slotProps.htmlInput`(`slotProps.input.inputProps` 为 deprecated 路径,role 落错元素 → e2e 角色链断裂、click 撞菜单 backdrop 超时)。
- **e2e 适配三处**:①值断言 `toHaveText` → `toHaveValue`(Dropdown 触发器是原生 input);②清除动作走 × 按钮(可访问名 `清除 <字段>` 带字段名保证页面唯一),sprint/release 的「无」选项被 clearable 取代;③helpers 与 sprint.spec 的 combobox locator 加 `exact: true`(看板「sprint 过滤」子串撞「Sprint」,过渡期随机炸)。
- helpers `selectOption` 回读双轨桥(input→toHaveValue / div→toHaveText);B 全量替换后 div 分支已无消费者,保留兜底不删。
- MUI 对话框淡出期(~200ms)吞点击守卫照旧 ✓(未动 Dialog/fixture)。
- TC3:E2E 全量 **36 绿 + 1 偶发**(API 预置 `PUT /api/members/{id}/capabilities: 500`,单跑该 spec 两轮全绿——后端并发缺陷,与 spec 0017 记录的首跑偶发同族;归因候选票,需抓 8080 侧堆栈)。
- 样板过目 = 用户 HMR 实测 + 直接跑相关 spec(替代截图走查);PRD 简版即本 spec §1.2,清除动作演进为「× 按钮」形态。

