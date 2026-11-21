# spec 0158 — YPJ-221 甘特 S4:Milestone/Release 时点上图(竖线+旗标带)

> 状态:**待过目(r2,2026-11-22 用户拍板 A′——竖线+旗标带;泳道方案降级为演进方向记 §十)**
> r1(泳道)→ r2 变更:形态从「顶部两泳道」改为「贯穿行区竖线 + 顶部旗标带」;改动面 7 文件 → 5 文件(TitleColumn 不再动);CP 6 → 4。
> 源:主票 YPJ-217(S1–S5 切片之 S4);旧甘特 PRD §二R5;Milestone-Release PRD US-M11(本票兑现)。SRS v4 §1.4 非目标项由此票转正;**SRS 正文修订延后到 As-built**(v4.1 增量节)。

## 一、需求

### 1.1 定位
甘特页把项目级里程碑(Milestone)与发布(Release)的**时点**画成贯穿行区的竖线(今日线同族),线顶旗标携带身份。条与线直接相交——「item 落在里程碑/发布之后」一眼即得,是甘特核心读数「与计划的差」的直接延伸。

### 1.2 范围
- 竖线:milestone targetDate(release releasedDate),线型=虚(计划时点)/实(已发生),颜色按状态。
- 旗标带:表头下 ~20px/层 薄带,每线一面旗(名称 + gate N/M + version),旗标可点跳管理页,hover 全信息 tooltip。
- 图例文字补一句。

### 1.3 非目标
- 编辑/拖拽/新建(管理走既有页面);线/旗标不进标题列。
- scope 过滤联动高亮(候选记 §十)。
- 今日线改造(现状逐行绘制机制不动;marker 线从旗标带顶起,今日线仍从行区起——已知轻微不一致,不处理)。
- SRS 正文修订(As-built 时)。

## 二、数据语义(零后端改动)

数据源 = 现有两列表 API,DTO 已带全部字段;`useGanttData` 的 `msOpts`/`relOpts` 现只取 id+name,**放宽类型取全 DTO**,同一响应复用(二级范围选择器不受影响)。

| 字段 | milestone | release | 用途 |
|---|---|---|---|
| id/name | milestoneId/name | releaseId/name | 旗标、testid |
| status | planned/in_progress/completed/cancelled | planned/released | 真值表分态 |
| targetDate | String? | String? | 虚线 x 位 |
| releasedDate | — | String?(released 才有) | 实线 x 位 |
| overdue | Boolean | Boolean | 红色 |
| gateDone/gateTotal | Int?(双 null=零 gate 站) | — | 旗标章 |
| version/stage/daysToTarget | — / Int? | version/stage | tooltip |

**上图规则**:
- `targetDate` null → 不画线;milestone `cancelled` → 不渲染。
- release `released` → **只画 releasedDate 实线**(r1 拍板沿用;targetDate 属历史不画,tooltip 可见)。
- 全空(无可上图对象)→ 旗标带不渲染,行位不受影响。
- 数据新鲜度:沿用「二级选项一次拉取」口径,挂载时拉一次;跨标签页新建刷新即得,不另拉。
- **轴纳入 marker 日期**(e2e 设计发现的真缺口,CP2b):轴=item 日期锚定,marker 在窗外会被 xOf 钳边成假位移(YPJ 自身 completed 里程碑 10-18 即在窗外);markerDatesOf(ms,rel) 与组件渲染同规则,page 传 useTimelineScale 并入 min/max(「全览」语义=全部内容入画;宽区间走 GANTT_MIN_PPD 下限+横滚兜底)。

## 三、渲染真值表(状态 × 线型/色/旗标)

| 对象 | 状态 | 竖线 | 颜色 | 旗标 | 点击 |
|---|---|---|---|---|---|
| milestone | planned | 虚线 | 灰 | name(+gate N/M) | 跳里程碑页 |
| milestone | planned+overdue | 虚线 | 红 | name 红 | 跳里程碑页 |
| milestone | in_progress | 虚线 | 蓝(高亮) | name 粗体(+N/M) | 跳里程碑页 |
| milestone | completed | 虚线 | 绿(达成) | name(+N/M) | 跳里程碑页 |
| milestone | cancelled | 不渲染 | — | — | — |
| release | planned | 虚线@targetDate | 蓝 | name(version) | 跳发布页 |
| release | planned+overdue | 虚线@targetDate | 红 | name 红 | 跳发布页 |
| release | released | **实线@releasedDate** | 绿 | name(version) | 跳发布页 |
| 任意 | 日期 null | 不画线 | — | — | — |

- **线**:1px 竖线,`pointerEvents:none`(不拦条 hover、不可点);画在行区**条下层**(DOM 序先于行,条不透明段自然盖线,间隙露线)。
- **旗标**:可点(Tooltip + Link);`border:1px` 用线色,底 `background.paper`(任何底色上可读);位于线右侧 4px。
- **旗标碰撞退让**(确定性,无测量):按 x 升序,标签宽估算 = name 字数×12 + (gate 章 44) + (version 40) + padding;重叠则下一层(带高 20px/层);**上限 3 层,超限仅线(旗标牺牲)**;带高 = 层数×20,层 0/无 marker → 带不渲染。

## 四、页面示意图(ASCII,贴合实况)

```text
┌────────────┬ ┌──────────────────────────────────────────────┐
│ (表头 cell)│ │  10月         ▼11月           12月            │ ← TimelineHeader(现状,不动)
├────────────┼ ─┼───┬──────────────┬───────────────┬──────────┤
│            │ │   │first ┆       │甘特落地┆      │R1┊       │ ← 旗标带(层高20px;旗=可点)
│ YPJ-249 …  │ │   │ ▓▓▓▓▓│▓▓░░░  │  ▓▓▓░░        │          │ ← 行区:竖线贯穿、条盖线
│  ⊳ YPJ-250 │ │   │ ▓▓░░ │       │               │          │
├────────────┼ ─┼───┴──────┴───────┴───────────────┴──────────┤
│ ▶ 已完成(3)│ │   (现状,不动;线延伸到完成组底)              │
└────────────┴ └──────────────────────────────────────────────┘
   TitleColumn     GanttChart(旗标带+竖线都在滚动内容内,随横滚同轴;标题列零改动)
```

- ┆ 虚线(计划时点)· ┊ 实线(已发布)· 旗标=线顶身份牌。
- 今日线(蓝实线,逐行)机制不动,在行区内与 marker 线并存。

## 五、详细设计(代码级修改计划)

纯前端,5 个文件,零后端改动:

| # | 文件 | 改法 |
|---|---|---|
| 1 | `hooks/useGanttData.ts` | `msOpts`/`relOpts` 类型放宽为全 DTO(fetch URL 不变);返回值类型导出 |
| 2 | `components/MilestoneLines.tsx` | **新建**:props `{ms, rel, xOf}`;按 §三渲染旗标带(normal flow,层数退让)+ 竖线组(absolute,top:0→bottom:0,pointerEvents none);全空不渲染带 |
| 3 | `GanttChart.tsx` | props 增 `ms/rel`;`<TimelineHeader>` 后插 `<MilestoneLines/>`,其下行区包进 `position:relative` Box(竖线定位基准);图例文案追加(page.tsx 侧) |
| 4 | `page.tsx` | 底部图例追加:「竖线=里程碑/发布时点(虚=计划,实=已发布;旗标可点跳管理页)」 |
| 5 | `web/e2e/gantt.spec.ts` | 泳道用例改竖线用例(§八);helpers 若缺 milestone/release 建置则补 |

否决备选:
- **泳道(r1)**:marker 身份/交互最好,但当前量级(YPJ 实测 2 ms + 0 rel)空旷、占 56px、改动面 7 文件含 TitleColumn 对齐风险 → 降级为演进方向(§十)。
- 旗标放 header 刻度行内:与刻度争空间,碰撞更疼 → 否。
- 后端聚合 ms/rel 进 gantt API:列表 API 已有全字段 → 否。
- SVG 渲染:与现有 Box 绝对定位风格不一致 → 否。

## 六、CP 计划(一次 coding,停等 e2e)

- **CP1**:① 类型放宽 + ② MilestoneLines 骨架(带+线渲染,无退让)+ ③ 插入与 relative 包裹 → tsc 绿
- **CP2**:碰撞分层 + tooltip/点击 + ④ 图例 → tsc/prettier 绿 + testid 对账
- **CP3**:⑤ e2e 用例 + 全量绿(用户复跑)
- **CP4**:手测档勾选清单交付

纪律:import 删除与最后一个用点迁走同一 CP;`npx tsc --noEmit > /tmp/tsc.log 2>&1 && echo TSC_GREEN` 短路链;每 CP 就地 commit。

## 七、手工测试用例(交付时逐条勾)

前提:dev 实例(3001),项目含已排期 item。

- [ ] M1 planned 里程碑(targetDate 未来)→ 灰虚线+旗标,x 位对齐日期
- [ ] M2 in_progress 里程碑 → 蓝虚线+旗标粗体(高亮)
- [ ] M3 gate 型里程碑(挂 2/5 FINAL)→ 旗标含「2/5」;零 gate 站无章
- [ ] M4 planned 且 targetDate 已过 → 红虚线
- [ ] R1 planned release → targetDate 蓝虚线
- [ ] R2 released release → releasedDate 绿实线+旗标;targetDate 无线;tooltip 含两日期
- [ ] E1 无里程碑/发布的项目 → 旗标带不渲染,行位/表头不受影响
- [ ] E2 两线同日 → 旗标上下层错位;构造 4 个同日 marker → 第 4 面旗不渲染(仅线)
- [ ] I1 点旗标 → 跳对应管理页;hover → tooltip 全信息;线本身不可点不拦 hover
- [ ] I2 条盖线(条段处线不可见,间隙露线);横滚同步;已完成组展开后线延伸到底

## 八、e2e 计划(web/e2e/gantt.spec.ts)

1. fixture 建 milestone(planned+targetDate)→ 断言旗标 testid 存在且 x 位随日期变化
2. 建 release(released)→ 断言实线于 releasedDate、旗标存在
3. 空项目 → 断言旗标带 testid 不存在且首行 y 位不受影响
(testid:`gantt-ms-flag-{id}` / `gantt-rel-flag-{id}` / `gantt-ms-line-{id}` / `gantt-rel-line-{id}` / `gantt-flag-band`,CP2 对账)

## 九、验收标准

1. §七手测 10 条全绿(用户勾选)
2. e2e 全量绿(用户跑,test 容器)
3. tsc/prettier/CI 绿
4. Milestone-Release PRD US-M11 状态回写(As-built 时)
5. SRS v4.1 增量节(时点上图语义并入,As-built 时)

## 十、开放决策清单

- 【已拍板 2026-11-22】形态=竖线+旗标带(方案 A′);released 只画 releasedDate;targetDate null 不上图;旗标 3 层上限。
- 【演进方向,不进本票】marker 规模化(10+)后升级泳道形态( r1 spec 存 git 留史);scope 选 milestone/release 时对应线高亮。
- 【无未决】真值表覆盖四态+两态+空态+null 日期。
