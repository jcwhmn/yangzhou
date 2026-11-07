# 0109 — 处置终态语义修正:完成组只留 Done 成员 + 按钮命名(YPJ-158,0106 修订)

> 背景(2026-11-06 用户实测):完成 Sprint 后非 Done item 仍显示在完成组页。实锤(T104Z):Sprint 2(completed)4 条 open 行含 test1/test2,而它们同时在 Sprint 3 有 live 行——双行并存。机制:0106 D3 组先行 → 组 completed 后 `replaceMembership` 的 live 过滤不删该组行 → 处置转出后旧行永留。用户拍板:①确认处置/取消处置按钮改为「进「组名」」/「进 Backlog」;②混合处置(部分进组部分进池)由逐行标记+主按钮承担;③完成组只应显示 Done 成员。

## 一、需求

### 1.1 行为(处置弹窗,替换 0106 §1.2 的按钮语义)

| 元素 | 现 | 改后 |
|---|---|---|
| 主按钮 | 「确认处置」 | **「进「{目标组名}」」**——执行逐行计划:未标记行转入目标组,已标记行回 Backlog(混合合法) |
| 次按钮 | 「取消处置」(无操作,靠派生) | **「进 Backlog」**——全部非终态成员显式回池落库(`groupId:null`),含 completed 组旧行清理 |
| 逐行按钮(0107) | 「回 Backlog」/「撤销」 | 不变(标记例外) |
| 摘要行(0107) | 「将转入…N 个;回 Backlog M 个」 | 不变(与主按钮文案互证) |

### 1.2 归属真相修正(核心)

**member 表(item_group_member)= 当前归属真相;历史事实全在 history 表。**

- `replaceMembership`:迁移 item 时,删除该 item 在**同类型的全部旧行**——含 live 行(现状)与 **FINAL 组行(新增)**——逐行 `memberHistory.recordRemove` 关闭区间;然后插入目标行(若目标行已在保留行中则不重复插,防 `uk_item_group_member` 冲突)。
- Done 成员从不被处置 PUT → 行照留 → **完成组页只显示 Done 成员**(D1 语义落到展示层)。
- 用户显式移动 Done item(详情下拉)→ 其完成组行删除 + history 记 removed——「离开过」也是历史事实,由 history 表承载。

### 1.3 存量清理(V52 迁移)

completed(FINAL)组的 open 成员行,按 item 当前状态分流:item 非 FINAL → 删行 + 关 history 区间;item FINAL → 保留。清理后「Done sprint 无非 Done item」对存量立即成立(T104Z 残行、prod V27→V28 迁移残行同规则)。

### 1.4 不变式

1. 重开(0108)不受影响:纯状态迁移不动行;重开后挂入/移出走修正后的归属替换。
2. Backlog 派生谓词不依赖 completed 组残行(清理前后对 Backlog 显示无差,差在完成组页)。
3. 幂等:重复处置同一 item(已在目标组)→ 旧行清理仍执行,目标行不重复。

### 1.5 非目标

- 处置单端点事务化(方案C 仍挂起)
- 域级审计(YPJ-154)
- history 表回填 removed_at 之外的历史修补

## 二、设计

### 2.1 后端(`ItemGroupService.replaceMembership`)

```kotlin
val sameType = rows.filter { rowGroups[it.groupObjectId]?.type == typeKey }
val live = sameType.filter { rowGroups[it.groupObjectId]?.status !in finalNames }
val stale = sameType.filter { rowGroups[it.groupObjectId]?.status in finalNames } // 0109:completed 组旧行
// 幂等早退仅在无 stale 时生效(stale 必须清理)
if (target != null && stale.isEmpty() && live.size == 1 && live[0].groupObjectId == target.objectId)
    return itemService.get(itemObjectId)
(live + stale).forEach { m -> memberships.delete(m); memberHistory.recordRemove(...) }
if (target != null && live.none { it.groupObjectId == target.objectId })
    memberships.save(...); memberHistory.recordAdd(...)
```

- release 组(released=FINAL)的行同样受 stale 规则:item 被显式移出 release → 行删(现状 live 语义本就允许跨 release 移动;完成后组页同理只留 Done)。

### 2.2 V52 迁移(persistence)

FINAL 组(`group_status.status_type='FINAL'` 且组 `item_group.status` 为该 FINAL 名)的 open 成员行,item 状态非 FINAL → `delete from item_group_member` + 关 `item_group_member_history` 区间(`removed_at=now()`)。item FINAL 判定走 item→status→status_type 链(实现时核对 item 侧表名)。

### 2.3 前端(`sprint/[id]/page.tsx` + texts)

- 主按钮:`进「${targetGroupName(disp)}」`(S3 新建态即新名);onClick = 现确认处置逻辑不变
- 次按钮:`进 Backlog`;onClick 改 = 全部行逐个 `PUT group-membership {groupId:null, groupType:l1.typeKey}`(等 summary 的「全部」形态),完成后关弹窗;busy 防中断沿用
- texts:`dispConfirm`/`dispCancel` 删,新增 `dispGoGroup`(模板「进「{name}」」)、`dispGoBacklog`(进 Backlog)

### 2.4 否决备选

- 展示层过滤(组页隐藏非 Done 残行):数据双行事实仍在,backlog/导出等旁路继续脏,否决
- 处置单端点顺带做(方案C):范围膨胀,否决(仍挂起)

## 三、测试

### 3.1 集成(GroupReopenApiTest 旁/新例 + ItemGroupApiTest 对齐)

1. 转出清理:组含 Done+非Done 成员 → complete → 逐行处置转出 → 断言 member 表仅剩 Done 行、被转 item 只有目标组行、history 区间闭合(jdbc 直查)
2. 回池清理:`PUT {groupId:null}` → 该 item 同类型全行删(含 completed 组)、history 关闭
3. 幂等+stale:item 已在目标组但残留 completed 组行 → 再 PUT 同目标 → stale 清、不炸唯一约束、目标行单份
4. 显式移出 Done item → 完成组行删(1.2 语义)

### 3.2 e2e(group-complete-disposition.spec.ts)

1. 现有「终态成员留在本页」断言保留;**新增**:确认处置后完成组页非 Done 成员 count=0(本次事故的缺口断言)
2. 「进 Backlog」例:点击后 Backlog 页可见全部非终态成员,完成组页仅 Done
3. 按钮文案断言:进「{组名}」/进 Backlog
4. 混合例(原覆写例)适配新文案

### 3.3 手工

T106C:完成→进组/进池/混合三走;完成组页核对只剩 Done;重开后再完成回归。
