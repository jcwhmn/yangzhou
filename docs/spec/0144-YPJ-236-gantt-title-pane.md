# 0144 — YPJ-236 甘特标题列独立面板:自有横滚 + 右边线 + 异色区分

> 状态:实现。需求源:用户 2026-11-18 指示「title 列和甘特图列独立分开,title 列可以有自己的横向滚动条,最后能用不同的底色来区分它们」;原票 236(列右边线 + title 过长列内底部横滚)提前开工,需求按本指示细化。
> 关联:YPJ-236(依赖 YPJ-230 分支,叠层);spec 0143 的 A 案(120px 截断省略)被本票**自然宽 + 列内横滚**取代,component="div" 机制保留。

## 一、需求

1. 标题列与甘特区**独立双面板**:甘特区横向滚动时标题列不动;标题列横向滚动时甘特区不动。
2. 标题列内容超宽(标题自然宽总和 > 280)时,标题列**底部出现自己的横向滚动条**;标题不再截断省略,按自然宽呈现。
3. 两区**不同底色**区分:标题列浅灰(grey.50)+ 右边线(divider,即原票「列右边线」);甘特区默认底。
4. 纵向始终行对齐:两侧行结构同高(表头行 24px、数据行 minHeight 44、同 borderBottom),页面级滚动。

非目标:两区 hover 联动(后续可加);纵向独立滚动(无此需求,行数少);标题列宽上限(超长标题就长滚,不设 cap)。

## 二、详细设计(web/app/p/[key]/gantt/page.tsx)

### 页面示意图(贴合实况)

```
┌─标题列 grey.50(自有横滚)─┐┌─甘特区(自有横滚,缩放)──────────────┐
│ (表头占位 24px × N 行)     ││ 2026-11   W47    W48      (刻度行)   │
├───────────────────────────┤├──────────────────────────────────────┤
│ ▼ YPJ-217 甘特图第一期…   ││ ═══════汇总████══════════    [To Do] │
│   └ YPJ-219 甘特 S2…      ││      █████条█████                    │
│     YPJ-229 时线显示修正 3h││         ▍                            │
├───────────────────────────┤├──────────────────────────────────────┤
│ ↑标题列底部横滚(超宽时)  ││ ↑甘特区横滚(缩放/fit)               │
└───────────────────────────┘└──────────────────────────────────────┘
```

### 操作流程(状态×动作真值表)

| 前置状态 | 动作 | 行为 |
|---|---|---|
| 标题内容自然宽 ≤ 280 | — | 标题列无横滚,内容铺满列宽(minWidth 100%) |
| 标题内容自然宽 > 280 | — | 标题列底部横滚出现,标题按自然宽完整呈现 |
| 任意 | 滚动甘特区(拖滚动条/Ctrl+滚轮/预设/◀▶) | 标题列纹丝不动 |
| 任意 | 滚动标题列 | 甘特区纹丝不动 |
| 任意 | 页面纵向滚动 | 两列行对齐(同高同行框) |
| 任意 | 缩放/切范围/视点日 | 只重建甘特区;标题列不随之变化 |

### 代码修改计划

1. **外层容器**:单滚动容器 → `Stack direction=row`(border+radius+overflow hidden 在外层);左=标题面板,右=甘特面板(flex:1, minWidth:0)。
2. **标题面板**(`data-testid="gantt-title-pane"`,width=TITLE_W,overflowX auto / overflowY hidden,bg grey.50,右 border divider):内层 `width:"max-content", minWidth:"100%"`(CSS fit-content 定宽,零 JS 测量);表头占位行(高 24×headerRows.length,borderBottom);`scheduled.map` 逐行:折叠钮+number Chip+标题 Link(Typography 保留 component="div"+noWrap+testid,**去 maxWidth:120**)+estimate/blocked Chip。行 borderBottom + minHeight 44。
3. **甘特面板**(scrollRef 移此,`data-testid="gantt-scroll"` 保留):表头行去掉 sticky 占位;数据行去掉标题 Box,保留 bar-area(today 线/汇总/条/整块覆盖链接)+ 右缘 sticky 状态 Chip。行 borderBottom + minHeight 44。
4. **几何量换算**:containerW 改测甘特面板 clientWidth——`fit = containerW/spanDays`(原 −TITLE_W);日窗 `setZoom(containerW/days)`;居中 `x − clientWidth/2`(原 −(clientW−TITLE_W)/2);zoomTo 锚点同理;就绪守卫 `containerW <= TITLE_W` → `<= 0`。
5. **异色**:标题面板 grey.50 + 右边线;原行内 sticky 标题格的 bgcolor background.default 移除(面板底色接管);甘特面板默认底。

否决:JS 测两列行高并同步(结构同高即可,加同步是投机抽象);hover 联动(非需求);标题列可折叠(无此需求)。

### e2e 影响

- 0143 用例「行标题截断」改为「行标题完整呈现」(自然宽 ≥200,断标题不再被 120 截断);`avail = clientWidth−280` → `clientWidth`。
- 存量 poll「scrollWidth ≥ clientWidth−280」→「≥ clientWidth−2」(fit 恰铺满甘特面板)。

## 三、手工测试用例(rebuild prod 后,与 230 一起验)

1. 217/208 等行:标题完整可读;拖标题列底部滚动条 → 只有标题列动,甘特区不动;反向亦然。
2. 拖甘特区滚动条/Ctrl+滚轮缩放:标题列纹丝不动;标题列与甘特区行始终对齐(逐行扫一眼)。
3. 两区底色可区分:标题列浅灰+右边线,甘特区白底。
4. 窄标题项目(全短标题):标题列无横滚,铺满列宽。
5. 0143 用例回归:1 日窗恰 1 天;今日线/零时长条不回归。
