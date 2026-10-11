# 0019 — V17-6 日期组件重设计:mm/dd/yyyy 分段直录

> 票:YPJ-19。原生 `<input type="date">` 时间挤在一起、不好操作、观感差,自研分段日期输入替代(4 文件 8 处);依赖 V17-4 theme 基线。**PRD 先行:本 spec 第 1 节经用户确认后才补详细设计与编码。**
> 需求权威文档:`docs/requirement/DateInput PRD.md`(讨论收敛版 + 后续工作池);本 spec 为立项与实现记录。

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
- 月历:右侧 📅 按钮弹自研 mini 月历 Popover(用户 2026-10-10 要求保留日历选择);‹ › 切月,点日回填并立即提交,「今天」一键回填。
- 两档尺寸:small(行内,默认宽约 150px)与默认(Dialog 表单);宽度可由调用方覆盖。
- label:上方小字;另有 `aria-label` prop 兜底无可见 label 场景。

### 1.3 交互

**录入与跳段(8 位键盘模型,2026-10-10 用户扩展)**

8 个数字位:1=月十、2=月个、3=日十、4=日个、5–8=年。

- 位 1(月十):0/1 → 填十位、进位 2;2–9 → 当个位补 0(0X)、跳位 3;Tab → 位 3(月保持)。
- 位 2(月个):0–9 → 填个位、跳位 3;← → 位 1(数字保持);Tab → 位 3。
- 位 3(日十):0–3 → 填十位、进位 4;4–9 → 当个位 0X、跳位 5;Tab → 位 5(日保持)。
- 位 4(日个):0–9 → 填个位、跳位 5;← → 位 3。日上限 31:位 3 为 3 时按 2–9 → 直接记 `31` 跳位 5(2026-10-10)。
- 位 5–8(年):逐位填、进位;满 4 位封口(再按数字忽略);短写提交补全——`27`→`2027`(2 位按 50→1900 pivot)、`2`→`2002`、`027`→`0027`;Enter 提交;→(位尾)与 Tab 均直接跳出控件进下一个控件(跳过段内 📅/× 按钮),失焦提交。
- ←/→ 光标位内自然移动,位首/位尾跨界段(数字保持);`/` 同 →。
- Tab 在控件内 = 段间前进,落位 1(数字保持);Shift+Tab 反向;Tab 进空控件与 click 同权(预填今天,光标位 1);出控件后按 DOM 顺序落焦点(item 详情即 开始日期 → 截止日期 → 优先级)。
- Backspace:位内自然删;空段回退上一段。Delete 自然删位(不再清整段)。
- 8 位相互独立(2026-10-10 用户拍板):点击任一数字位光标落该位,数字键覆写当前位、光标进一位;无任何整段选中行为(select-on-focus/onClick 全选已废除)。月/日个位写入即跳下一段位 1;年逐位覆写,满 4 后再按覆写末位。
- 粘贴:任一段粘贴完整日期(ISO `yyyy-MM-dd` 或 `mm/dd/yyyy`)→ 分发三段;单段数字照常入段。

**无效按键处理(2026-10-10 全部拍板)**
- 字母/符号键:忽略,位置不动。
- 十位放不下的数字(月十位 2–9、日十位 4–9):智能个位补 0 跳段(方案 A 用户确认;连打 `311990` → `03/11/1990`)。
- 段内超界(月 `13`–`19`、日超当月天数):照收、照常跳段;提交时按「越界自动修正」静默钳制,无提醒。
- 年满 4 位后再按数字:忽略(封口)。

**提交与放弃(核心语义:编辑中不落库)**
- 组件内草稿态:`onChange` 只在「三段完整」或「清空」时外发(ISO 串或 "");打字过程不外发,调用方表单值不被半截输入污染。
- Enter:草稿完整 → 立即提交。
- 失焦:完整 → 提交(与已提交值相同则无操作);不完整 → 保留草稿,不提交(用户拍板 2026-10-10)。失焦保留的草稿继续显示,下次聚焦接着改。
- **越界自动修正(2026-10-10 用户拍板,替代红框)**:输入过程不做当月最大日期判断;提交时静默钳制——月钳到 1–12(13→12)、日钳到当月最后一天(02/31→02/28,闰年 02/29)、日 0→01——无需提醒,修正值直接显示并落库。
- Esc:任何时刻恢复到已提交值(放弃编辑,不落库)。
- 清空:点 × 立即 `onChange("")`。
- 与保存按钮的时序:点保存先触发失焦——完整的编辑在保存前已提交(越界已修正),保存拿到修正值;不完整草稿则保存拿到「已提交值」(与原生 date 行为一致,记录为已知限制)。

**空值点击插当天**(票内明示;最终规则用户拍板 2026-10-10)
- 值为空时点击控件任意段 → 三段预填今天,焦点落在被点段,可继续改。
- 失焦即提交今天——预填即视为选定今日,所见即所存;与「失焦:完整合法 → 提交」一般规则合流,无特例。
- 保持空值的途径:不点控件,或点 × 清空;Esc 仍可在失焦前放弃。

### 1.4 边界

- 越界(02/31、月 13)提交时静默钳制修正(见 1.3),无红框拦截;段内合法域 mm 1–12 / dd 1–31 / yyyy 4 位。
- 不设 min/max 范围 prop(8 处现状无此需求,真出现再加)。
- 月历弹层保留(用户 2026-10-10 要求):自研 mini 月历 Popover,设计见 §2.8。
- 非目标:时间部分、只读态、国际化(显示顺序固定 mm/dd/yyyy,内部值恒 ISO `yyyy-MM-dd` 或 "")、月历内 ←/→ 键盘移动日格(后续需要再议)。

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
- **提交路径**(commit):三段全空 → `onChange("")`;完整(各段位满)→ 越界钳制(normalize:月 1–12,日 = 当月最后一天,`daysInMonth = new Date(y, m, 0).getDate()`,闰年自动正确)→ `onChange(iso)` 并把草稿同步为修正值;不完整 → 保持草稿,不外发。无 error 态(红框方案废弃)。
- 触发:容器 blur(relatedTarget 不在容器内才算真失焦,段间移动不算)+ Enter。
- Esc → setSegs(parse(committed)) 回滚草稿(空值即回空,撤销预填)。
- × → onMouseDown preventDefault(防 blur 吞 click)+ `onChange("")` + 段清空。

### 2.3 段行为(8 位独立光标模型,见 §1.3)

- 每段 `<input inputMode="numeric" maxLength 同位数 aria-label=月/日/年>`;无 select-on-focus/整段选中——8 位相互独立,点击落位、逐位覆写。
- 数字键 keydown 拦截 → writeDigit:覆写当前位、光标进一位;月/日十位超界数字按智能个位整段 `0X` 跳段;月/日个位写满跳下一段位 1;日上限 31(位 3=3 按 2–9 记 `31`);年满 4 覆写末位。
- DOM 直写 `el.value` + `setSelectionRange` 后再同步 state(受控重渲染比对相同值跳过写,光标不被重置)。
- onChange 仅兜底(Playwright fill / IME / 单段粘贴):整段智能解读——1 位=十位槽(留段或 `0X` 跳段)、2 位=满段跳段、日 >31 记 `31`。
- keydown:←/→ 光标位内自然移动,位首/位尾跨界段,年段位尾 →(与 Tab 同)聚焦 group 后第一个可聚焦元素;`/` 同 →;Backspace 空段回退上一段(位内自然删);Delete 自然删位。Tab:月/日段 preventDefault 聚焦下一段位 1;年段跳出控件。
- paste:剪贴板匹配 ISO `yyyy-MM-dd` 或 `mm/dd/yyyy` → 分发三段;否则按普通入段。
- **空值聚焦预填(统一入口)**:容器 onFocus 且 `value === ""` 且三段全空 → setSegs(today),光标落当前段位 1——click 与 Tab 进同权;失焦即走 2.2 提交路径 → 今天入库,无特例。

### 2.4 结构与可访问性

```
<Box role="group" aria-label={label ?? ariaLabel} data-value={committed}
     sx={{ 底色 field、无边框、圆角 8、focus-within 显边框 }}>
  {label && <FormLabel aria-hidden 小字>}   // 视觉 label,朗读走 group aria-label,防双读
  <input 月/> / <input 日/> / <input 年/>
  <IconButton aria-label={`月历 ${label ?? ariaLabel}`}>📅</IconButton>  // 弹 mini 月历 Popover(§2.8)
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

### 2.8 mini 月历 Popover(2026-10-10 追加,保留日历选择能力)

- `Popover` 锚定字段框(同 Dropdown Menu 的锚定语言);状态 `calOpen`,📅 按钮触发。
- 迷你月历(同文件私有组件):初始月 = 已提交值所在月(空则今天);头部 `‹‹ ‹ 年.月 › ››` 四钮(跳年/切月);7 列日格(首日前补空位),日 = 28px 方块按钮;选中日 = field 底色,今天 = 描边;点日 → 三段回填 + 立即提交 + 关闭;「今天」按钮一键回填今天。
- 星期表头/「今天」/上一月/下一月文案外置 texts(`t.date.weekdays/today/prevMonth/nextMonth`)。
- 非目标:月历内 ←/→ 键盘移动日格、年份快跳;e2e 不覆盖月历(直录路径已有 fillDate)。

## 3. 手工测试用例(build/debug 阶段执行)

1. **回显**:item 详情有起止日期 → 显示 `mm/dd/yyyy` 舒展,底色无边框。
2. **点段直改(逐位)**:点 dd 十位输 `1` → 光标进位 2;再输 `5` → `15` 跳年;点 `10` 的个位改 `5` → `15`(仅动该位,不整段覆盖);保存 → 重进页面为 2026-10-15。
3. **自动跳段**:空字段连输 `10` `31` `2026` → 段满自动跳,失焦提交 2026-10-31。
4. **空值点击插当天**:建 sprint Dialog 点空「开始日期」→ 三段预填今天;直接点「创建」→ sprint 起始=今天。
5. **null 保持**:建 sprint 不碰日期直接创建 → 起止均为空。
6. **放弃**:点空日期(预填今天)→ Esc → 点别处 → 字段回空。
7. **越界修正(月)**:输 `13/15/2026` 失焦 → 自动修正为 `12/15/2026` 并落库,无提示。
8. **越界修正(日)**:输 `02/31/2026` 失焦 → 修正为 `02/28/2026` 并落库;闰年 `02/29` 保持;位 3=3 后按 9 → 即时记 `31`。
9. **不完整保留**:dd 改成 `3` 失焦 → 草稿保留显示、值不变;补成 `31` → 提交。
10. **清空**:有值点 × → 立即空;保存 → null。
11. **粘贴**:复制 `2027-01-05`,点 mm 段 Ctrl+V → 三段分发 `01/05/2027` 并提交。
12. **键盘**:连打 `311990` → `03/11/1990`;`←/→` 位内移动、位首尾跨段;Tab 段间落位 1(数字保持);空段 Backspace 回退;Delete 自然删位。
13. **发货内联**(ReleaseManager 🚀):无 label 字段 placeholder `mm/dd/yyyy`,可录可清。
14. **月历**:点 📅 → 弹月历(初始=已提交值所在月,空则今天);‹ › 切月、‹‹/›› 跳年;点日 → 回填+提交+关闭;「今天」一键回填。
15. **Tab 行为与年短写**:Tab 进空控件 → 预填今天、光标在位 1;年段 Tab/→ 跳出控件;Tab 顺序 开始日期 → 截止日期 → 优先级(item 详情);年输 `27` 出去 → `2027` 落库(50 及以上 → 19XX)。
16. **e2e**:board/milestone/release 三 spec 迁移后单跑绿(agent 执行);全量本地绿。
