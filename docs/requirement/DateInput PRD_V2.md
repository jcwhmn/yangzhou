# DateInput PRD — 分段日期输入（MM/DD/YYYY）

## 1. 背景与定位

DateInput 是用于 Web 表单的自研日期输入控件，用于替代原生 `<input type="date">`。

目标：

* 提供比原生 date input 更可控、更一致的视觉和交互。
* 支持快速键盘录入，不要求用户每次完整输入年月日。
* 以当天日期作为默认上下文，用户只需输入自己需要修改的日期部分。
* 支持键盘、鼠标和月历三种主要输入方式。
* 对外保持简单的日期值契约：**空值或有效 ISO 日期**。

DateInput 与 Dropdown 同属 YPJ 自研控件族，沿用统一的 field/theme 视觉语言。

---

# 2. 形态

## 2.1 结构

DateInput 对用户表现为三个日期段：

```text
MM / DD / YYYY
```

共 8 个数字位：

```text
Month  2 digits
Day    2 digits
Year   4 digits
```

交互语义以 **3 个 segment** 为单位；实现层可以使用 **8 digit cursor model**。

三个 segment：

1. Month
2. Day
3. Year

数字输入均为真实 input，使用 `inputMode="numeric"`。

---

## 2.2 Label / ARIA

* 支持可见 label。
* 无可见 label 时支持 `ariaLabel`。
* 外层使用 `role="group"`。
* Month / Day / Year 分别具有明确的可访问名称。
* Calendar、Clear 按钮必须具有明确的 accessible name。

---

## 2.3 辅助按钮

右侧提供：

* 📅 Calendar：打开日期选择月历。
* × Clear：有值时显示，用于清空日期。

Clear 的 accessible name：

```text
清除 <字段名>
```

---

## 2.4 尺寸

支持两档：

* `small`：行内场景
* 默认：Dialog / Form 场景

宽度由调用方控制。

---

# 3. Value 契约

## 3.1 对外 Value

DateInput 对外只有两种状态：

```text
""              空值
yyyy-MM-dd      有效日期
```

例如：

```text
""
"2026-10-12"
```

不对外暴露不完整或非法日期。

---

## 3.2 受控模式

```ts
value: string
onChange(value: string): void
```

其中：

```text
value = "" | yyyy-MM-dd
```

`onChange` 只在以下情况触发：

1. 用户完成一次有效日期输入并提交。
2. 用户通过 Calendar 选择有效日期。
3. 用户点击 Clear 清空。

编辑过程不会通过 `onChange` 污染外部表单值。

---

## 3.3 内部编辑

DateInput 内部允许存在编辑中的输入，但这些内容属于内部 draft，不属于对外 value。

核心原则：

> **对外始终只有“空”或“有效日期”；编辑中的内容只有在形成有效日期后才提交。**

Esc 可以放弃当前编辑，恢复最近一次已提交的 value。

---

# 4. 默认日期与快速输入

## 4.1 Focus 时不自动产生 Value

当 DateInput 当前为空时，获得 focus：

> **当天日期作为 placeholder / 输入参考，不作为实际 value。**

例如当天为：

```text
10/12/2026
```

空控件获得 focus 后显示：

```text
10 / 12 / 2026
```

但实际：

```text
value = ""
```

如果用户没有进行有效输入就离开控件：

```text
value = ""
```

不会触发 `onChange`。

---

## 4.2 当天作为输入基准

当用户开始输入时：

> **未被用户修改的日期段，以当天日期作为默认值。**

例如当天：

```text
10/12/2026
```

用户只输入：

```text
15
```

表示修改 Day：

```text
10/15/2026
```

用户只输入：

```text
11/05
```

表示：

```text
11/05/2026
```

用户输入：

```text
03/08/27
```

表示：

```text
03/08/2027
```

该机制的目标是减少重复输入，提高日期录入效率。

---

# 5. 8 Digit 输入模型

虽然 DateInput 对用户表现为三个 segment，但数字输入采用 8 digit 模型：

```text
MM DD YYYY
```

每次数字输入作用于当前 digit。

数字输入后，cursor 根据输入规则自动移动。

---

# 6. Month 输入规则

Month 范围严格限制为：

```text
01–12
```

确定非法的输入在输入时直接拒绝，不进入 draft。

拒绝输入可以提供短促的 beep / input feedback。

## 6.1 输入规则

| 当前输入  | 下一位   | 行为                     |
| ----- | ----- | ---------------------- |
| 空     | `0`   | 等待第二位                  |
| 空     | `1`   | 等待第二位                  |
| 空     | `2–9` | 自动形成 `02–09`，进入 Day    |
| `0`   | `1–9` | 形成 `01–09`，进入 Day      |
| `0`   | `0`   | 拒绝                     |
| `1`   | `0–2` | 形成 `10–12`，进入 Day      |
| `1`   | `3–9` | **拒绝 + beep，保持 Month** |
| `2–9` | —     | 已自动形成 `02–09`          |

例如：

```text
2 → 02 → 进入 Day
7 → 07 → 进入 Day
1 → 等待第二位
10 → 10 → 进入 Day
12 → 12 → 进入 Day
13 → 第二个 digit 被拒绝 + beep
```

`13` 不作为有效月份接受。

---

# 7. Day 输入规则

Day 的数字范围首先限制为：

```text
01–31
```

超过 31 的输入在输入时直接拒绝。

## 7.1 输入规则

| 当前输入  | 下一位   | 行为                   |
| ----- | ----- | -------------------- |
| 空     | `0`   | 等待第二位                |
| 空     | `1–3` | 等待第二位                |
| 空     | `4–9` | 自动形成 `04–09`，进入 Year |
| `0`   | `1–9` | 形成 `01–09`，进入 Year   |
| `0`   | `0`   | 拒绝                   |
| `1–2` | `0–9` | 形成 `10–29`，进入 Year   |
| `3`   | `0–1` | 形成 `30–31`，进入 Year   |
| `3`   | `2–9` | **拒绝 + beep**        |

例如：

```text
4 → 04 → 进入 Year
9 → 09 → 进入 Year
15 → 15 → 进入 Year
29 → 29 → 进入 Year
31 → 31 → 进入 Year
32 → 第二个 digit 被拒绝 + beep
```

---

# 8. Year 输入规则

Year 为 4 digit。

输入：

```text
YYYY
```

每一位可以覆盖当前 digit。

完成 4 位后形成候选日期。

短年份输入规则：

* 1 位：补为 `200X`
* 2 位：使用 50 pivot
* 3 位：前补 `0`
* 4 位：直接使用

2 位年份：

```text
00–49 → 2000–2049
50–99 → 1950–1999
```

例如：

```text
2    → 2002
27   → 2027
49   → 2049
50   → 1950
3    → 0003
2027 → 2027
```

---

# 9. 日期级合法性验证

Month 和 Day 在 digit 输入阶段只负责判断自身范围。

形成完整日期后，再进行实际日期验证。

例如：

```text
02/29/2028 → valid
02/29/2027 → invalid
02/31/2026 → invalid
04/31/2026 → invalid
```

## 9.1 不再静默修正日期

DateInput **不再将非法日期静默钳制为另一个日期**。

例如：

```text
02/31/2026
```

不会自动变成：

```text
02/28/2026
```

用户输入的日期语义不应被控件静默改变。

---

# 10. Commit 与失焦

## 10.1 有效日期

当当前输入可以形成有效日期：

```text
blur
或
Enter
```

提交日期。

例如：

```text
10/15/2026
```

提交：

```text
2026-10-15
```

并触发：

```ts
onChange("2026-10-15")
```

---

## 10.2 空值

如果控件原本为空，用户：

```text
focus
→ 没有进行有效输入
→ blur
```

允许离开。

Value 保持：

```text
""
```

不触发 `onChange`。

---

## 10.3 非法日期

例如：

```text
02/31/2026
```

在 blur / Enter 时验证失败：

1. 不提交。
2. 外部 value 保持原值。
3. 阻止 focus 离开 DateInput。
4. 将 focus 定位到第一个导致问题的 segment。
5. 提供轻量的 invalid feedback。

当前日期合法性问题主要定位到 Day，因此：

```text
02 / 31 / 2026
      ↑
    focus Day
```

---

# 11. Keyboard Interaction

Overwrite-only editing model

## 11.1 数字键

数字键：

> 修改当前 digit，并根据 Month / Day / Year 的输入规则移动 cursor。

---

## 11.2 ← →

`←` / `→` 用于移动 cursor。

行为：

* segment 内移动 digit。
* 到达 segment 首尾时跨 segment。
* Year 最后一个 digit 后可进入控件外部 focus flow。

---

## 11.3 Tab

Tab 用于进入 / 离开 DateInput。

进入 DateInput 后：

* 空值：以当天作为 placeholder。
* 已有值：定位到对应日期位置。

DateInput 内部的 segment 移动主要通过数字输入和 `←` / `→` 完成。

Tab 不作为普通数字 digit 导航键使用。

---

## 11.4 Enter

Enter：

* 对当前候选日期执行 commit。
* 有效 → 提交并离开。
* 非法 → 拒绝提交，focus 保持在 DateInput，并定位到问题 segment。

---

## 11.5 Esc

Esc：

> 放弃当前编辑，恢复最近一次已提交 value。

不会触发 `onChange`。

---

## 11.6 Backspace / Delete

保持自然删除行为：

* Backspace：删除当前位置，必要时向前移动。
* Delete：删除当前位置。
* 删除后继续允许用户修改当前 segment。

如果删除导致当前输入重新回到“空输入”，控件仍然可以使用当天作为默认输入基准。

---

# 12. Click 行为

Click 支持：

1. 点击 DateInput 获得 focus。
2. 点击具体 digit，cursor 定位到对应位置。
3. 已有 value 时，可以直接进入对应日期位置编辑。
4. 点击 Calendar 打开月历。
5. 点击 Clear 清空 value。

---

# 13. Calendar

点击 📅 打开 Calendar Popover。

## 13.1 Calendar UI

头部：

```text
‹‹  ‹  YYYY.MM  ›  ››
```

支持：

* 上一年
* 上一月
* 下一月
* 下一年

日期区域：

```text
7 columns
```

状态：

* 当前选中日期：field theme highlight。
* 今天：outline。
* Today：提供「今天」快捷操作。

---

## 13.2 选择日期

点击日期：

1. 生成有效日期。
2. 回填 DateInput。
3. 立即 commit。
4. 触发 `onChange`。
5. 关闭 Calendar。

---

## 13.3 Calendar 文案

文案通过：

```text
texts
```

外置，例如：

```text
t.date.*
```

---

# 14. Clear

有 value 时显示 Clear 按钮。

点击：

```text
value = ""
```

并触发：

```ts
onChange("")
```

清空后重新 focus DateInput：

> 继续使用当天作为 placeholder / 输入基准。

---

# 15. Accessibility

DateInput 必须支持：

* 外层 `role="group"`。
* 明确的 group label。
* Month / Day / Year 各自具有 accessible name。
* Calendar button 具有明确 accessible name。
* Clear button 具有明确 accessible name。
* invalid 状态提供可被辅助技术感知的状态。
* focus 始终明确落在当前可继续操作的 segment / digit。

推荐语义：

```text
Start date
  Month
  Day
  Year
  Open calendar
  Clear start date
```

---

# 16. 不做 / 暂不定义

V1 暂不支持：

* datetime
* timezone
* 多种 display format
* 国际化日期格式
* readonly
* min/max prop
* range selection
* 父组件在用户编辑过程中改变 `value` 时的 draft synchronization 行为

其中最后一项暂不定义，作为后续独立控件化时考虑的 API / state synchronization 问题。

---

# 17. 核心设计原则

DateInput 的行为遵循以下原则：

### 1. 空值与有效值是唯一对外状态

```text
"" | yyyy-MM-dd
```

### 2. 今天是默认上下文，不是自动写入的值

Focus 空控件时显示今天，但不会产生 value。

### 3. 支持部分输入，提高录入效率

用户只修改自己关心的 Month / Day / Year，未修改部分以当天日期为基准。

### 4. 能在输入阶段判断非法，就立即拒绝

例如：

```text
Month: 13
Day: 32
```

直接拒绝非法 digit，并提供轻量 beep / feedback。

### 5. 跨字段才能判断的非法日期，在 commit 时验证

例如：

```text
02/31/2026
02/29/2027
```

不静默修正。

### 6. Commit 失败不允许静默离开

非法日期：

```text
保留用户输入
↓
阻止 blur
↓
focus 回问题 segment
↓
用户继续修正
```

### 7. 对外是 3 段，对内是 8 digit

产品和交互模型：

```text
Month / Day / Year
```

实现模型：

```text
MM DD YYYY
= 8 digit cursor model
```

---

# 18. 验收重点

至少覆盖以下场景：

| 场景       | 输入               | 预期                         |
| -------- | ---------------- | -------------------------- |
| 空 focus  | focus            | 显示今天 placeholder，value 仍为空 |
| 空 blur   | focus → blur     | value 仍为空                  |
| 快速月输入    | `7`              | `07`，进入 Day                |
| 月边界      | `12`             | 接受                         |
| 非法月份     | `13`             | 第二 digit 拒绝 + beep         |
| 快速日输入    | `4`              | `04`，进入 Year               |
| 日边界      | `31`             | 接受                         |
| 非法日期日    | `32`             | 第二 digit 拒绝 + beep         |
| 闰年       | `02/29/2028`     | commit                     |
| 非闰年      | `02/29/2027`     | 阻止 commit，focus Day        |
| 月末       | `04/31/2026`     | 阻止 commit，focus Day        |
| 部分输入     | 只输入 Day          | Month/Year 使用当天            |
| 年份       | `27`             | 2027                       |
| Calendar | 点击日期             | 回填、commit、关闭               |
| Clear    | 点击 ×             | value 清空                   |
| Esc      | 修改后 Esc          | 恢复 committed value         |
| Enter    | 有效日期             | commit                     |
| Enter    | 非法日期             | 不 commit，focus 问题 segment  |
| Blur     | 有效日期             | commit                     |
| Blur     | 非法日期             | 阻止离开，focus 问题 segment      |
| Paste    | ISO / MM/DD/YYYY | 分发到三个 segment              |
