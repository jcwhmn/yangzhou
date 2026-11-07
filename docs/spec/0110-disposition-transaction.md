# 0110 — 处置事务化:完成与成员处置同一事务(YPJ-159,方案C;0106/0109 修订)

> 背景(2026-11-07 用户实测 dev T104Z):前端完成流 = `POST /actions/complete`(状态先落库)→ 弹窗 → 逐条 `PUT /items/{id}/group-membership`。两段之间任何中断/失败 = 「组已 completed、成员未动」的矛盾态。用户拍板:**完成与处置必须一个事务**。

## 一、需求

### 1.1 行为

- 完成 level-1 组时,**状态迁移 + 全部非终态成员处置在同一后端事务内落库**;要么全成,要么全不动。
- 处置计划由前端弹窗产出(0109 交互不变:进「组名」/进 Backlog 逐行标记),但改为**随完成请求一次性提交**,前端不再有第二轮 PUT。
- 无非终态成员的完成(空组/全 Done)不弹窗,行为同今(无 body 直完成)。
- API 兼容:`POST .../actions/complete` **无 body 行为不变**(仅状态迁移)——CLI、既有 e2e helpers、0097 白名单语义不受影响。

### 1.2 范围

- 只动 level-1 组(sprint)complete 出边;reopen/start 不涉处置。
- 非原子残留的存量数据:dev 库 scratch 可弃,不做修复;prod 由 V52 已清且此后无此矛盾态入口。

### 1.3 非目标

- 处置后端自动决策树(0105)不变,仍由前端显式提交计划;
- 不改 member/history 建模(0109 语义:member 表=当前归属,FINAL 行=历史);
- 不做处置部分失败的部分落库(失败即整体回滚,错误经统一异常形状返回)。


## 一点五、界面规格(2026-11-07 用户拍板:一次弹出;本节为界面真相源)

### 交互序列(组详情页,Sprint active 态)

  点「✓ 完成 Sprint」按钮
    ├─ 组内非终态 item 数 = 0 → 弹【完成确认框】(一次弹,完成即关)
    └─ 非终态 item 数 > 0 → 弹【处置弹窗】(一次弹,无中间确认步)

### 【完成确认框】(全 Done 组)

  ┌──────────────────────────────────────┐
  │ 完成 Sprint                    (xs)  │
  ├──────────────────────────────────────┤
  │ 完成后,0 个未完成 item 将进入处置     │
  │ (选定去向);已完成 item 保留在本页    │
  │ 历史。                                │
  ├──────────────────────────────────────┤
  │                 [取消]  [确认完成]    │
  └──────────────────────────────────────┘
  · 确认完成 → POST actions/complete(无 body)→ 关弹窗,页头出「此 Sprint 已完成」横幅
  · 取消 → 关弹窗,什么都不发生

### 【处置弹窗】(有非终态 item;maxWidth=sm)

  ┌────────────────────────────────────────────────────┐
  │ 完成「Sprint 42」——2 个未完成 item 待处置          │
  ├────────────────────────────────────────────────────┤
  │ 转入进行中的「Sprint 3」  ← 目标摘要行(0107)      │
  │ [Sprint 3] [Sprint 4]   ← 仅多候选时显示(选目标)  │
  │ 或 [组名输入框]         ← 仅无任何组时显示         │
  ├────────────────────────────────────────────────────┤
  │ ▢ 待办成员A   [回 Backlog]   ← 每行一个非终态 item │
  │ ▢ 进行中成员B [回 Backlog]     点击=覆写该行去向    │
  │   (覆写后该行橙色,按钮变[撤销覆写])              │
  ├────────────────────────────────────────────────────┤
  │ 摘要:2 进「Sprint 3」 0 回 Backlog                 │
  ├────────────────────────────────────────────────────┤
  │            [取消] [进 Backlog] [进「Sprint 3」]     │
  │              次钮      主钮(内插目标组名)          │
  └────────────────────────────────────────────────────┘
  · 进「组名」→ 单次 POST actions/complete body={dispositions}
    (逐行计划:未覆写行→目标组,覆写行→回池);成功关弹窗+横幅
  · 进 Backlog → 同一请求,全部 targetGroupId=null
  · 取消 → 关弹窗,组仍 active(事务未发生)
  · 失败(4xx/5xx)→ 弹窗保留、错误条显示、组未完成,可改计划重试

### 状态×动作→行为真值表

  | 组状态 | 非终态成员 | 动作             | 行为                                |
  |--------|-----------|------------------|-------------------------------------|
  | active | 0         | ✓完成 Sprint     | 开完成确认框                        |
  | active | >0        | ✓完成 Sprint     | 直接开处置弹窗(跳过确认框)        |
  | active | 任意      | 处置弹窗·取消    | 关弹窗,组仍 active                 |
  | active | 任意      | 进「组名」/进 Backlog | 单请求完成+处置;失败组仍 active |
  | active | 0         | 确认框·确认完成  | 完成(API 无 body,兼容 CLI)      |
  | completed| 任意     | ✓重开            | 0108 流,本票不改                   |

### 技术注记(实现层,UI 行为以上表为准)

  · items 未加载完 → 完成类出边按钮 disabled(防基于空 items 误判分支)
  · sprint 状态本地更新带版本号,防初始加载慢响应乱序覆盖

## 二、详细设计

### 2.1 后端(itemgroup 垂直切片内,三文件)

1. **ItemGroupService.action(...)** 加可选参数 `dispositions: List<DispositionLine>? = null`(或重载);仅 `function=="complete"` 且 `dispositions != null` 时,在**同一 @Transactional 内**、状态迁移成功后,逐行调既有 `replaceMembership` 内部落库路径(itemId→targetGroupId|null;null=回池)。0109 的 stale 清理逻辑原样复用 → 本组(completed 后)旧行随替换清掉。
2. **请求形状**(小 DTO 同文件):
   ```kotlin
   data class GroupActionRequest(val dispositions: List<DispositionLine> = emptyList())
   data class DispositionLine(val itemId: String, val targetGroupId: String?) // null=进 Backlog
   ```
3. **校验**(fail-fast,事务内):itemId 不属于本组非终态成员 → 400(fields=[itemId]);targetGroupId 非本人 active/planned 组 → 404/400;重复 itemId → 400。Controller `action(...)` 加 `@RequestBody(required=false)`。
4. **原子性保障**:Spring `@Transactional` 已有——replaceMembership 抛异常即整体回滚(状态迁移也回滚);无需新锁(既有唯一约束兜底)。

### 2.2 前端(sprint/[id]/page.tsx + texts 无新文案)

- `doComplete`(确认完成)拆两路:
  - 无非终态成员 → 照旧无 body complete;
  - 有 → 打开处置弹窗(行/目标计算不变);**进「组名」/进 Backlog 按钮 → 单次 `POST actions/complete` body={dispositions}** → 成功后一次性 refetch,弹窗关、页面呈现完成态。
- 删除前端逐条 PUT 循环(doConfirmDisposition/doBacklogAll 的 PUT 部分;「进 Backlog」也走同一次 body,全部 targetGroupId:null)。
- 失败处理:请求 4xx/5xx → 弹窗内 setError(统一错误形状),组状态不变(事务回滚),用户可改计划重试。

### 2.3 否决的备选

- **两阶段提交/事务 ID 表**:过重,单库单事务足够;否决。
- **前端先处置后完成**:反转矛盾(成员动了、组没完成)且改 0105 语义;否决。

## 三、测试

### 3.1 集成(ItemGroupApiTest 或新文件)

1. complete+dispositions:组 completed、非终态成员入目标组、本组无残留行、Done 行保留——**一次请求后断言全部**;
2. 回池行(targetGroupId=null):成员入 Backlog、全行清;
3. 非法 itemId → 400,且**组状态未变**(原子性探针);
4. 无 body complete:行为同今(回归)。

### 3.2 e2e(group-complete-disposition.spec.ts 适配)

- 各处置例改为断言**单次 complete 请求**(waitForResponse 1 个),完成页缺口断言保留;
- 「进 Backlog」例同改。

### 3.3 手工

T110A:dev 建组 3 成员(1 Done)→ 完成 → 混合处置(1 进组 1 回池)→ 一次点击后:组完成、成员各归其位、完成组页只剩 Done。T110B:dev 停后端模拟中断?不必——3.1 例 3 即原子性证据。

## 四、演化记录

- 2026-11-07 立票 YPJ-159;本 spec 修订 0106 §D 流(前端两段→单事务)与 0109 §按钮(交互不变、提交通道改)。
