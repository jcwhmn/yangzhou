# 0143 — YPJ-230 甘特日线回归:标题溢出叠压 + 1 日窗夹制

> 状态:实现。归因:prod 探针实锤(2026-11-18 晚,3000/8080 + YPJ 真实数据,Playwright 截图 + evaluate 坐标 + computed style),非猜测。
> 票面仅一句描述(「逐日粒度(1d/2d 步长)的显示问题」),219 回归族规矩:先探针实锤再修。

## 〇、探针实锤(先于设计)

几何核对结论:**日/时/周刻度、条、今日线坐标全部正确**(1 日窗 pxPerDay=720,今日线=now 22:30 居中于视口中心;零时长条 3px 在 11-18 刻度上;周行刻度落在周一 00:00;2 日窗精确显示 2.0 天)。像素扫描复核红丝条 x=532 与 11-18 刻度 x 对齐。**「日线」问题不在几何,在以下两案**:

- **A. 行标题溢出叠压(全缩放级,fit 同样复现)**:行标题 Link 内 `Typography variant="caption"` 根元素是 **`<span>`(display:inline)**——`max-width`/`overflow` 对 inline 元素无效,noWrap 只给 nowrap → 文本画出 120px 布局盒最长 ~350px,叠压行尾 estimate chip(207「+items」被 4h 压住、217「时区适配」被 7d 压住等),并压入条区。**220 包 Link 引入**(此前 Typography 是 flex item 被 blockify,overflow 生效;包 Link 后变 inline 内容,裁剪失效)。
- **B. 1 日窗夹制(1920 屏)**:`MAX_PPD=720` < 内容宽 1386 → 点「1日」实际显示 ~1.93 天,与「2日」(693,精确 2 天)几乎无差别。2/3/5 日窗不夹。**这就是「1d/2d 步长显示问题」的主体**。
- **C. 待拍板,本票不动码**:「今日」按钮 `goToDayWindow(null)` → `setZoom(null)`=fit,即「今日=全览+回中」,从 1 日窗点今日丢失缩放。219 v2 原生语义(非回归)。两案:①保持当前缩放仅回中;②维持现状。**按诊断七规 #6 摆两案待用户拍板**。

## 一、需求

1. 行标题在标题列内截断(省略号),不叠压 estimate/blocked chip、不压入条区;点击仍进详情。
2. 「1日」日窗在内容宽 ≤2680px(约 2.7K 屏)精确显示 1 天;2/3/5 日窗与 fit 行为不变。

非目标:未排期桶 RowTitle(无 nowrap,自然换行不叠压,不在病区);缺省视窗(YPJ-234);「今日」缩放语义(C 待拍板)。

## 二、详细设计(web/app/p/[key]/gantt/page.tsx)

1. **标题截断(修 A)**:Typography 加 `component="div"`——span(inline)→ div(block),sx 既有 `maxWidth: 120` + noWrap 的 overflow:hidden/textOverflow 立即生效,裁剪+省略号回到盒内;Link 既有 `minWidth: 0` 使 flex 收缩正常。外层 Link 加 `data-testid={`gantt-title-${r.number}`}` 供 e2e 断言。
   否决:改 Link style 加 overflow(裁剪盒上移一层,Typography 的 maxWidth 留着变死代码);Typography sx 加 display:block(与 component="div" 同效,但语义上 caption 本就映射 div 更直白)。
2. **MAX_PPD 720→2400(修 B)**:常量改值,注释改「1日窗在内容宽 ≤2680 成立」(原「1h=30px 细节级」语义随 v2 日窗承诺废弃)。±/Ctrl+滚轮/预设统一走 `Math.min(max, MAX_PPD)`,一处常量全覆盖。e2e 视口(内容 ~720px)数值不变,存量用例无感。4K 全屏(内容 >2680)1 日窗仍夹,留待真需求(234 缺省视窗会重审默认值)。

## 三、手工测试用例(rebuild prod 后)

1. 1920 屏甘特 fit:207/208/217/223 等带 estimate 的行,标题在 chip 前省略号截断,无叠压;点击标题仍进详情。
2. 点「1日」:视口恰显示 1 天(相邻日刻度间距=可视时间轴宽);小时刻度步长 1h。
3. 点「2日」「3日」:显示 2/3 天(回归)。
4. 「今日」:行为现状(全览+今日居中)——验证时顺手对 C 拍板。

## 四、e2e 转正(gantt.spec,单测两段)

1. **标题截断**:建长标题 item(>120px),排期入轴,断言 `gantt-title-*`(Typography 本体)rect 宽 ≤121px(修复前 inline 文本画出盒 ~350px)。
2. **1 日窗不夹**:`setViewportSize(1920×1080)`,点 win-1,断言日刻度相邻间距 ≈ clientWidth−280(±5%;修复前该值=720)。
