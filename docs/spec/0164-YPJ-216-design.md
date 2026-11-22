# spec 0164 — YPJ-216 组拉入排期守卫 · 设计(概要+详细)

> 配套需求见 `docs/spec/0164-YPJ-216-srs.md`(做什么);本文件讲怎么做:输入→输出的实现结构、模块落点、分支映射与测试映射。需求变更先改 SRS,本文件随之。

## 一、总体结构

守卫 = 单一后端入口 + 四入口收敛两条端点 + 前端补录/错误呈现 + 甘特缺省渲染。

```
途径① detail 选组 ─┐
途径④ 组页拉入   ──┴→ PUT /items/{id}/group-membership ─→ ItemGroupService.replaceMembership ─┐
途径② backlog 行拉入 ─┐                                                                      ├→ ItemService.applySprintPullSchedule(唯一入口)
途径③ 看板多选拉入 ────┴→ PUT /items/{id}/board-membership ─→ ItemGroupService.boardMembership ─┘
途径0 创建直挂(create 带 groupId)→ ItemService.create ─────────────────────────────────────────┘
甘特缺省画图:GanttChart 渲染层(spec 0151 分型前插缺省层,数据不写回)
```

## 二、守卫入口(详细设计)

`ItemService.applySprintPullSchedule(item, explicitStart?, explicitDue?): Item`

处理序(映射 SRS §4.1–4.3):

1. **预估校验**(SRS 4.1):`estimateValue == null || estimateUnit == null` 时读 `project.estimateRequired`——
   - true → `ConflictException`(「拉入组须先填预估时长(estimateValue + estimateUnit)」);
   - false → **直接 return item**(放行,不铺算,存量日期不动)。
2. **estHours**:`estimateHours(estimateValue, estimateUnit, ws, we)`,null(单位未知)→ return item。
3. **start 决策**(SRS 4.2):explicitStart ?: 存量 startDate ?: 前置推导(最晚前置 due+1min → `nextWorkMoment`;全未排 → ConflictException):`todayStart(ws, we, workMode)`。
4. **due 决策**(SRS 4.3):explicitDue ?: 存量 dueDate ?: `WorkdayCalculator.dueFrom(start, estHours)`。
5. start > due → `BadRequestException`;有变化 → save + `logActivity("dates_changed")`。

关键 helper(均在 ItemService,internal):`todayStart`(now 钳制)、`nextWorkMoment`(周末跳周一窗起/窗后跳次工作日;全天窗不裁时刻)、`workdayBounds`("24:00"=全天窗终点)。

## 三、四入口落点

| SRS 途径 | 端点 | service 方法 | 守卫调用点 |
|---|---|---|---|
| ① | PUT `/api/items/{id}/group-membership` | `replaceMembership` | 新增成员行后(timed 组);**幂等早退分支同样调用**(SRS 4.5) |
| ② | PUT `/api/items/{id}/board-membership {onBoard:true}` | `boardMembership` | 自动挂组成员行落库后(timed) |
| ③ | 同②逐条 | 同② | 同② |
| ④ | 同①逐条 | 同① | 同① |
| 0(创建直挂) | POST `/api/items` | `ItemService.create` | groupId 直挂且 timed level-1 |
| 移出 | 同①/②(空 groupId / onBoard:false) | — | 不调用 |

触发条件统一 `groupType.timed`(创建路径另要求 level-1)。自动挂组(②③):最早 active → 最早 planned 并 `startGroup`。409 复用 `ConflictException`。

## 四、前端补录与错误呈现(SRS §三)

| 途径 | 组件 | 方案 |
|---|---|---|
| ① | 详情页 | catch → setError(既有);用户在详情预估框补录后重选 |
| ② | backlog 行按钮 | 409 → 打开**补录对话框**(预估数值+单位)→ 提交 = PATCH item 预估 → 自动重试拉入;取消 = 不动 |
| ③ | PullDialog | 列表每行预估输入(数值+单位)+「应用到全部」复选;确认前对无预估行先 PATCH 预估再拉;错误显示在对话框内,不关框 |
| ④ | AddMembersDialog | 同③(行内预估 + 应用到全部);确认回调携带每票预估,页面先 PATCH 预估再逐条入组 |

「应用到全部」语义:点击时把输入值写入所有未手动改过的行;已手动改过的行不覆盖(行级 dirty 标记)。

## 五、项目缺省设置(甘特缺省画图输入,SRS 4.4)

`project` 表新增三列(可空,null=未设置,渲染回退内置缺省):

| 列 | 类型 | 语义 |
|---|---|---|
| `default_estimate_value` | VARCHAR null | 缺省预估数值(>0 数字文本) |
| `default_estimate_unit` | VARCHAR null | hour / day / week |
| `default_start_offset_minutes` | BIGINT null | 组开始后偏移分钟(≥0) |

- PATCH `/api/projects/{key}/settings` 扩三字段(null=不改;校验:value>0、unit 枚举、offset≥0);GET 项目响应携带。
- **回退链**:value 未设置 → 预估刻度「m」档(刻度也未设 → 内置默认五档的 m=5day);offset 未设置 → 0(组开始);组开始也无 → 既有 case1 锚点链。
- 迁移 `V69__project_pull_defaults.sql`;`entities.puml` 同步。

## 六、甘特缺省渲染

落点 `GanttChart.renderGanttRow` 分型输入,不动数据层、不写回:

- `estH` 为 null → 取缺省预估(§五回退链)换算小时;
- `startAnchor` 为 null → 取 `groupStartOf(r)` + offset 分钟;
- 效果:无预估/无开始票落 case4(锚+长度)画缺省条;两者皆缺且不在任何组 → 维持既有 case1;
- **与 spec 0151 分型交互**:缺省层插入后 case1/2 仅「不在任何组」的票可及,0151 存量断言与 e2e「三无」fixture 预期按新语义复核。

## 七、测试映射

| SRS 验收 | 测试 |
|---|---|
| 5.1 分支 | `ItemSprintPullScheduleApiTest`:必填 409 / 非必填放行 / 铺算 / 显式保留 / 前置 / 幂等重拉 / 跨周末 |
| 4.4 缺省 | settings PATCH 校验(集成);甘特缺省条(e2e gantt) |
| 5.2 四途径 | e2e:board-membership / sprint页(组页) / board-item-creation / item-detail;fixture 原则=真拉组且依赖铺算的造票带预估 |
| api.http | settings 新字段段 + 拉入段(必填 409 / 非必填放行 / 回填 / 覆盖) |

## 八、实现差距清单(相对 SRS 现状)

| # | 差距 | 落点 |
|---|---|---|
| 1 | 守卫无条件 409 → estimateRequired 条件化 | §二.1 |
| 2 | 项目缺省三设置(迁移+settings+DTO) | §五 |
| 3 | 甘特缺省画图 | §六 |
| 4 | 途径②补录对话框 | §四 |
| 5 | 途径③④对话框行内预估+应用到全部 | §四 |
