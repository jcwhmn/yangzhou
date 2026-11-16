# spec 0136 — YPJ-203:US-R7 逾期徽标 + US-R8 范围完成率(口径 1b)

> PRD(Milestone-Release)§6 US-R7/R8、§8 口径 1b;前置 spec 0132/0133/0135。

## 一、需求

### 范围

1. **US-R7 逾期徽标**:`ReleaseDto` 加 `overdue: Boolean`(= status==planned 且 targetDate < 今天,Clock;**计算不落库**);Releases 页与侧栏红标(milestone 同型视觉)。
2. **US-R8 范围完成率**:`ReleaseDto` 加 `doneCount` / `totalCount`;口径 1b:**done = 挂接 item 当前 status.statusType==FINAL;有效 = 挂接行中未软删(deletedAt==null)的 item**;与 milestone gateRows 同源实现。Releases 页 planned 行展示「done/total(N%)」并标注「仅供参考,不等于发布资格」。
3. released 行同样返回完成率终值(历史挂接不删),UI 在置灰区展示。

### 非目标

- 工作量完成率(FR-REL-017 第二式,无估算口径另票);发布资格门禁(FR-REL-018 明确分离);item 侧展示;按期率报表(US-R10)。

### ⚠ 语义断言(请人复核)

「排除已取消 item」:项目 workflow 状态只有 START/FINAL/ACTIVE 三型,**无法从状态型区分「已完成 FINAL」与「已取消 FINAL」**。取与现有实现一致的读法:cancelled 若被项目建成 FINAL 型会计入 done——与本断言冲突时需引入 CANCELLED 型或按状态名单排除,另票。当前默认 workflow 的 cancelled 为 ACTIVE 型(待用户核),自然被排除出 done。

## 二、详细设计

### 真值表(徽标/完成率 × 状态)

| release 状态 | overdue | 完成率展示 |
|---|---|---|
| planned,targetDate 未来/无 | false | ✅「done/total(N%)·仅供参考」 |
| planned,targetDate 已过 | **true(红标)** | ✅ 同上 |
| released | false(终态不逾期) | ✅ 终值(置灰区) |

| 挂接情况 | doneCount / totalCount |
|---|---|
| 零挂接 | 0 / 0 → UI 显「无挂接 item」 |
| item 软删(回收站) | 不入分母 |
| item 状态 FINAL | 入分子 |
| item 非 FINAL | 只入分母 |

### 代码修改计划

| 文件 | 改动 |
|---|---|
| `ReleaseService.kt` | `ReleaseDto` 加 overdue/doneCount/totalCount;`toDto` 重载带计算参数;list() 组装:批量取挂接行→滤软删→statuses 查 FINAL(仿 milestone gateRows,避免 N+1);released 行同算 |
| `ReleasePage.tsx` | planned 行:overdue 红标(targetDate 旁);完成率行「done/total(N%)·仅供参考,不等于发布资格」;零挂接显「无挂接 item」 |
| `layout.tsx`(侧栏) | Releases 段 planned 行 overdue 加红标(milestone ⚠ 同型) |
| `texts.ts` | releases 组加 overdue/doneRate/参考标注/无挂接文案 |

### 否决的备选

- **单独 summary 端点**(`GET /releases/summary`):列表 DTO 直带三个标量字段,前端零二次请求;summary 端点是投机。
- **完成率落库/缓存**:口径 1b 明确计算不落库;数据量小(挂接数少)无需缓存。
- **工作量完成率**:估算字段在 item 上口径未定(PRD 要求显示口径,砍掉)。

## 三、手工测试用例

1. 徽标:建 planned release,targetDate=昨天 → 列表红标 + 侧栏红标;改 targetDate=明天 → 徽标消失(编辑后即时刷新)。
2. released 行无徽标。
3. 完成率:release 挂 3 个 item,1 个拖到 FINAL 状态 → 行显「1/3(33%)·仅供参考」;全 FINAL → 「3/3(100%)」。
4. 零挂接 → 「无挂接 item」。
5. 软删一个挂接 item(回收站)→ 分母变 2(刷新后)。
6. 文案核验:「仅供参考,不等于发布资格」随行显示。

## As-built

(完工后补)
