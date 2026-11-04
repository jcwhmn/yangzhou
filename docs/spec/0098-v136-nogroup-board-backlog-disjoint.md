# 0098 — V136:无组降级看板排除 backlog items(board ∩ backlog = ∅)

> YPJ-143(2026-11-05)。来源:无组实验(dev NGX 项目,133 items)实证结论第一刀。
> 上篇:0097(v135 ItemGroup workflow unification)。

## ⚠ 显要标注(语义变化,请复核)

无 active sprint 场景下,看板的含义从「全部工作」变为「**非 backlog 集** = 终态 ∪ planned sprint 成员」——纯无组项目里即「已完成归档视图」(实验项目:133 → 66 个 Done)。这是用户 2026-11-05 拍板的字面需求(「backlog 里的 item 不出现在看板」),backlog 谓词是无组场景唯一的筛选器,board 取其补集。若本意是别的(如看板只显 active),编码前纠正。

## ① 需求

- **背景**:无组实验(NGX,133 items = 66 Done / 50 To Do / 8 In Progress / 5 In Review / 4 QA,0 组实例)实证:board 降级分支(无 active sprint)返回全量 133,与 backlog(67)完全重叠。backlog 里的 item 同时出现在看板 → 无组场景仅有的筛选失效,看板堆砌(Linear 后期问题重现)。
- **需求(不变量)**:`board ∩ backlog = ∅`。backlog 里的 item 永不出现在看板,任何场景。
- **范围**:仅 `ItemGroupService.board` 的降级分支(无 active sprint)。有 active sprint 分支不动——active sprint 成员被 backlog 谓词排除(currentSprint),不变量已自然成立。
- **非目标**:hide done 开关 / 搜索(q 参数)/ board 列语义(按 statusType 分列)/ saved views / no-group 模式产品化(建项目强制种组类型是否改可选)——观察实验后续另行开票。

## ② 详细设计

### 改动点(1 处)

`backend/api/src/main/kotlin/yangzhou/api/business/itemgroup/ItemGroupService.kt` `board()` 降级分支:

```kotlin
// 现状:全量降级(与 backlog 重叠)
if (active.isEmpty()) return BoardDto(emptyList(), itemService.list(key))
// 改为:补集 = 全量 − backlog
if (active.isEmpty()) {
    val backlogIds = itemService.list(key, backlogOnly = true).map { it.itemId }.toSet()
    return BoardDto(emptyList(), itemService.list(key).filterNot { it.itemId in backlogIds })
}
```

### 行为真值表(状态 × 数据 → board items)

| 场景 | backlog 谓词 | board items(改后) | 交集 |
|---|---|---|---|
| 有 active sprint | 非终态 ∧ 不在 planned/active sprint | active sprint 成员并集(现状不变) | ∅(成员被 currentSprint 排除) |
| 无 active,无任何 sprint 组 | = 全部非终态 | 全部终态 | ∅ |
| 无 active,有 planned sprint | 非终态 ∧ 不在 planned/active sprint | 终态 ∪ planned sprint 成员 | ∅ |

### 否决的备选

- `ItemService.list` 加第二布尔旗(excludeBacklog):两布尔组合语义易错,否决
- 统一改写有组分支谓词:有组语义(active sprint 成员并集)是 YPJ-29 既有裁决,不动
- 抽公共谓词方法(buildCtx 复用):两处调用场景不同,过度抽象

### 性能

降级分支 2× `list()`(DTO 组装 ×2);实验规模毫秒级,board 本身即重查询,不为省一次调用引入抽象。

### 测试(编码前先读 `.agents/skills/backend-testing/SKILL.md`)

- `ItemGroupApiTest` 现有用例 `board——无 active 降级全量_active sprint 并集`(L233)改名并改断言:无 active 分支 items = 非 backlog
- 新用例:无组项目(不建组实例)建 To Do + Done 各 1 → board items 仅含 Done;backlog 仅含 To Do;断言交集为空
- 边界用例:planned sprint(非 active)成员的非终态 item → 不在 backlog → 出现在降级 board(补集语义,不单列特判)
- REST 形状零改动 → `api.http` 不动;类图不触发(board 为 api 层方法内部行为,非 domain 实体/签名)

## ③ 手工测试用例(dev,合并后 rebuild dev)

1. 打开 3001 → 项目「无组实验」(NGX)→ 看板:**只剩 66 个 Done**(不再 133)
2. backlog:**仍 67**,不变
3. 互斥肉眼核:看板任一 item 均不在 backlog 列表(反之亦然)
4. 回归:切到有 active sprint 的项目 → 看板仍显示 sprint 成员(有组行为零变化)
