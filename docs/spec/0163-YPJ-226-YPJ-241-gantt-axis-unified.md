# spec 0163 — YPJ-226 × YPJ-241 甘特横轴统一(工作制轴 + 缩放×刻度分层)

> 16h 合并票(226 8h + 241 8h)。2026-11-22 用户拍板五项(§五已闭合),本版为终版开工基线。

## 一、需求

### 226(工作制轴)
- R1 工作制刻度轴:8h 工作时长语义,**周末折叠**(不占横向空间),workdayStart/End 可调
- R2 24h 墙钟刻度轴:钟表线性(现状),不跳周末
- **制式项目级可选;系统默认 = 工作制**(2026-11-22 拍板:工业标准为默认;用户个人工作室项目选墙钟制)
- R3 排期按工作日历铺时长——后端 dueFrom 接线已存在(spec 0138/0146);本票缺口 = 周末跳过 + 制式字段

### 241(横轴统一)
- R1 缺省视窗全数据入画——YPJ-234 已承接,本票不含
- R2 缩放突破 `MAX_PPD=2400` 支持亚日窗,刻度梯子下探 30/15/5 分钟(拍板:做)
- R3 刻度分层增年行,表头 cap 3→4(拍板:做);季行探针后定,暂不做

### 加班语义(拍板案 B+)
- 工作窗可配置(workdayStart/End)即加班吸收手段;工作制/墙钟制切换可查真实钟表时刻
- **显式加班建模(加班时段入历)不做**,列开放决策,候选归 YPJ-262(日历视图)语境再议

### 非目标
- 节假日历(另票);泳道/连线(209/260);日历视图(YPJ-262 backlog)

## 二、拍板记录(2026-11-22)

| # | 决策 | 结论 |
|---|---|---|
| 1 | 轴制式 | 项目级可选,**默认工作制**;墙钟制为项目选项 |
| 2 | 两制呈现 | 切换式(整轴按制式渲染) |
| 3 | 周末跳过落点 | 制式隐含:工作制恒跳,墙钟制恒不跳;不加独立布尔列 |
| 4 | 亚日窗 | 抬 MAX_PPD + 梯子下探 |
| 5 | 表头层数 | 加年行,cap 4 |
| 6 | 折叠 vs 灰显 | **折叠**(周末零宽度;⚠ 含推论:非工作时段(夜间窗)同样折叠,1 工作日 = workdayStart–End 宽度——用户明示「折叠」于周末,夜间折叠为工作时长语义的自然推论,dev 验收时复核) |

## 三、设计

### 3.1 后端:制式字段 + 周末跳过
- Flyway:`projects` 加 `work_mode VARCHAR NOT NULL DEFAULT 'wall' + CHECK (work_mode IN ('work','wall'))`;**存量项目不动(= wall)**;新建项目 API 缺省 `'work'`(create DTO 缺省值,列默认保守)——⚠ 动 schema → 同步 `entities.puml`
- `WorkdayCalculator` 加构造参数 `skipWeekends: Boolean = false`:铺算/`hoursBetween` 遇 Sat/Sun 跳到周一/次日窗起点;**窗口正交:全天窗+跳周末 = 只折周末,合法**(2026-11-22 二次修订:原「work 必须真窗」设计会波及全体新建项目 fixture,且 24h+跳周末本身是合理形态)
- `workMode='work'` ⇒ skipWeekends=true;`'wall'` ⇒ 现状行为;新建项目缺省 `work`(存量全 `wall` 不动)
- `workdayBounds` 扩展返回 mode;schedule 铺算(ItemService 4 处)与 estimate 反推自动继承
- 改 API(project create/PUT workMode)→ 同步 `backend/script/api.http`

### 3.2 前端:折叠几何(xOfT 非线性)
- `useTimelineScale` 按模式分派:
  - `wall`:现状钟表线性 `xOfT`,一切不动
  - `work`:预计算工作段数组(逐日 [ws,we] 跳周末)→ `xOfT(t)` = 累计工作毫秒 × pxPerWorkMs;**逆映射 `tOfX`** 供缩放锚点(zoomTo 的 dataDays 换算改走逆映射)
- pxPerDay 语义在 work 模式下改 pxPerWorkDay(每工作日像素);fit/GANTT_MIN_PPD/MAX_PPD 换算随制式
- 轴范围锚定:工作模式下范围 = 首/末工作时刻(数据日期落在周末→钳到邻近日窗界)
- 刻度生成按模式分派:hour tick 只吐窗内时刻;day tick 落当日窗起点;work 模式无周行(周界含周末,无位);月行落月首工作日;年行落年内首个工作日
- 条/今日线/包络/汇总/hover 全走 xOf/xOfT/xEndOf,自动继承折叠;GridLines(239)用 headerRows tick,自动适配
- 墙钟制不渲染任何工作制标注;工作制下因折叠无「周末淡带」需求

### 3.3 缩放深化(两模式同构)
- `MAX_PPD` 2400 → 4800(wall);work 模式对应 pxPerWorkDay 上限等比;YPJ-230 承诺收窄为「缺省态内容宽不破」,显式缩放放行
- 小时梯子扩 `[5,10,15,30]min` 档(仅高 ppd 命中);work 模式梯子单位 = 工作小时
- 年行门控:wall `pxPerDay*365 <= 44` 吐 `YYYY`;cap 3→4,丢弃序粗→细保 4

### 3.4 文件清单
- backend:persistence/Entities.kt、Flyway V next、api/business/project(ProjectService/DTO)、workday/WorkdayCalculator、item/ItemService(workdayBounds)、api.http、entities.puml
- web:gantt/hooks/useTimelineScale.ts(主战场)、types.ts、utils/timeCalculations.ts、GanttChart.tsx(轴 prop 传递)、page.tsx(mode 读取)

## 四、手工测试用例

1. 工作制新项目:estimate 5d 起周五 → 条跨周末顺延,周末在轴上零宽度,周五紧贴周一
2. 墙钟制项目(含存量):现状完全回归——铺算跨周末不顺延、钟表线性刻度
3. 工作制缩放:刻度只现窗内时刻;缩到最小见年行;放大过原上限见 30/15min 档;锚点缩放不漂(逆映射)
4. 今日线在工作制下落当前时刻的工作位(周末/夜间钳到最近工作位)
5. workdayStart/End 调整(如 09:00-18:00)→ 条宽与刻度随动
6. api.http:project create 缺省 work、PUT 切 wall、存量项目读值 wall
7. dev server 建双制式测试项目+数据供用户验(agent 负责)

## 五、开放决策

- 显式加班建模:不做(见 §一);后议
- 季行:探针后定
- ⚠ 夜间折叠推论(§二#6):dev 验收时用户复核,不合意再返工

## 六、As-built(完工后补)
