# spec 0159 — YPJ-255 甘特实际层零可见修复(A47)

> 状态:设计稿 v1,待用户对三方案拍板后进编码。
> 台账 A47(`docs/planning/问题处理记录.md`),YPJ-249 验收发现,用户定性 bug。

## 一、需求

进行中叶行 **actualStart < 计划 start**(提前开工)时,黄覆盖段 = `start→min(今天,条尾)` 归零 → 落档工时在甘特实际层完全不可见。

- **范围**:仅进行中单条覆盖模型(SRS v4 §6.2)的覆盖段几何;§6.3 完成双条、case1/3b 无计划条黄条不受影响。
- **非目标**:不引入进度百分比计算、不动后端甘特 API 字段、不做日历工时换算(仍日历时间等价,H8 落地时一并改)。
- **验收**(票面):
  - [ ] actualStart < planStart 的进行中行实际层可见
  - [ ] SRS §6.2 更新
  - [ ] e2e 边界用例

## 二、根因(实锤,spec 0158 交付代码)

`web/app/p/[key]/gantt/GanttChart.tsx` L151:

```ts
const fillW = doing && planIsBar
  ? Math.min(Math.max(Math.min(axis.todayX, right) - left, 0), width)
  : 0;
```

`left` = 计划条左端(计划锚),actualStart 参与不了覆盖段;actualStart 早于计划 start → `todayX < left` → fillW=0。渲染按 spec 字面正确,语义违反用户意图——故属**语义决策**而非单纯 bug 修补,须拍板(七规 #6)。

## 三、方案对比(三选一或组合,待拍板)

### 方案① 覆盖段下界取 min(计划start, 实际start)

```ts
const fillLeft = Math.min(left, actLeft);        // 覆盖段可伸出计划条左端
const fillW = Math.min(Math.max(Math.min(axis.todayX, right) - fillLeft, 0), ...)
```

- 优点:改动最小(一行);实际层从 actualStart 起可见,与「进行中从实际开始流逝」语义一致;不破坏单条覆盖模型。
- 缺点:黄覆盖段左端可超出蓝计划条左端,条形出现「蓝短黄长」的错位观感(黄段悬浮无蓝底)。
- 语义:实际早于计划 → 已流逝 = 今天 − 实际开始。符合直觉。

### 方案② 计划条锚实际开始(actualStart < planStart 时整个计划条左移)

计划条左端 = `min(计划锚, actualStart)`;覆盖段公式不变(仍 start→min(今天,条尾)),自然非零。

- 优点:蓝黄始终同一条,无错位观感;计划条位置反映现实开工点。
- 缺点:改动大——计划条几何牵连 due 端点、偏移徽标、父行包络(§7.2 从渲染结果反推,自动跟随但 e2e 断言面广);且「计划」语义被实际数据改写,计划/实际边界变模糊;完成态(§6.3)同行的计划条是否也移?语义涟漪未定义。
- 语义:把「计划」重定义为「计划∩现实起点」,与 SRS §5 计划层锚定义冲突,需大改 §5+§6.2。

### 方案③ 回退第二条实际条(actualStart < planStart 的进行中行画双条)

打破 §6.2「进行中恒一条线」,对该边界情形回退双条(下黄条 actualStart→今天,同 §6.3 完成态形态)。

- 优点:实际层信息最完整(能看到实际起点与计划起点的偏差,偏差即视觉传达)。
- 缺点:同票引入两种进行中形态(单条/双条按边界切换),渲染分支+e2e+手测面翻倍;且 221 拍板过的「进行中恒一条线、零徽标」原则被开洞,需在 SRS 写成例外条款。
- 语义:最保守(不动覆盖段定义,只加特例)。

### 对比小结

| | 改动量 | 观感一致性 | SRS 改动 | 221 拍板原则 |
|---|---|---|---|---|
| ① 下界取 min | 一行+测试 | 黄段可伸出蓝条(轻微) | §6.2 一句 | 不破坏 |
| ② 锚实际开始 | 大(牵连包络/徽标) | 最好 | §5+§6.2 | 不破坏但计划语义重定义 |
| ③ 双条特例 | 中(新分支) | 完成态一致 | §6.2 例外条款 | 开洞(恒一条线) |

**agent 倾向①**(最小改动、不破坏既有拍板),但黄段伸出属可见的品味型观感问题,不代选,请拍板:①/②/③/组合(如①+③:下界取 min 且超 planStart 左界时……不必,①已覆盖)。

## 四、详细设计(按方案①写,拍板②/③则本节重写)

- `GanttChart.tsx` 叶行内:覆盖段左端 `fillLeft = Math.min(left, act ? actLeft : left)`;`fillW = doing && planIsBar ? clamp(min(todayX, right) - fillLeft, 0, width + (left - fillLeft)) : 0`(覆盖段可越出计划条右端已被 `min(todayX, right)` 钳住;左端允许伸到 actLeft)。
- `ActualBar.tsx` 不动(覆盖段由 PlanBar 的 fill 绘制,路径不变)。
- SRS v4 §6.2:「条内已流逝段(start→min(今天,条尾))」→「已流逝段(min(计划start, 实际start)→min(今天, 条尾);实际早于计划时覆盖段伸出计划条左端,YPJ-255)」。
- e2e `web/e2e/gantt.spec.ts` 新增边界用例:actualStart < planStart 的进行中行,断言黄覆盖存在且左端 x < 计划条左端 x、右端 = min(today, 条尾)。
- 手测档 `docs/testing/manual-test-cases.md` 立节(构造一条 start 在未来、已挂 In Progress 且有落档工时的 item,dev 3001)。

## 五、手工测试用例(供用户 build/debug)

1. dev 环境建 item:计划 start=明天、due=后天,挂 In Progress、登记实际工时(actualStart=今天早些)。
2. 甘特:该行计划条(蓝)可见,黄覆盖段从 actualStart 位置开始、右端到今天/条尾取小——实际层可见。
3. 回归:正常行(start 已过)覆盖段形态不变;无计划条行黄条不变;完成行双条不变。

## 六、开放决策

- [ ] 方案拍板(①/②/③,§三)
- (拍板后清)SRS §6.2 修订按拍板结果落。

## 七、As-built(完工后补)
