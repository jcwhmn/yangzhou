# ItemGroup 生命周期侧栏 SRS

> 票:YPJ-269(2026-11-26 grill 定稿,共识全文亦录 YPJ-269 票面)。本文件为需求真相源;实现计划见 `docs/spec/0174-YPJ-269-group-lifecycle-sidebar-spec.md`。ItemGroup PRD 不动(需求类文档一律 SRS 格式,不与旧格式并存)。

## 1 目的与范围

sprint 等 ItemGroup 需要一处只读视图,回答「这个组什么时候建的、什么时候开始/完成、活了多久、组内 item 首尾何时」。

- **范围**:group 详情页右侧生命周期侧栏(数据层对全部组类型通用,现行实现挂 sprint/[id] 页);组状态迁移史落库;started/completed 等提取值读时派生进 group 详情 API。
- **非目标**:release 侧栏页面(releases 现无详情页;数据层已通用,详情页落地时侧栏同构即得);迁移轨迹时间线 UI(史先落库,展示另票 YPJ-284);侧栏编辑/即时保存(YPJ-282 范畴);计划起止展示(SprintHeader 已有,不双真相源);milestone(独立实体,不在 item_group 域);ItemSidebar(item 详情页)任何改动。

## 2 术语

沿用词汇表(CONTEXT.md):ItemGroup / sprint / release。本文新增:

| 术语 | 语义 |
|---|---|
| 组状态史 | `group_status_history` 一行 = 一次组状态迁移 from→to 及时刻 |
| START / FINAL | 组工作流状态类型(`group_status.status_type`):sprint START=planned、FINAL=completed;release START=planned、FINAL=released |
| 直挂成员 | `item_group_member` 显式行,不含 item 子树(与 sprint 详情页成员表同口径) |

## 3 数据语义

### 3.1 组状态史(落库)

- 每次组状态变更一行:`from`(null = 初始进入)/ `to` / `source` / 时刻。`source ∈ created`(建组初始态)`| action`(动作端点、看板拉入自动激活 spec 0117——用户可见的真实迁移)`| cascade`(父组联动重算、改父回退;仅实际变化时写——0111 重算已有不变即跳过语义)。
- **存量组不回填**(NULL,UI 显示"—");对齐 item_status_history V31 先例(算不出即不造,不写伪数据)。
- **提取值(started/completed)零物化**:不落列、不建扩展属性机制,读时从史派生——镜像 item 先例(item 表从未有 started/completed 列,LifecycleTimes 读时算)。

### 3.2 派生口径(单点公式)

| 派生值 | 公式 | 对齐 |
|---|---|---|
| startedAt(实际开始) | 首次迁出 START:史中首行 `to ∉ START` 的时刻 | item 侧 `LifecycleTimes.startedAt` 同公式(YPJ-132/0150 §3.4) |
| completedAt(实际完成) | 最近一次迁入 FINAL:史中末行 `to ∈ FINAL` 的时刻;reopen 后未再完成则保留上次完成时刻 | item 侧 `LifecycleTimes.completedAt` 同公式(YPJ-245 校准) |
| 执行时长 | completedAt − startedAt,仅两者皆有时显示;从开始算,不含排期未动时间 | grill Q6 裁决 |
| 首 item 开始 | 直挂成员各自 startedAt 的 min(全空则空) | LifecycleTimes × 直挂成员,零建模 |
| 末 item 完成 | 直挂成员各自 completedAt 的 max(全空则空) | 同上 |

时刻 UTC ISO 传输,前端 zh-CN 本地化渲染,空值显示"—"。存量组的首末 item 时间**能考尽考**:item_status_history 建表时已从 item_activity 回填(解析不出即丢弃),派生查询天然覆盖可考部分。

## 4 分支真值表

### 4.1 组状态 × 迁移记录(sprint)

| 前状态 | 动作 | 后状态 | 史行 source |
|---|---|---|---|
| —(建组) | create | planned | created(from=null) |
| planned | start | active | action |
| active | complete | completed | action |
| active | backward | planned | action |
| completed | reopen | active | action |
| 任意(父组联动) | 子组动作/删子组/改父传导 | 按 spec 0111 重算 | cascade(仅实际变化时) |

release(planned→released 单向,FINAL 无出边)走同一机制同表;本票 UI 挂现行 group 详情页(sprint/[id]),release 无详情页故不触达。

### 4.2 侧栏字段 × 数据可得性

| 字段 | 数据源 | 无数据时 |
|---|---|---|
| 创建 | item_group.created_at(原生,恒有) | — |
| 实际开始 | 派生 startedAt | "—" |
| 实际完成 | 派生 completedAt | "—" |
| 执行时长 | completed−started | 不显示该行值 |
| 首 item 开始 | 派生 min | "—" |
| 末 item 完成 | 派生 max | "—" |

## 5 验收标准

1. 新建组(sprint 类型):侧栏只有创建时刻,其余“—”。
2. start → 实际开始出现且等于动作时刻;complete → 实际完成 + 执行时长出现。
3. reopen→complete:实际完成 = 第二次完成时刻(最近口径);backward→start:实际开始 = 首次迁出时刻(不覆写)。
4. 存量组(本票上线前建的):开始/完成“—”;首末 item 时间按成员 item 史能考尽考。
5. 空成员组:首末 item"—"。
6. release 回归:release 列表与既有行为不受影响。

## 6 开放决策清单

无——2026-11-26 grill 全部裁决(形态=独立史表、零物化、口径镜像 item、UI 仅 sprint、文档单独 SRS),全文录 YPJ-269 票面 description。遗留动作:历史展示段剥离至 YPJ-284。
