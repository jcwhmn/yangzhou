# spec 0158 — YPJ-221 甘特 S4:Milestone/Release 泳道

> 状态:**待过目**(设计先行,过目后才编码;V32 节奏)
> 源:主票 YPJ-217(S1–S5 切片之 S4);旧甘特 PRD §二R5(2026-11-17 用户 6 点反馈之点 5);Milestone-Release PRD US-M11(甘特泳道 defer 项,本票兑现)。SRS v4 §1.4 非目标中的「Milestone/Release 泳道」由此票转正;**SRS 正文修订延后到 As-built**(v4.1 增量节,不在本 spec 动 SRS)。

## 一、需求

### 1.1 定位
甘特页时间轴顶部新增两条**只读泳道**,把项目级里程碑(Milestone)与发布(Release)的时点标记上图,与 item 计划/实际条同轴对照——一眼看出「条走到哪 vs 里程碑/发布在何时」。

### 1.2 范围
- 泳道两条:**里程碑**(milestone 全体,cancelled 除外)、**发布**(release 全体)。
- Marker:菱形 + 名称标签;milestone 带 gate 就绪度章(N/M);tooltip 全信息;点击跳对应管理页。
- 颜色对齐 SRS 三色体系进行中/完成/超期语义(§四真值表)。
- 图例文字(甘特页底部)补泳道说明。

### 1.3 非目标
- Marker 编辑/拖拽/新建(管理仍走里程碑页/发布页)。
- 泳道折叠、sticky 固定(与 TimelineHeader 现状一致随页滚动)。
- scope 过滤(选了某 milestone)时泳道联动高亮——本期不做,记开放清单候选。
- 资源负载泳道、多项目 Roadmap(仍后续票)。
- SRS v4 正文修订(As-built 时做)。

## 二、数据语义(零后端改动)

数据源 = 现有两列表 API,DTO 已带全部所需字段;`useGanttData` 的 `msOpts`/`relOpts` 现只取 id+name,**放宽类型取全 DTO**,同一响应复用(二级范围选择器仍只用 id/name,不受影响):

| 字段 | milestone | release | 用途 |
|---|---|---|---|
| id/name | milestoneId/name | releaseId/name | 定位、标签 |
| status | planned/in_progress/completed/cancelled | planned/released | 真值表分态 |
| targetDate | String? | String? | 菱形 x 位 |
| releasedDate | — | String?(released 终态才有) | 实心菱形 x 位 |
| overdue | Boolean(planned 且已过) | Boolean(planned 且已过) | 红色 |
| gateDone/gateTotal | Int?(双 null=零 gate 站) | — | 就绪度章 |
| version/stage | — | String? | tooltip |
| daysToTarget | Int? | — | tooltip |

**上图规则**:
- `targetDate` 为 null → 不上图(无 x 位);milestone `cancelled` → 不渲染。
- release `released` → **只画 releasedDate 实心菱形**(拍板:targetDate 属历史,不画,tooltip 内可见)。
- 泳道空态:该类可上图对象为空 → 整条泳道隐藏(标题列 cell 同步隐藏);两类全空 → 泳道区不渲染。
- 数据新鲜度:沿用「二级选项一次拉取」现状(挂载时拉一次),跨标签页新建的里程碑不自动出现——已知限制,刷新即得,不另拉。

## 三、渲染真值表(状态 × 形态)

| 对象 | 状态 | targetDate 菱形 | releasedDate | 名称标签 | gate 章 | 点击 |
|---|---|---|---|---|---|---|
| milestone | planned | ◇ 灰描边 | — | name,常规 | 双 null 不显,否则 `N/M` | 跳里程碑页 |
| milestone | planned+overdue | ◇ 红描边 | — | name,红色 | 同上 | 跳里程碑页 |
| milestone | in_progress | ◆ 蓝实心(高亮) | — | name,**粗体** | 同上 | 跳里程碑页 |
| milestone | completed | ◆ 绿实心 | — | name,常规 | 同上 | 跳里程碑页 |
| milestone | cancelled | 不渲染 | — | — | — | — |
| release | planned | ◇ 蓝描边 | — | name(version) | — | 跳发布页 |
| release | planned+overdue | ◇ 红描边 | — | name,红色 | — | 跳发布页 |
| release | released | 不画 | ◆ 绿实心 | name(version) | — | 跳发布页 |
| 任意 | targetDate/releasedDate null | 不上图 | — | — | — | — |

动作语义(每个 marker 一致):hover → Tooltip(名称/状态/targetDate/releasedDate/gate 就绪度/version·stage/剩余·超期天数);click → `Link` 跳 `/p/{key}/milestones` 或 `/p/{key}/releases`(路由已存在)。

**标签碰撞退让**(确定性规则,无测量):marker 按 x 升序渲染;标签画在菱形右侧;若与前一个 marker 的标签区间重叠(估算宽 = name 字数×12px + gate 章 44px),本 marker 不渲染标签——菱形仍在,信息走 tooltip。

## 四、页面示意图(ASCII,贴合实况)

双面板现状(YPJ-236):标题列 | 分割条 | 甘特区;泳道插在 TimelineHeader 与 item 行之间,**两侧同序同高对齐**:

```text
┌────────────┬ ┌──────────────────────────────────────────────┐
│  (表头 cell)│ │  10月        ▼11月          12月              │ ← TimelineHeader(现状,不动)
├────────────┼ ─┼──────────────────────────────────────────────┤
│ ◆ 里程碑   │ │        ◇SLA评审 3/5     ◆v1.0上线(蓝,粗体) │ ← 新:里程碑泳道 28px
├────────────┼ ─┼──────────────────────────────────────────────┤
│ ◆ 发布     │ │   ◇R2 v0.9(红=超期)      ◆R1 v1.0(绿实心) │ ← 新:发布泳道 28px
├────────────┼ ─┼──────────────────────────────────────────────┤
│ YPJ-249 …  │ │   ▓▓▓▓▓░░░░░░   (计划/实际条,现状,不动)     │
│  ⊳ YPJ-250 │ │   ▓▓▓░░░░                                    │
├────────────┼ ─┼──────────────────────────────────────────────┤
│ ▶ 已完成(3)│ │   (现状,不动)                                │
└────────────┴ └──────────────────────────────────────────────┘
   TitleColumn    GanttChart(泳道行随横滚同轴移动;纵排与标题列 cell 一一对应)
```

- 泳道行:高 28px,`border-bottom: 1px divider`,底色透明(与行区一致)。
- 标题列对应 cell:图标 `◆` + 文案「里程碑 / 发布」(非交互,Typography caption)。
- 今日线/依赖锚蓝点等现状元素不进泳道(今日线逐行绘制机制不动)。

## 五、详细设计(代码级修改计划)

纯前端,7 个文件,零后端改动:

| # | 文件 | 改法 |
|---|---|---|
| 1 | `hooks/useGanttData.ts` | `msOpts`/`relOpts` state 类型放宽为全 DTO(`{milestoneId,name,status,targetDate,overdue,gateDone,gateTotal,...}` / release 同理);fetch URL 不变;返回值类型导出 |
| 2 | `components/SwimLanes.tsx` | **新建**:props `{ms, rel, xOf, projectKey}`;内部按 §三真值表渲染 0–2 条泳道行(菱形=Box rotate 45°,标签/gate 章/Tooltip/Link);空态不渲染行 |
| 3 | `GanttChart.tsx` | props 增 `ms/rel`;`<TimelineHeader>` 之后插 `<SwimLanes/>`;import 相应组件 |
| 4 | `components/TitleColumn.tsx` | props 增 `showMsLane/showRelLane`;header cells 与 mainRows 标题 cell 之间插对应固定高 cell(28px,同 border) |
| 5 | `page.tsx` | 从 useGanttData 取 ms/rel,算 `msLane = ms.filter(s!=='cancelled' && targetDate)`、`relLane = rel.filter(targetDate || releasedDate)` 传两侧;底部图例文案追加泳道一句 |
| 6 | `web/e2e/gantt.spec.ts` | 新增泳道用例(见 §七);helpers 若缺 milestone/release 建置则补 |
| 7 | `docs/testing/manual-test-cases.md` | YPJ-221 节落档(本 commit 同步立节) |

否决备选:
- 后端把 milestone/release 聚合进 gantt API → 否:两列表 API 已有全字段,聚合徒增契约面与耦合。
- 泳道行高与 item 行(44px)一致 → 否:marker 是时点不是条,28px 紧凑够读,且减少纵向空间挤占。
- SVG 渲染 → 否:与现有 PlanBar/ActualBar 同为 MUI Box 绝对定位风格,一致性优先。

## 六、CP 计划(一次 coding,停等 e2e)

- **CP1**:① useGanttData 类型放宽 + page.tsx 泳道集合计算(纯数据层,页面无视觉变化)
- **CP2**:② SwimLanes 组件 + ③ GanttChart 插入(右侧泳道可见)
- **CP3**:④ TitleColumn 泳道 cell(左侧对齐;与 CP2 同 CP 验证行高)
- **CP4**:图例文案 + tsc/prettier 全绿 + testid 对账
- **CP5**:⑥ e2e 用例 + 全量跑绿(用户复跑确认)
- **CP6**:⑦ 手测档勾选清单交付

纪律:import 删除与最后一个用点迁走同一 CP;`npx tsc --noEmit > /tmp/tsc.log 2>&1 && echo TSC_GREEN` 短路链验证;每 CP 就地 commit。

## 七、手工测试用例(交付时逐条勾)

前提:dev 实例(3001),项目含已排期 item。

- [ ] M1 纯 planned 里程碑(targetDate 未来)→ 里程碑泳道灰描边菱形+名称,x 位对齐日期
- [ ] M2 in_progress 里程碑 → 蓝实心高亮+粗体标签
- [ ] M3 gate 型里程碑(挂 2/5 FINAL)→ 章「2/5」;零 gate 站里程碑无章
- [ ] M4 planned 且 targetDate 已过 → 红描边(overdue)
- [ ] R1 planned release(targetDate 未来)→ 发布泳道蓝描边菱形
- [ ] R2 released release → releasedDate 处绿实心菱形;tooltip 含 targetDate+releasedDate
- [ ] E1 无里程碑/发布的新项目 → 两条泳道都不渲染(标题列无 cell,行序不错位)
- [ ] E2 密集:两 marker 同日 → 后者标签隐藏、菱形仍在,tooltip 可读
- [ ] I1 点击 marker → 跳对应管理页;hover → tooltip 全信息
- [ ] I2 泳道与 item 行横滚同步;标题列纵向对齐不错位

## 八、e2e 计划(web/e2e/gantt.spec.ts)

1. fixture 建 milestone(planned+targetDate)→ 断言泳道菱形 testid 存在且 x 位随日期变化
2. 建 release(released)→ 断言实心 marker 于 releasedDate
3. 空项目 → 断言泳道 testid 不存在且首行 y 位不受影响
(具体 testid:`gantt-lane-milestone` / `gantt-lane-release` / `gantt-ms-{name}` / `gantt-rel-{name}`,CP4 对账)

## 九、验收标准

1. §七手测 10 条全绿(用户勾选)
2. e2e 全量绿(用户跑,test 容器)
3. tsc/prettier/CI 绿
4. Milestone-Release PRD US-M11 状态回写(As-built 时)
5. SRS v4.1 增量节(泳道语义并入,As-built 时)

## 十、开放决策清单

- 【已拍板】released 只画 releasedDate 实心,targetDate 不画(§二)。
- 【候选,不阻塞】scope 选 milestone/release 时泳道对应 marker 高亮——记后续改进意见,不进本票。
- 【无未决】渲染真值表全覆盖四态+两态+空态+null 日期。
