# 0142 — YPJ-229 甘特时线回归:日窗预设/今日线/零时长条

> 状态:As-built(2026-11-18,PR #230 已合)。关联:YPJ-229(父 219);spec 0140 v2 轴。
> 归因:prod 实测探针(Playwright evaluate + 截图),非猜测;e2e 全绿但手工显示问题由此复现。

## 一、需求(实锤三案)

1. **加载中点日窗预设,缩放静默丢失**:进页数据未到时(`rows===null`,滚动容器未渲染)点 1/2/3/5 日(或「今日」),`goToDayWindow` 的 `setZoom` 分支因 `containerW=0` 不执行;数据到达后 center 守卫阻止重居中 → 停留 fit 挤压视图。用户表现:「要手动放大两次才能看」(与 YPJ-234 缺省视窗互补,本票修交互丢失)。
2. **视点日=今日按 00:00 居中,当前时刻偏出 1 日窗**:今日线画在 `now`,而居中算的是日期 00:00;1 日窗(px/天≈746)下 14:xx 距 00:00≈434px > 半屏 373px → 今日线不可见。
3. **零时长条不可见**:start=due 同刻(YPJ-207 11-18T00:00→00:00)画 1.2px 细丝。

## 二、详细设计(web/app/p/[key]/gantt/page.tsx)

1. **日窗挂起补放**:`pendingWinRef`(undefined=无挂起)。`goToDayWindow` 在 `rows===null || containerW<=TITLE_W` 时只记挂起;`useEffect([rows, containerW])` 就绪后补放。直接生效路径清挂起,防切范围后补放旧值。
2. **居中重放**:`lastCenterStartRef` 记录上次居中时的 `axis.start`;轴起点变化(数据到达/切范围)后同视点日重居中(center effect 判定放宽)。缩放(zoomTo/Ctrl+滚轮)仍不重触发(start 不变)。
3. **今日=当前时刻居中**:视点日===fmtDay(今天)时,x 取 `axis.todayX`(now),非 00:00。
4. **零时长条最小可见宽**:hasStart 分支 `Math.max(right-left, 3)`(start-only 无 due 的标记宽也 3px,<20 e2e 断言仍过;due-only 菱形不变)。

否决:按钮禁用等待数据(体验差);进页自动套上次日窗(归 234 缺省视窗)。

## 三、手工测试用例

1. 刷新甘特,数据加载中立即点「1日」→ 数据到达后自动变 1 日窗(小时刻度在),今日线在视口内居中附近。
2. 点「今日」→ 今日时刻居中,蓝色今日线可见。
3. 1 日窗:今日时刻在视口中心(非 00:00);◀/▶ 移视点日后仍按日期 00:00 居中。
4. YPJ-207(11-18 00:00 起止)在含 11-18 的窗口可见 3px 红丝条(超期)。
5. 缩放 ±/Ctrl+滚轮:锚点不跳(回归)。

## 四、As-built(2026-11-18)

- 三案按 §二落地(gantt/page.tsx):`pendingWinRef` 挂起-补放、`lastCenterStartRef` 轴变化重放+今日按 `todayX` 居中、零时长条 `Math.max(...,3)`;归因与 root cause 见台账 A14,均 prod 探针实锤。
- e2e 转正:gantt.spec 新增 3 例(挂起补放/今日时刻居中/零时长条 ≥3px),gantt/board 共 23 例全绿;`gradle build` 后端未动不涉。
- 顺带修正(设计外,实锤后纳入):`applyDayWindow` 同时重置 `lastCenterRef` 与 `lastCenterStartRef`,使挂起补放后同视点日也能重居中(否则补放的 setCenter 同值不触发 effect)。
