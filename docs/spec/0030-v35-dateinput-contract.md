# 0030 YPJ-35:DateInput PRD_V2 契约形式化核对

2026-10-13 立项。状态:**完工**(As-built 见文末;含 label floating 压边增量,用户验收通过)。前置 YPJ-34 已合入(#100),无冲突。

## 需求(票面)

PRD_V2 契约形式化项,核对现行实现并补齐:对外 Value 双态(§3.1/§3.3)、受控 onChange 触发面(§3.2)、Esc 回滚(§3.3/§11.5)、ARIA/Label(§2.2/§15)、Clear(§14)、Focus 空控件当天仅参考(§4.1)。多为核对+补契约测试;发现偏差逐项修。

## 核对表(逐条对照现行实现,34 合入后主干)

| 契约 | 现状 | 处理 |
|---|---|---|
| §3.1 Value 只有 `"" \| yyyy-MM-dd`,draft 不外泄 | ✓ onChange 仅在 commit(完整且合法)/Calendar/Clear/键盘清空后 blur 四处 | 无需改 |
| §3.2 onChange 三触发(提交/Calendar/Clear) | ✓ 编辑过程零外发;键盘逐位清空后 blur 发 `""` 判为 Clear 同义(键盘路径) | e2e 补断言 |
| §3.3 Esc 恢复最近提交 value | ✓(34 已带消红) | 已有用例 |
| §2.2/§15 role=group、段/按钮 accessible name | ✓(`月历 X`/`清除 X` 符合 §2.3 格式) | e2e 补冒烟断言 |
| §15 invalid 可感知 | ✓(34:`aria-invalid` + 红字) | 已有用例 |
| §15 focus 明确落在可继续操作的 digit | ✓(34-D:digit 选中 overwrite) | 已有用例(selection 断言) |
| §4.1 空聚焦当天仅参考不产生 value | ✓(34:§10.2 空进空出) | 已有用例 |
| §14 Clear:清空后**重新 focus DateInput** 继续录(当天基准) | **✗ 偏差**:现实现只清空不回焦(焦点在组内时靠 mousedown preventDefault 恰好不丢;焦点在组外时点 × 后落空) | **修**:onClick 清空后 `focusSeg("mm")` → onSegFocus 以今天为基准重预填 |
| §14 Clear 显示条件 | ✓有值显示;无 name(无 label/ariaLabel)时不显示(无障碍名无从构成) | 记录为有意行为,不改 |

## 详细设计

- **`web/components/DateInput.tsx` 仅一处**:Clear `onClick` 末尾加 `focusSeg("mm")`(清空后三段全空,焦点落月段 [0,0],onSegFocus 以今天重预填,§14"继续使用当天作为输入基准")。
- **`web/e2e/date-input.spec.ts` 追加 3 用例**:
  1. **draft 不外泄**:聚焦空控件填月段 `1`(等待位)→ `data-value` 仍 `""`;补全合法日期 blur → `data-value` 变更(§3.2 触发面)。
  2. **Clear 契约**:填值 → × 可见且名 `清除 开始日期` → 点 × → `data-value` 变 `""`、月段获得焦点(§14 回焦)、再 blur 不产生值。
  3. **ARIA 冒烟**:role=group + name、三段 aria-label、`月历 X` 按钮名。
- 无后端、无其它文件改动。

否决备选:把键盘清空 blur 不发 `""`(破坏受控同步,外部表单会留旧值);Clear 回焦改为"仅焦点在组外时"(分支行为更难测,§14 文意是"清空后重新 focus",统一回 mm 最简)。

## 增量:label floating 压边(用户拍板 2026-10-13,同回合)

用户反馈 DateInput 的 label 悬在控件上方,与其它控件不一致,要求统一为 **floating label 压边**:label 中线落在控件上缘(translateY(-50%)),自带与控件背景一致的底色(tokens.field)遮挡焦点边框形成"边框被切开"效果;左右留距(left:10 + px:0.5)不贴圆角。底色取 field 色而非页面/弹窗色:label 下半在控件内与 field 无缝,上半为 field 色浅补丁,两种表面均可接受;自绘 Box 无 fieldset,无法做 MUI outlined 的边框缺口。几何零位移,aria 不变。样式已入 PRD_V2 **§2.5 Floating Label**(用户口述六点形式化);两版 PRD 归一:**V1(DateInput PRD.md)已删**,V2 为唯一权威(spec 0019 的历史引用不改)。

## 手工测试用例(build/debug 阶段)

1. 有值控件点 × → 值清、月段聚焦并显示今天预填,继续键入即改;blur 不误存纯预填。
2. 焦点在控件内编辑中点 × → 同样回月段重新开始。
3. 编辑一半(月段 `1_`)看外部表单值不变 → 提交后才变。
4. 巡检五处调用方(详情页/里程碑/Release 表单/货船行/侧栏 sprint 表单)label 压边观感(中线贴上缘)与上方避让(凸出 ~7px)。
5. e2e 全量回归。

## 验收对照(票)

- [ ] 契约逐条核对并留痕 → 本 spec 核对表
- [x] 偏差修复(Clear 回焦)→ 手工 1/2 + e2e
- [x] 契约测试补齐 → e2e 3 新用例

## As-built

> 2026-10-13 完工。与设计零结构性偏离;一次 e2e 用例自身 bug 修正(fill 前未按 Home,click 落点段中致逐位覆写错位)。

- **DateInput.tsx 两处**:①Clear `onClick` 末尾 `setSegs(todaySegs()) + focusSeg("mm")`(显式预填:handler 内 value prop 尚为旧值,onSegFocus 不会自触发;dirty=false 保持 §10.2 空进空出);②label floating 压边(见增量节,含 tokens.field 底色遮边)。
- **PRD 归一**:V2 增 §2.5 Floating Label(六点形式化);**V1(DateInput PRD.md)已删**,V2 唯一权威(spec 0019 历史引用不改,仓内无其它引用)。
- **e2e**:date-input.spec 追加 3 用例(draft 不外泄 / Clear 回焦+纯预填不产生值 / ARIA 冒烟);helpers 契约未动。
- **验证**:tsc 零新增;date-input **13 passed**;全量 **47 passed**(底色前一轮;底色为纯视觉后补 date-input 轮);核对表 9 项:8 项对齐、1 项修复(Clear 回焦)、1 项有意行为记录(无 name 不显示 Clear)。
