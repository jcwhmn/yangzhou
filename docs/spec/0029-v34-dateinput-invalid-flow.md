# 0029 YPJ-34(+22/23):DateInput 非法日期处理流 + 候选池清理/控件化拍板材料

2026-10-13 立项(用户指示三票同回合)。状态:**完工**(As-built 见文末;用户 build/debug 通过,含 D 节 overwrite 增量)。

## 需求

### A. YPJ-34 主体(PRD_V2 权威,`docs/requirement/DateInput PRD_V2.md` §9/§10/§17 + §6.1/§7.1 digit 表)

现行非法日期**静默钳制**(02/31→02/28,spec 0019 拍板)废止,改三段式:

1. **输入阶段段内拒绝**(§6.1/§7.1 digit 表,全表实装,不止第二位超界):
   - 月:首位 2–9 → 自动 `0X` 跳段(现行为已对);`1`+`3–9` → **拒 + feedback 保持**;`0`+`0` → **拒**(无 `00` 月)。
   - 日:首位 4–9 → `0X` 跳段(已对);`3`+`2–9` → **拒**;`0`+`0` → **拒**(无 `00` 日)。
2. **commit(blur/Enter)跨字段验证**(§10.3,如 02/31/2026、02/29/2027):不提交、外部 value 不动、**阻止 focus 离开**、focus 定位第一个问题 segment(日问题→Day)、轻量 invalid feedback。
3. **空值 focus 未输入 blur**:允许离开,value 保持 `""`,不触发 onChange(§10.2)。

**⚠ 两处票面前提偏差,请复核**(均按 PRD_V2 权威实做):

- 票面「空值…现行为一致」**不符**:现行空值聚焦预填今天、失焦**即提交今天**(0019 拍板「失焦即提交」)。PRD §10.2 + 原则 2(今天不是自动写入的值)要求 value 保持空——本票一并改。
- 段内拒绝范围:现行 fill/IME 兜底路径还有「日 >31 记 31」钳制,一并废止(拒绝,不修正);修正后「00」在 digit 阶段即拒,不再落到 commit 验证。

### B. YPJ-23 功能增强池清理(V1 PRD §6.2 五项逐项裁决,票即载体)

| 池项 | 裁决 | 本票动作 |
|---|---|---|
| 月段输入封顶(现仅提交期钳 12) | **被 YPJ-34 覆盖**(A-1 即 §6.1 全表) | 无单独工作 |
| 月历键盘导航(←/→ 移日格、Enter 选中) | **本票实装** | MiniCalendar 加方向键(见设计) |
| 月历的 e2e 覆盖 | **本票实装** | 并入新 date-input.spec.ts(见设计) |
| IME 逐位适配(现降级整段解读) | **0019 已做**(beforeinput insertText 逐位接管,候选上屏同语义);残余:组合窗中拼音字母预显——数字段 inputMode=numeric 无真实场景,出现痛点再立票 | 池内销项 |
| min/max 范围 prop | **维持 defer**(其自身定义「出现真实需求再加」,无触发) | 池内销项,PRD §6.2 留档 |

五项全部有归宿 → **本票合并后 YPJ-23 移 Done**(PRD §6.2 保留为记录,池不再需要载体)。

### C. YPJ-22 独立控件化拍板材料(V1 PRD §6.1;决策归你,本票只备料)

现状:DateInput(约 520 行)+ Dropdown 自研,theme 语言/受控契约/ARIA 契约一致,消费方仅本仓 web(DateInput 5 处、Dropdown 若干处)。

| 选项 | 内容 | 成本 | 适用触发 |
|---|---|---|---|
| **A. 维持单仓**(推荐) | 控件留 `web/components`,零迁移 | 零 | 没有第二消费方/不开源(现状) |
| B. monorepo workspace 包 | 抽 `packages/ui`,导入改 `@yangzhou/ui`;无发布开销,内部复用更正式 | 中(import 全改 + 构建链) | 出现第二内部消费方(CLI TUI/第二前端)或开源计划落地 |
| C. npm 独立包 + semver | 独立仓/包发布,版本化 | 高(发布流/版本纪律/兼容承诺) | 确定有外部使用者 |

**建议:A,现在不动**;触发条件写明(出现 B/C 触发件再重开)。你拍板 A/B/C 任一,票按裁决关/立新票。

### YPJ-34 非目标

Calendar UI 形态/Clear/Esc/年短写补全(27→2027)/粘贴分发行为不变(beep 音频不做,视觉 feedback 即可);DateInput 对外 props 契约不变,调用方(MilestoneManager/ReleaseManager/详情页/layout)零改动;min/max prop 无真实需求不立(见 B 节)。

### D. 增量:digit 选中 overwrite 模式(用户拍板 2026-10-13,同回合)

用户反馈插入模式不清晰 → 焦点/键入进位/点击落点一律**单 digit 选中**呈现(原生文本选区高亮),键入即覆写该位:writeDigit 本就是按位覆写,本增量只补视觉与光标语义。←/→ 在 digit 间移动并选中(跨段落首/末位 digit),点击段取 caret 落点选中;focusSeg 落段即选首/末位 digit。

## 详细设计

**改 `web/components/DateInput.tsx` + 新增 `web/e2e/date-input.spec.ts`;后端零改动**:

- **删 `normalize()`**(钳制核心);`daysInMonth` 留给月历与验证用。
- **`writeDigit` 段内拒绝**(按 §6.1/§7.1 全表):月 pos1 `cur[0]==="1" && d>2` 拒、`cur[0]==="0" && d===0` 拒;日 pos1 `cur[0]==="3" && d>1` 拒、`cur[0]==="0" && d===0` 拒;拒即不写/不跳段/`setInvalidSeg(k)`(红字 feedback),光标原地;删除日 pos1 的 `ones>1→1` 钳制。任何成功写入先 `setInvalidSeg(null)`(修正即消红)。
- **`onSegInput`**(fill/IME 兜底):删「日 >31 记 31」;整段结果非法(月 >12、日 >31、`00`)→ 不应用 + `setInvalidSeg(k)`。
- **`commit` 重写**:不完整 → 保留草稿(不变);完整 → `y/m/d` 数值验证:`m∉1-12 → problem=mm`,否则 `d∉1-daysInMonth(y,m) → problem=dd` → `setInvalidSeg(problem)` + `focusSeg(problem, true)` + return(**不 onChange、不 setSegs**);合法 → onChange + 草稿同步(现行为)。
- **blur 阻止**:root `onBlur`(relatedTarget 出 root)调 commit,失败路径内 focusSeg 拉回——DOM focus 已被 blur 带走,拉回即 §17-6「不允许静默离开」;× 清除钮 onMouseDown preventDefault、📅 在 root 内,均不触发此路径(不变)。
- **§10.2 空进空出**:加 `dirty` ref(本焦点期内有真实写入才 true;writeDigit/onSegInput/onSegPaste 置位,root onFocus 且 value==="" 时复位)。blur 时 `value==="" && !dirty` → 还原三段为空、直接 return(不 onChange)。部分输入(改了 Day)→ dirty → 照常 commit,月/年取今天基准(§17-3)。
- **feedback UI**:`invalidSeg` state——该段 input `color: error.main` + `aria-invalid`,修正/成功 commit/Esc/清除时消;Esc 回滚顺带清 invalidSeg。
- **月历键盘导航**(YPJ-23):MiniCalendar 日格 grid 容器 onKeyDown——←/→/↑/↓ 移 focus ±1/±7 日(7 列折算,越界忽略),Enter/Space 走 button 原生 click 即选中;日格已有 `key={iso}`,按 `data-iso` 查 DOM 定位目标格。约 15 行。
- **e2e**(新 `web/e2e/date-input.spec.ts`,独立 key 项目,不依赖其它用例):非法月 digit 拒绝(`1`+`3`)、日 `3`+`2` 拒、`0`+`0` 拒、02/31/2026 commit 拒绝(value 不变 + focus 在日段)、02/29/2028 闰年通过、02/29/2027 阻止、空 focus→blur value 仍空、Esc 回滚;月历:开月历点某日 → data-value 更新且月历关、键盘 ←/→ 移格 Enter 选中。现有 fillDate 4 处调用全为合法日期,helpers 契约不变(票面「多处适配」实际为 0 处,验证过)。

否决备选:beep(Web Audio 自动播放策略,不值);invalid 反馈用 Tooltip 文案(轻量原则,红字+aria-invalid 足够);月历键盘用 roving tabindex 全套(池项只要求 ←/→ + Enter,从简)。

## 手工测试用例(build/debug 阶段)

1. 月段输 `1` 再按 `3` → 3 被拒,显示 `1_` 红字,光标未跳;按 `2` → `12` 跳日段,红消。
2. 日段输 `3` 再按 `2` → 拒;月/日段输 `0` 再按 `0` → 第二个 0 拒。
3. 详情页截止日期输 `02/31/2026` → Enter/blur → 不提交(data-value 不变),focus 拉回日段红字;改 `28` → 提交成功。
4. `02/29/2028` 提交成功;`02/29/2027` 阻止(focus Day)。
5. 空日期控件 focus(出现今天预填)→ 不输入直接 blur → value 仍空、不误存今天。
6. 空控件 focus → 只改日段 → blur → 整日期提交(月/年=今天)。
7. Esc:非法态回滚 committed value 且红消。
8. 月历:点 📅 开月历 → 点任意日 → 回填提交且月历关;焦点在日格按 → 移下一日,Enter 选中。
9. 点任意 digit → 该位高亮选中,直接键入覆写;←/→ 逐 digit 移动。
10. e2e 全量回归 + 新 date-input.spec 通过。

## 验收对照(票/PRD §18)

- [ ] 段内非法 digit 拒绝 + feedback(含 `00`)→ 手工 1/2
- [ ] commit 阻止 + focus Day + value 不变 → 手工 3/4
- [ ] 空值空出 → 手工 5
- [ ] 部分输入 commit → 手工 6
- [ ] 月历键盘导航 → 手工 8(YPJ-23)
- [ ] YPJ-23 池五项全部销项 → B 节表;合并后 23 移 Done
- [ ] YPJ-22 按你拍板关/立票 → C 节表
- [ ] spec 0019 手工用例 7/8(越界修正)自本票起作废,由本表替代

## As-built

> 2026-10-13 完工。与设计零结构性偏离;一次 e2e 用例自身 bug 修正(fill 跳段后 blur 错段,非实现问题)。

- **DateInput.tsx**:`normalize()` 删除;`writeDigit` pos1 按 §6.1/§7.1 拒(`formed===0` 或 `>12/31`,`setInvalidSeg` 后原地保留);`onSegInput` 整段成段非法拒应用;`commit` 跨字段验证(problem=mm/dd → focus 拉回、不外发);`dirty` ref 实装 §10.2 空进空出;`invalidSeg` 红字(`.seg-invalid` + `aria-invalid`),写入/成功 commit/Esc/Clear 消红;月历 grid `onKeyDown` ±1/±7 + 日格 `data-iso`;**D 节增量**:`selectDigitEl` + focusSeg 落段选首/末位 digit、ArrowLeft/Right 逐位移动选中、input onClick 延时取 caret 落点选中。
- **e2e 新 `date-input.spec.ts`**:7 用例(digit 拒/00 拒/commit 阻止+focus 断言/闰年边界/空进空出/部分输入/Esc 回滚/月历点选+键盘+选中断言),helpers 契约未动。
- **验证**:tsc 零新增;全量 e2e **44 passed**(原 37 + 新 7);手工用例 1–10 用户实测通过。
- **YPJ-22 关票依据**:用户过目 C 节后 go = 拍板 **A 维持单仓**(零迁移;B/C 触发条件见 C 节,出现即重开)。合并后 22 移 Done。
- **YPJ-23 销项**:月段封顶→被 34 覆盖;月历键盘导航→本票实装;月历 e2e→本票实装;IME 逐位→0019 已做(beforeinput 路径);min/max→无触发维持 defer(PRD §6.2 留档)。合并后 23 移 Done。
