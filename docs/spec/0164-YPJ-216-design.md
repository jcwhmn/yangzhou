# spec 0164 — YPJ-216 sprint 拉入排期守卫 · 设计(概要+详细)

> 配套需求见 `docs/spec/0164-YPJ-216-srs.md`(做什么);本文件讲怎么做:输入→输出的实现结构、模块落点、分支映射与测试映射。需求变更先改 SRS,本文件随之。

## 一、总体结构

守卫 = 单一后端入口 + 四入口收敛两条端点 + 前端错误呈现 + 甘特缺省渲染。

```
途径① detail 下拉 ─┐
途径④ sprint 拉入 ─┴→ PUT /items/{id}/group-membership ─→ ItemGroupService.replaceMembership ─┐
途径② backlog 行拉入 ─┐                                                                        ├→ ItemService.applySprintPullSchedule(唯一入口)
途径③ 看板多选拉入 ────┴→ PUT /items/{id}/board-membership ─→ ItemGroupService.boardMembership ─┘
途径0 创建直挂(create 带 sprintId)→ ItemService.create ────────────────────────────────────────┘
甘特缺省画图:GanttChart 渲染层(spec 0151 分型前插缺省层)
```

## 二、守卫入口(详细设计)

`ItemService.applySprintPullSchedule(item, explicitStart?, explicitDue?): Item`

处理序(映射 SRS §4.1–4.3):

1. **预估校验**(SRS 4.1):`estimateValue == null || estimateUnit == null` 时——
   - 读项目设置 `project.estimateRequired`;=true → `ConflictException`(消息「拉入 sprint 须先填预估时长(estimateValue + estimateUnit)」);
   - =false → 直接 return item(不铺算,存量日期不动)。**现状代码为无条件 409,此为待改差距**。
2. **estHours**:`estimateHours(estimateValue, estimateUnit, ws, we)`,null(单位未知)→ return item。
3. **start 决策**(SRS 4.2):explicitStart ?: 存量 startDate ?: 前置推导(最晚前置 due+1min → `nextWorkMoment`;全未排 → ConflictException):`todayStart(ws, we, workMode)`。
4. **due 决策**(SRS 4.3):explicitDue ?: 存量 dueDate ?: `WorkdayCalculator.dueFrom(start, estHours)`。
5. start > due → `BadRequestException`;有变化 → save + `logActivity("dates_changed")`。

**关键 helper**(均在 ItemService,internal):
- `todayStart`:now 钳制——`nextWorkMoment(now, ws, we, workMode)`;
- `nextWorkMoment`:workMode 才生效;周末跳周一窗起/窗后跳次工作日窗起;全天窗(ws=we=00:00)不裁时刻;
- `workdayBounds`:解析项目工作窗,"24:00"=全天窗终点(兑退缺省 00:00/00:00)。

## 三、四入口落点

| SRS 途径 | 端点 | service 方法 | 守卫调用点 |
|---|---|---|---|
| ① | PUT `/api/items/{id}/group-membership` | `replaceMembership` | 新增成员行后(timed 组);**幂等早退分支同样调用**(SRS 4.5) |
| ② | PUT `/api/items/{id}/board-membership {onBoard:true}` | `boardMembership` | 自动挂 sprint 的成员行落库后(timed) |
| ③ | 同②逐条 | 同② | 同② |
| ④ | 同①逐条 | 同① | 同① |
| 0(创建直挂) | POST `/api/items` | `ItemService.create` | sprintId 直挂且 timed level-1 |
| 移出 | 同①/②(空 groupId/onBoard=false) | — | 不调用 |

触发条件统一 `groupType.timed`(创建路径另要求 level-1)——不用 typeKey=="sprint" 字面量,自定义 timed 组同纪律。自动挂组:最早 active → 最早 planned 并 `startGroup`(零「无 sprint」409)。409 复用 `ConflictException`(无 code 通道,消息区分两种拒绝;统一错误形状不变)。

## 四、前端错误呈现(SRS §三共同约束)

| 途径 | 组件 | 现状 |
|---|---|---|
| ① | 详情页 saveBasics catch → setError | 既有 |
| ④ | AddMembersDialog → sprint 页 setError | 既有 |
| ② | backlog 行按钮 catch → setError | **新增**(原 `.catch(() => {})` 静默吞) |
| ③ | PullDialog.confirmPull try/catch → 对话框内红字,不关框 | **新增**(原无 catch) |

批量途径语义:顺序逐条 await,首错抛出中止,已成功票保留(与 SRS §三③④一致)。

## 五、甘特缺省画图(SRS 4.4)

渲染层缺省,落点 `GanttChart.renderGanttRow` 的分型输入(不动数据层):

- `estH` 为 null 时以缺省预估替代:`estimateHoursOf(中档值, 中档单位, wsm, wem)`;中档取项目 `estimateScales` 中 `key=="m"` 档(无 m 档 → 刻度中位数;无刻度 → 3h?**实现时定,倾向 1d 兜底**);
- `startAnchor` 为 null 且票在本 sprint → 取组开始(`groupStartOf` 既有链);不在任何组 → 维持既有 case1 行为;
- 缺省仅影响渲染输入,不写回 item(无 PATCH)。
- **与 spec 0151 分型的交互**:缺省层插入后,「无预估」票不再落 case2(锚+无长度)形态,分型矩阵的 case1/2 仅剩「不在任何组」的票可及——0151 的存量 case 断言需复核,e2e fixture(甘特「三无」票)预期要按新语义改。

## 六、数据与迁移

无新表、无迁移。`estimateRequired` 为项目既有设置字段,只读。

## 七、测试映射

| SRS 验收 | 测试 |
|---|---|
| 5.1 全部分支 | `ItemSprintPullScheduleApiTest`(集成,REST 黑盒);含幂等重拉两条 |
| 4.5 409 消息 | 集成断言消息文本;前端呈现走 e2e |
| 5.2 四途径 | e2e:board-membership / sprint / board-item-creation / item-detail 相关 spec;守卫上线后 fixture 原则=**真拉 sprint 的造票带预估**(helpers.apiCreateItem 按需 `{estimate:true}`) |
| 甘特缺省画图 | e2e gantt.spec:无预估票断言「缺省条」(改动后原 case2/3 断言复核) |
| api.http | assign 段:必填 409 / 非必填放行 / 正常回填 / due 覆盖 |

## 八、实现差距清单(相对 SRS 现状)

| # | 差距 | 落点 |
|---|---|---|
| 1 | 守卫无条件 409 → estimateRequired 条件化 | §二.1 |
| 2 | 甘特缺省画图(中档/sprint 开始) | §五 |
| 3 | e2e fixture 随甘特新语义复核(「三无」票将画缺省条) | §七 |
| 4 | 非必填放行后「无预估成员重拉」早退分支:409 改为放行 | §三 |
