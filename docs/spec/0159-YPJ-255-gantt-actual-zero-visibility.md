# spec 0159 — YPJ-255 甘特实际层零可见修复(A47)+ 进度覆盖语义 + Estimate 占位默认档

> 状态:v2,两项拍板已落(2026-11-22 用户确认),进编码。
> 台账 A47(`docs/planning/问题处理记录.md`),YPJ-249 验收发现,用户定性 bug。

## 一、需求与拍板

### 拍板 1:覆盖 = 进度,画在计划条上(替代时间流逝覆盖)

用户原话要旨:覆盖展示「计划中的任务完成了多少」,完全在计划线段上,不考虑实际开始时间(实际工时可能分段/补录,无可靠起止);提前开工的任务,覆盖照画在未来段上(业界缺 business baseline 是已知缺口,不归本票)。

- **覆盖占比**:清单完成率(checklistDone/Total,Total>0)优先;无清单但有落档工时与 Estimate → totalMinutes/estimateHours,cap 100%;两者皆无 → 无覆盖。
- **超期语义修订**:§6.2 原「超期进行中:覆盖满条」随时间流逝覆盖一并废除——超期仍由红条表达,覆盖恒按进度。(用户已确认覆盖语义,此条为连带修订,PR 描述显著标注。)
- 完成行(§6.3 绿条 + 清单深色遮罩)不动;进行中行原清单深色遮罩与覆盖合一,移除。

### 拍板 2:缺 Estimate 占位 = 建票表单默认预选「中」档

- EstimateScale(YPJ-180,spec 0126)内置默认 微2/小3/中5/大8/n13 天;默认取 **key=`m`**(自定义刻度无 m 时取排序中位档)。
- 落点 = **建票表单预选**(快创建估档下拉初始值),数据真实落库,零渲染假数据;详情页编辑表单不动;用户清空后不回弹。

### 范围/非目标

- 范围:后端 GanttRow 增 `totalMinutes`(REST 契约变更 → 同步 `api.http`);GanttChart 覆盖段重写;快创建估档默认。
- 非目标:business baseline、日历工作制(H8)、后端 Estimate 必填化、详情页表单。

## 二、根因(实锤)

`GanttChart.tsx` L151 `fillW` 时间流逝口径:`min(todayX,right)-left`,actualStart 不参与;计划 start 在未来 → 归零。拍板后此口径整体废除,非修补。

## 三、详细设计

### 后端(`backend/api` ProjectService)

- 注入 TimeEntry repository(ItemService 同款);`gantt()` 批量取可见 item 的工时(口径同 ItemService:`minutes` 优先,否则起止折算,进行中折算到 now),`GanttRow` 增 `totalMinutes: Long = 0`。
- 同步 `backend/script/api.http` 甘特响应示例。

### 前端

- `web/app/p/[key]/gantt/types.ts`:`GanttRow` 增 `totalMinutes: number`。
- `GanttChart.tsx`:叶行内
  ```ts
  const estHours = estimateHours(r.estimateValue, r.estimateUnit);
  const pct = (r.checklistTotal ?? 0) > 0
    ? (r.checklistDone! / r.checklistTotal!) * 100
    : estHours && r.totalMinutes > 0
      ? Math.min(100, (r.totalMinutes / 60 / estHours) * 100)
      : null;
  const fillW = doing && planIsBar && pct !== null ? (width * pct) / 100 : 0;
  ```
  进行中行不再画清单深色遮罩(与覆盖合一);`actPct` 仅保留于完成绿条。删 `axis.todayX` 在覆盖段的引用(今日线本身不动)。
- `web/app/p/[key]/page.tsx`:快创建估档预选——`estScales` 到达且估档态双空时,`key==="m"` ?? 排序中位档,写入 `newEstValue/newEstUnit`(effect deps 仅 estScales,用户清空不回弹)。
- Hover 卡:如有工时/进度展示引用覆盖口径,核对不撒谎(实现时核对,不改字段)。

### SRS v4.1 修订

- §6.2:覆盖段定义整段替换为进度口径(清单优先、工时/Estimate 兜底、皆无为无);删「超期进行中:覆盖满条」改为「超期由红条表达,覆盖恒按进度」。
- §3 新增 Estimate 占位一条:快创建默认预选中档(YPJ-255;刻度自定义时取 m ?? 中位)。

### e2e(`web/e2e/gantt.spec.ts`)

1. 边界回归:有清单的进行中行,黄覆盖宽 = 计划条宽 × 完成率(与 today 位置无关;构造计划条全在未来仍可见——A47 场景)。
2. 无清单有工时:覆盖 = totalMinutes/estimate。
3. 无清单无工时:无覆盖。
4. 建票快创建:估档下拉初始为「中」(如 e2e 已有建票流程挂断言)。

### 手工测试(落档 manual-test-cases.md YPJ-255 节)

1. 建条目:start=明天、due=后天、估档默认「中」、挂 In Progress、登记工时 → 甘特行计划条(蓝,位于未来)上有黄覆盖,宽∝工时/Estimate。
2. 有清单行:覆盖宽 = 完成率,深色遮罩消失。
3. 回归:完成行绿条+遮罩不变;无计划条行黄条不变;超期行红条、覆盖仍按进度。

## 四、开放决策

(无——两项已拍板;超期「覆盖满条」废除为连带修订,PR 显著标注供复核。)

## 五、As-built(完工后补)
