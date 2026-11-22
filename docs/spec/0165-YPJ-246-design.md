# 0165 — YPJ-246 甘特视觉对齐:设计

> 配套 SRS:`0165-YPJ-246-srs.md`(需求基准,措辞含用户原话)。本文件 = 现状审计 + 代码级修改计划 + 操作流程真值表 + 页面示意图 + 否决备选。

## 一、现状审计(2026-11-25,逐项对源文档 7 域)

| 源域 | 现状 | 结论 |
|---|---|---|
| 1 颜色-父条橙 | `SummaryBar` `warning.main` 橙 8px | ❌ 本票改灰蓝 6px |
| 1 颜色-进行中黄覆盖 | `ProgressOverlay`(YPJ-255 进度语义) | ✓ 不动 |
| 1 颜色-完成双条蓝绿 | `PlanBar` top:5 蓝 / `ActualBar` top:24 绿 | ✓ 不动 |
| 1 颜色-超期红条 | `PlanBar` `error.main` | ✓ 不动 |
| 1 **底色三分类** | 行仅 hover `action.hover`,无状态底色 | ❌ 本票新增 |
| 2 今日线贯穿 | 逐行 `TodayLine` info 2px top:0~bottom:0 | ✓ 不动 |
| 3 父行降权/徽标 | 橙条抢权重;「+N」已贴条尾 top:0 | ❌ 条改色/细化;徽标位置已对 |
| 4 覆盖严格落地 | YPJ-255(清单/工时为分子) | ✓ 不动 |
| 5 信息密度 | 默认缩放 GANTT_MIN_PPD ✓;零宽 3px ✓;徽标贴条尾 ✓;**ppd 无记忆 ❌**;**标题无悬停全显 ❌**(noWrap+横滚);标题列宽记忆 ✓ | 两项进本票 |
| 6 双条对齐 | top:5/24 固定,行高 44 | ✓ 不动 |
| 7 未排期组 | 无弱化(白底黑字) | ❌ 本票弱化 |
| 7 状态列色点 | 无状态列 | 由底色取代(SRS 决策,不做) |
| 7 图例 | 无图例组件(页底曾有文字,已随重构消失) | ❌ 本票浮层 |
| 7 刻度降级 | `useTimelineScale` 梯子按 MIN_LABEL_PX | ✓ 不动 |

## 二、结构

```text
app/p/[key]/gantt/
├── GanttChart.tsx            # 行渲染(右半);+行底色 statusTint;SummaryBar 色值
├── components/
│   ├── TitleColumn.tsx       # 左半标题格;+行底色 +父行字重 +悬停 title
│   ├── SummaryBar.tsx        # 橙→灰蓝 #94A3B8,8→6px
│   ├── LegendPopover.tsx     # 新增:图例浮层(条五色+底色三分类)
│   └── UnscheduledGroup.tsx  # 弱化:文字 text.secondary、去底色
├── hooks/
│   ├── useGanttData.ts       # +statuses(statusType+position,浓淡分档输入)
│   └── useTimelineScale.ts   # +ppd 记忆读写(yz-gantt-ppd)
├── page.tsx                  # 工具栏挂图例按钮
└── statusTint.ts             # 新增:StatusType→底色/浓淡档纯函数(两端共用,可单测)
```

## 三、关键实现

### 3.1 底色(核心)

`statusTint.ts` 纯函数(无 React 依赖,便于单测):

```ts
export const TINT = {
  START: "#EEF2F6",
  FINAL: "#E7F5EC",
  ACTIVE: ["#FFF8E1", "#FFEDB8", "#FFE28E"], // 浅→深
};
/** 父行/非 item 行 → null(透明) */
export function statusTint(
  statusType: string | null,
  tier: number | null, // ACTIVE 浓淡档 0..2,非 ACTIVE 传 null
): string | null;
/** 项目 ACTIVE 状态(按 position 升序)→ 状态名 → 档;供行计算 tier */
export function activeTierOf(
  activeStatuses: { name: string; position: number }[],
  statusName: string,
): number | null;
```

- `useGanttData` 增拉 `GET /api/projects/{key}`(statuses 已含 statusType+position,看板页同款消费),向行组装处供 `activeTierOf` 的档位映射(状态名→档);
- `GanttChart` 行 Box 与 `TitleColumn` 的 `TitleCell` Box 各加 `bgcolor: tint ?? "transparent"`(父行 tint=null;悬停 `action.hover` 现有规则不动,叠加在底色上);
- 父行判定复用既有 `hasKid(r)`。

### 3.2 SummaryBar

`bgcolor: "warning.main"` → `bgcolor: "#94A3B8"`(注释:spec 0165 灰蓝降权);内条 `height: 8` → `6`。

### 3.3 ppd 记忆

- `useTimelineScale` 初值:`localStorage["yz-gantt-ppd"]` 解析数值,夹紧 `[GANTT_MIN_PPD, MAX_PPD]`;无记忆走现有 fit;
- 写点:用户显式缩放(± 按钮回调)与 fit 完成后,写当前 ppd;读取/解析失败静默回退 fit。

### 3.4 标题悬停

`TitleColumn` 标题 Typography 加 `title={r.title}`(原生 tooltip,零依赖;否决 MUI Tooltip:整行点击热区冲突)。

### 3.5 未排期组弱化

`UnscheduledGroup` 组头文字色 `text.secondary`,底色透明;桶内行不变。

### 3.6 图例浮层

`LegendPopover`:工具栏(缩放控件旁)`IconButton`(PaletteOutlined)+`Popover`;内容两段小表:底色三分类(三色块+文案,浓淡注「按工序先后浅→深」)+ 条五色+v4 §11.2 今日线说明;数据来自 §3.1 的 TINT 常量(单一来源)。

## 四、操作流程真值表(状态 × 动作)

| # | 状态/场景 | 动作 | 期望 |
|---|---|---|---|
| 1 | 项目含 START/ACTIVE×2/FINAL 状态各 ≥1 票 | 打开甘特页 | 三类行底色即可扫读分区;浓淡按状态 position |
| 2 | 父行(有子孙) | 打开页 | 无底色;灰蓝细汇总条;标题加粗 |
| 3 | 任意行 | 鼠标悬停 | action.hover 反馈叠加,不覆盖底色语义 |
| 4 | 任意行 | 悬停标题 | tooltip 显完整标题 |
| 5 | 缩放后(±/fit) | 离开甘特页再进 | ppd 保持 |
| 6 | 任意 | 点工具栏图例按钮 | Popover 列底色+条色;再点/点外关闭 |
| 7 | 未排期组存在 | 打开页 | 组头弱化不抢眼;展开/收起行为同现状 |

## 五、页面示意(ASCII)

```text
┌─────────────────────────┬──────────────────────────────────────┐
│ 标题列(同底色)          │ 时间轴区(同底色)                     │
├─────────────────────────┼──────────────────────────────────────┤
│ ▼ DEMO-3 组装阶段(父)  │  ▬▬▬ 灰蓝细汇总条(无底色)          │ ← 父行透明
├─────────────────────────┼──────────────────────────────────────┤
│ DEMO-4 设计  To Do       │ ░░░░░░░░░░░░░░░ 蓝计划条 ░░░░░░░░░  │ ← START 灰蓝底
├─────────────────────────┼──────────────────────────────────────┤
│ DEMO-5 开发  In Progress │ ▒▒▒▒▒ 蓝条+黄覆盖 ▒▒▒▒▒▒▒▒▒▒▒▒▒▒  │ ← ACTIVE 琥珀浅档
├─────────────────────────┼──────────────────────────────────────┤
│ DEMO-6 联调  Code Review │ ▒▒▒▒▒▒▒▒ 红超期条 ▒▒▒▒▒▒▒▒▒▒▒▒▒▒  │ ← ACTIVE 琥珀深档
├─────────────────────────┼──────────────────────────────────────┤
│ DEMO-7 测试  Done        │ ███ 蓝(上) / ███ 绿(下) ███       │ ← FINAL 淡绿底
├─────────────────────────┼──────────────────────────────────────┤
│ ▶ 未排期(2)            │  (text.secondary 弱化)              │
└─────────────────────────┴──────────────────────────────────────┘
  ░ 灰蓝底(START)   ▒ 琥珀底(ACTIVE,浅→深)   █ 淡绿底(FINAL)
```

## 六、否决备选

| 备选 | 否决理由 |
|---|---|
| 底色用蓝系浓淡(计划色族) | 与「蓝=计划条」冲突,扫读层与细读层抢同一色族 |
| 底色红/橙表达超期 | 超期已是条级红;底色再红 → 双载体同语义,噪音 |
| 父行按子孙聚合着色 | 聚合规则(任一 ACTIVE?全 FINAL?)语义含糊,误导;留开放决策 |
| MUI Tooltip 包标题 | 点击热区与整行 Link 冲突,原生 title 零成本 |
| e2e 断言色值字符串 | 主题换算脆弱;断言改用「三类行 computed background-color 互不相等 + 父行为 transparent」 |
| ppd 记忆按项目分键 | 单用户单浏览器场景,全局键更简;需要时再分 |

## 七、测试映射

- **单元**(web tsc 之外的纯函数):`statusTint`/`activeTierOf` 真值表(START/FINAL/ACTIVE 3 档/1 状态/2 状态/null 父行);
- **e2e**(新增/改):造 START、ACTIVE、FINAL 三票 → 断言三行 `background-color` 互异且父行为 transparent;缩放记忆(缩放→reload→同 ppd);图例按钮开关;既有 130 用例回归;
- **手工**(落 `docs/testing/manual-test-cases.md` YPJ-246 节,验收清单 = SRS §四逐条):三色扫读、浓淡可辨、条色不被淹没、悬停叠加、父行降权、未排期弱化。

## 八、REST/契约

零后端改动(消费既有 `GET /api/projects/{key}` statuses);无新端点、无 DTO 变化,api.http 不动。
