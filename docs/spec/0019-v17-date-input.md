# 0019 — V17-6 日期组件重设计:mm/dd/yyyy 分段直录

> 票:YPJ-19。原生 `<input type="date">` 时间挤在一起、不好操作、观感差,自研分段日期输入替代(4 文件 8 处);依赖 V17-4 theme 基线。**PRD 先行:本 spec 第 1 节经用户确认后才补详细设计与编码。**

## 1. 需求(详细)= 组件 PRD

### 1.1 背景

- 现状 4 文件 8 处原生 date 输入(item 详情 2 / 项目 layout 建 sprint Dialog 2 / MilestoneManager 1 / ReleaseManager 3):浏览器按 locale 渲染,段间挤在一起,键盘不舒展,各浏览器观感不一。
- 8 处全是「表单态 + 显式保存」:onChange 写表单 state,落库靠保存/提交按钮。组件只需保证「编辑中不污染表单值」,放弃编辑即不落库。
- e2e 契约现状:3 个 spec 文件 4 处 `getByLabel(label).fill("yyyy-MM-dd")` + 1 处 `toHaveValue`(board 2+1、milestone 1、release 1)。分段化后 fill 单输入不再成立,契约随组件重定(spec 0021 helpers 增量)。

### 1.2 形态

- 三段直录:`mm / dd / yyyy` 顺序固定,段间 `/` 分隔;每段为真实数字输入——鼠标点在数字上即落入该段直接改(票内明示)。
- 视觉与 theme 基线一致:field 底色 `#e9f0f6`、无边框、圆角 8,聚焦显边框;段字号与间距按「展示舒展」放宽(具体值见详细设计)。
- 空态:各段灰字 placeholder `mm` / `dd` / `yyyy`;无可见 label 场景(ReleaseManager 发货内联)整条显示 `mm/dd/yyyy` 灰字。
- 清除:有值时右侧 ×(可访问名「清除 <字段>」,与 Dropdown 清除同款式)。
- 两档尺寸:small(行内,默认宽约 150px)与默认(Dialog 表单);宽度可由调用方覆盖。
- label:上方小字;另有 `aria-label` prop 兜底无可见 label 场景。

### 1.3 交互

**录入与跳段**
- 每段只收数字;输满自动跳下一段(mm 2 位→dd,dd 2 位→yyyy,yyyy 满 4 位即完整)。
- ←/→ 段间移动,`/` 同右移。
- Backspace 在空段上回退上一段;Delete 清当前段。
- 粘贴:任一段粘贴完整日期(ISO `yyyy-MM-dd` 或 `mm/dd/yyyy`)→ 分发三段;单段数字照常入段。

**提交与放弃(核心语义:编辑中不落库)**
- 组件内草稿态:`onChange` 只在「三段完整且组合合法」或「清空」时外发(ISO 串或 "");打字过程不外发,调用方表单值不被半截输入污染。
- Enter:草稿完整合法 → 立即提交;组合非法 → 红框提示,不动作。
- 失焦:完整合法 → 提交(与已提交值相同则无操作);完整但组合非法(如 02/30)→ 保留草稿 + 红框,不提交;不完整 → 保留草稿,不提交(用户拍板 2026-10-10)。失焦保留的草稿继续显示,下次聚焦接着改。
- Esc:任何时刻恢复到已提交值(放弃编辑,不落库)。
- 清空:点 × 立即 `onChange("")`。
- 与保存按钮的时序:点保存先触发失焦——完整合法的编辑在保存前已提交,保存拿到新值;不完整/非法草稿则保存拿到「已提交值」(与原生 date 行为一致,记录为已知限制)。

**空值点击插当天**(票内明示;最终规则用户拍板 2026-10-10)
- 值为空时点击控件任意段 → 三段预填今天,焦点落在被点段,可继续改。
- 失焦即提交今天——预填即视为选定今日,所见即所存;与「失焦:完整合法 → 提交」一般规则合流,无特例。
- 保持空值的途径:不点控件,或点 × 清空;Esc 仍可在失焦前放弃。

### 1.4 边界

- 组合非法不静默钳制:显示用户输入并红框,提交被拦(见 1.3);段内合法域 mm 1–12 / dd 1–31 / yyyy 4 位。
- 不设 min/max 范围 prop(8 处现状无此需求,真出现再加)。
- 非目标:日历弹层选择器(本票=分段直录;原生 picker 随替换消失,**行为变化请用户知悉**)、时间部分、只读态、国际化(显示顺序固定 mm/dd/yyyy,内部值恒 ISO `yyyy-MM-dd` 或 "")。

### 1.5 可访问性与 e2e 契约(重定)

- 结构:外层 `role="group"` `aria-label={label}`(可见 label 关联),三段各带 aria-label(月/日/年,文案外置 texts.ts,组内作用域),× 为 button「清除 <字段>」。
- helpers 增量(spec 0021):`fillDate(page, label, "yyyy-MM-dd")` 分发三段并失焦提交;`expectDate(page, label, iso)` 读容器 `data-value` 断言。
- 现存 4 处 fill + 1 处 toHaveValue 迁移到新 helpers(board、milestone、release)。

### 1.6 目标

1. 新组件 `web/components/DateInput.tsx`(与 Dropdown.tsx 平级),本节即其 PRD。
2. 替换 8 处:item 详情(2)/ 项目 layout(2)/ MilestoneManager(1)/ ReleaseManager(3)。
3. e2e 契约迁移 + 全量本地绿。

### 1.7 验收(票内清单)

- [ ] PRD 细化并经用户确认后才动实现(= 本节,待确认)
- [ ] 自研组件替换原生 type="date" 8 处(item 详情 / 项目 layout / MilestoneManager / ReleaseManager)
- [ ] E2E 适配 + 全量本地绿

## 2. 详细设计(代码级修改计划)

### 2.1 组件 API(`web/components/DateInput.tsx` 新)

```tsx
<DateInput
  label?: string        // 可见 label(上方小字,样式对齐 Dropdown 的 shrink label)
  ariaLabel?: string    // 无可见 label 的可访问名(ReleaseManager 发货内联)
  value: string         // 受控,ISO yyyy-MM-dd 或 ""
  onChange: (v: string) => void   // 只在「完整合法」或「清空」时外发
  size?: "small"        // 行内档(默认宽 ~150px)与 Dialog 档
  sx?: SxProps          // 透传容器(item 详情 180 等宽度覆盖)
/>
```

不设 disabled/只读/min-max(8 处无此需求;真出现再加)。

### 2.2 状态机(核心:草稿 vs 已提交)

- 草稿 `segs = { mm, dd, yyyy }`(string,各 0–2 / 0–2 / 0–4 位数字),显示层唯一来源。
- `committed = value`(prop)。value 变化且未聚焦 → setSegs(parse(value))(表单 reset 场景,如 MilestoneManager reset)。
- parse:ISO → 段;空/非法 → 段空。
- **提交路径**(evalDraft):三段全空 → `onChange("")`;完整(mm 1–12 / dd 1–31 / yyyy 满 4 位 / new Date 回读校验真实日期)→ `onChange(iso)`;组合非法 → error 态(红框),不外发;不完整 → 保持草稿,不外发。
- 触发:容器 blur(relatedTarget 不在容器内才算真失焦,段间移动不算)+ Enter。
- Esc → setSegs(parse(committed)) 回滚草稿(空值即回空,撤销预填)。
- × → onMouseDown preventDefault(防 blur 吞 click)+ `onChange("")` + 段清空。
- error 态在任意段再编辑或 value 外部变化时清除。

### 2.3 段行为

- 每段 `<input inputMode="numeric" maxLength 同位数 aria-label=月/日/年>`;onChange 只留数字、截断超长。
- 输满自动 focus 下一段(mm→dd→yyyy;yyyy 满不跳)。
- keydown:←/→ 段间移动,`/` 同 →;Backspace 在空段回退上一段;Delete 清当前段。
- paste:剪贴板匹配 ISO `yyyy-MM-dd` 或 `mm/dd/yyyy` → 分发三段;否则按普通入段。
- **空值点击预填**:容器 onMouseDown 且 `value === ""` 且三段全空 → setSegs(today) 后放行默认聚焦(焦点自然落在被点段);失焦即走 2.2 提交路径 → 今天入库,无特例。

### 2.4 结构与可访问性

```
<Box role="group" aria-label={label ?? ariaLabel} data-value={committed}
     sx={{ 底色 field、无边框、圆角 8、focus-within 显边框、error 红框 }}>
  {label && <FormLabel aria-hidden 小字>}   // 视觉 label,朗读走 group aria-label,防双读
  <input 月/> / <input 日/> / <input 年/>
  {有值 && <IconButton aria-label={`清除 ${label ?? ariaLabel}`}>×</IconButton>}
</Box>
```

- data-value 恒等于已提交 ISO 串(e2e 断言锚点;草稿不进 data-value)。
- texts.ts 增 `date: { month: "月", day: "日", year: "年" }`。

### 2.5 e2e helpers 增量(`web/e2e/support/helpers.ts`)

```ts
fillDate(page, label, iso)   // getByRole("group", { name, exact: true }) 内按 月/日/年 fill,末段 blur 提交
expectDate(page, label, iso) // expect(group).toHaveAttribute("data-value", iso)
```

- exact:true(0018 子串撞名教训)。

### 2.6 替换清单(8 处机械改写)

| 文件 | 处 | 改法 |
|---|---|---|
| `web/app/p/[key]/i/[itemId]/page.tsx` | 2 | `label="开始日期"/"截止日期" size="small" sx={{ width: 180 }}` |
| `web/app/p/[key]/layout.tsx` | 2 | 建 sprint Dialog,`size="small"` |
| `web/components/MilestoneManager.tsx` | 1 | `label={t.milestone.targetDate} size="small"` |
| `web/components/ReleaseManager.tsx` | 3 | targetDate / releasedDate 各 1(size small);发货内联 1 无 label → `ariaLabel={t.release.releasedDate}` |

- e2e 迁移:board.spec(2 fill + 1 toHaveValue)→ fillDate/expectDate;milestone.spec(1);release.spec(1)。

### 2.7 否决的备选

- `@mui/x-date-pickers` DatePicker:引新依赖 + 非分段直录观感,落选(票要自研)。
- 保留原生 input 仅做皮肤:分段观感/键盘行为不受控,落选。
- 隐藏真实 input 承载 ISO 供 e2e fill:Playwright fill 要求元素可见,不可行——改为三段真实输入 + helpers。

## 3. 手工测试用例(build/debug 阶段执行)

1. **回显**:item 详情有起止日期 → 显示 `mm/dd/yyyy` 舒展,底色无边框。
2. **点段直改**:点 dd 段输 `15` → 即时变 `15`;保存 → 重进页面为 2026-10-15。
3. **自动跳段**:空字段连输 `10` `31` `2026` → 段满自动跳,失焦提交 2026-10-31。
4. **空值点击插当天**:建 sprint Dialog 点空「开始日期」→ 三段预填今天;直接点「创建」→ sprint 起始=今天。
5. **null 保持**:建 sprint 不碰日期直接创建 → 起止均为空。
6. **放弃**:点空日期(预填今天)→ Esc → 点别处 → 字段回空。
7. **段内非法**:mm 输 `13` 失焦 → 红框、值不变;改 `11` → 提交。
8. **组合非法**:输 `02/30/2026` → 红框不提交(库值不变);改 `02/28/2026` → 提交。
9. **不完整保留**:dd 改成 `3` 失焦 → 草稿保留显示、值不变;补成 `31` → 提交。
10. **清空**:有值点 × → 立即空;保存 → null。
11. **粘贴**:复制 `2027-01-05`,点 mm 段 Ctrl+V → 三段分发 `01/05/2027` 并提交。
12. **键盘**:`←/→`、`/` 段间移动;空段 Backspace 回退;Delete 清段。
13. **发货内联**(ReleaseManager 🚀):无 label 字段 placeholder `mm/dd/yyyy`,可录可清。
14. **e2e**:board/milestone/release 三 spec 迁移后单跑绿(agent 执行);全量本地绿。
