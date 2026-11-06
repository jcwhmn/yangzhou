# 0107 — 处置交互显式化:行内「回 Backlog」按钮 + 提交前结果摘要(方案A)

> 背景(2026-11-06 T104Z 事故复盘):处置弹窗「勾选 = 回 Backlog」checkbox 语义反直觉——视觉惯例是「勾选要处理的行」,实际是「送回 Backlog」。用户勾选两行 → 两行 PUT body `{"groupId":null}` 全落 Backlog(access log 实锤),Sprint 3 空置,误判「转移失败、item 留在原组」。用户拍板方案A:去掉 checkbox,行内显式按钮 + 提交前结果摘要。日志权衡另立 YPJ-154;事务化(方案C)未拍板,不在本票。

## 一、需求

### 1.1 行为矩阵(处置弹窗内,每个非终态成员行)

| 行状态 | 展示 | 可见操作 |
|---|---|---|
| 默认(转入目标组) | 行尾次级文字按钮「回 Backlog」 | 点击 → 行进入已定态 |
| 已定回 Backlog | 行整行警示色调 + 文字「将回 Backlog」+ 次级按钮「撤销」 | 点击撤销 → 回默认态 |

- **语义本体不变**(0106 D1/D2):默认全部转入目标组;只有显式点击才覆写;Done 留组;取消处置 = 不落任何归属,成员留已完成组(Backlog 派生可见,兜底)。
- 不再存在裸 checkbox;「回 Backlog」这个动作永远以**带文字的按钮**出现,点击即所见。

### 1.2 提交前摘要(新,防误操作最后一道闸)

- 确认按钮上方常驻摘要行:「将转入「目标组」N 个;回 Backlog M 个」——M > 0 时整行警示色。
- 目标组名复用弹窗顶部同一目标表达式(单进行中直指 / 选定 / 将激活 / 将新建),抽成单一 `targetLabel` 供顶部与摘要共用,不重复实现。

### 1.3 不变式

1. 处置只改归属,不动 item 状态(0106 非目标延续)。
2. 每行提交时刻的状态 = 行内所见(按钮态即真值,无隐藏状态)。
3. 术语「组」;用户可见文案外置 `texts.ts`。

### 1.4 非目标

- 处置单端点事务化(方案C,未拍板)
- 处置失败驻留 + 重试(随 C 另议)
- 逐 item 状态迁移、批量端点(0106 非目标延续)

## 二、设计

### 2.1 改动文件(零后端)

| 文件 | 改动 |
|---|---|
| `web/app/p/[key]/sprint/[id]/page.tsx` | ①行区:删 Checkbox,改次级 Button(默认 outlined「回 Backlog」/已定 contained warning「撤销」+ 行文字「将回 Backlog」);②摘要行 Typography(警示色条件 M>0);③目标表达式抽 `targetLabel` 局部函数,顶部与摘要共用 |
| `web/lib/texts.ts` | 新键:`dispRowBacklog`(回 Backlog)、`dispRowMarked`(将回 Backlog)、`dispRowUndo`(撤销)、`dispSummaryMove`/`dispSummaryBack`(摘要两段,拼摘要行);删 Checkbox 相关无(原 aria-label 在页面内联,随 Checkbox 一起删) |

### 2.2 交互细节

- 行按钮 toggle 仍写回 `disp.rows[idx].toBacklog`(数据结构不变,confirmDisposition 零改)。
- 摘要计数:rows.filter(toBacklog) / 其余。
- aria:按钮 `aria-label` 带 item 标题(可访问性,原 checkbox aria-label 语义平移)。

### 2.3 否决的备选

- 弹窗分两区(主区/例外区):对 N≈sprint 规模的行数是过度设计,否决。
- 仅加底部提示文字(方案B):不消除误点路径,只兜底,用户已选 A,否决。

## 三、测试

### 3.1 e2e(`group-complete-disposition.spec.ts` 改造;其余 4 例语义不动)

1. 覆写例(原第 2 例):checkbox click → 改点行内「回 Backlog」按钮;断言行进入警示态 + 摘要行「…回 Backlog 1 个」;确认后入池断言不变
2. 新增断言(挂第 1 例):默认态摘要「将转入…N 个;回 Backlog 0 个」无警示色——覆盖摘要行存在性
3. aria-label 同步调整(回Backlog {title} 保留在按钮上)

### 3.2 手工(dev `T106C`,对照 spec 0106 §3.1 场景)

1. 完成组 → 弹窗:每行有「回 Backlog」按钮,无 checkbox;摘要行显示「将转入「目标组」N 个;回 Backlog 0 个」
2. 点一行「回 Backlog」→ 行变警示态「将回 Backlog」+「撤销」;摘要变「…回 Backlog 1 个」且警示色
3. 点「撤销」→ 行回默认;摘要复原
4. 确认处置 → 未标记行入目标组、标记行入 Backlog(与摘要一致)
5. 取消处置 → 成员留已完成组(现状兜底不变)

## As-built(2026-11-06,PR #190)

- **落地**:`sprint/[id]/page.tsx` 处置弹窗行区重写:裸 Checkbox → 行尾显式 Button(默认 text「回 Backlog」/标记后 contained warning「撤销」,aria-label 平移「{动作} {title}」;标题行警示色)+ 常驻摘要行 Typography(「将转入「{targetGroupName}」 N 个;回 Backlog M 个」,M>0 warning.dark);新增 `targetLabel(d)`(弹窗顶部目标表达式单一实现)与 `targetGroupName(d)`(摘要组名:actives/planned 按 pick 匹配否则首个,新建态用 newName——与 confirmDisposition 落库逻辑镜像);原「勾选=回 Backlog」底部说明文字删除。`texts.ts` 新键 6(dispRowBacklog/dispRowMarked/dispRowUndo/dispSummaryMove/dispSummaryBack/dispSummaryTail)。数据结构 rows[].toBacklog 与 confirmDisposition 零改;零后端
- **e2e**:group-complete-disposition 覆写例改按钮点击(aria-label「回 Backlog 回池A2」)+ 默认态摘要(2 个/0 个)与标记态摘要(1 个/1 个)断言;其余 4 例未动;全量 86/86 绿(独占两轮)
- **流程事故注**:首推 CI format 红——本地只对改动文件跑了 --write,未跑全仓 --check,`targetGroupName` 两处超长行漏格式化;补 `ec9aad9` 修正。教训:提交 web 改动后、推送前跑 `npx prettier --check .`(AGENTS YPJ-121 已有此规矩,执行漏)
- **事故闭环**:本票修的正是 2026-11-06 T104Z 事故根因(checkbox 误语义致静默落池);数据已手工修复(test3/test4 入 Sprint 3);同日另立 YPJ-154(日志/可观测性权衡)、YPJ-156(e2e 套件并发互踩+断言窗),均 To Do 未排期
