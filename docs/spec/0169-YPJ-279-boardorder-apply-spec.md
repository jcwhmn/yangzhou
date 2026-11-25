# 0169 · YPJ-279 看板整列重排:board-order/apply

## 一、目的与范围

现状:排序唯一入口 = spec 0166 §3.1 `POST /projects/{key}/board-order/move`(相邻交换 up/down)。整列按优先级重排需 N 次 CLI 调用,每次内部再走 2+位置 次步进 HTTP,重排一列 ≈ O(n²) 次调用(YPJ-273 的探测-撤回-步进方案),23 张票实测数百次调用且中途可中断致半改状态(2026-11-25 实况)。

本票新增**批量重排能力**:

1. **REST**:`POST /api/projects/{key}/board-order/apply`——一次调用把一列排成目标顺序。
2. **CLI**:`items boardorder-apply <KEY-N>...`——参数顺序即目标顺序,瘦客户端单次调上述端点(替换本分支上已提交的「CLI 循环 move」实现,`747380e` 只保留命令骨架,内部改调新端点)。

范围外:不改 move 端点、不改前端看板页、不改 `board_item_order` 表结构。

## 二、术语

- **列**:item 所属 status 的看板可见子集(onBoard 或在当前 active sprint),沿 0166 既有口径。
- **位次**:`board_item_order.position`,列内 1..n 显示序;null = 未排过,沉底按编号自然序(0166 既有)。

## 三、数据语义

请求体:

```json
{ "itemIds": ["<uuid>", ...] }   // 按目标顺序罗列;全部须属同一列(同一 status),否则 400
```

处理(单事务):

1. 取 `itemIds[0]` 所属列(status → visible 子集),校验列表中每个 item 均在该列,且无重复 → 违反即 400(code=BAD_REQUEST)。
2. 列表内 item 按给定顺序写 `position = 1..n`。
3. **列内未列入的成员**:按其当前显示序(既有 position ?? MAX 沉底 + 编号自然序)排在 n 之后,一次性物化(沿 0166「首次触碰物化」模式)——即整列被完整重写为「目标序列在前,其余保持相对序在后」。
4. 事务内完成;任一 item 校验失败则整批不写。

响应 = 0166 move 响应同形:`{ status, ordered: [{itemId, boardOrder}] }`(重写后的全列新序)。

## 四、分支真值表

| # | 输入情形 | 行为 |
|---|---------|------|
| B1 | itemIds 全属同列、无重复 | 重写整列位次,200 + 新 ordered |
| B2 | itemIds 混列(不同 status) | 400,不写任何行 |
| B3 | itemIds 有重复 | 400,不写任何行 |
| B4 | itemIds 含不属该项目/不存在的 item | 404,不写任何行 |
| B5 | itemIds 为空数组 | 400 |
| B6 | itemIds = 全列成员(完整列表) | 合法(B1 特例),列序=给定序 |
| B7 | 列内全部成员都未排过(position 全 null) | 目标序列物化为 1..n,其余(若有)接后 |
| B8 | item 在板上但列只有一个成员 | 单元素列表 → position=1,200 |

## 五、实现要点

- **后端**:`ItemService.applyBoardOrder(projectKey, itemIds)`;repo 层仅用既有 save(逐行 `save(row.copy(position=…))`),无跨表批量 SQL,不涉 JDBC client;无需新迁移(表已存在)。
- Controller 归 `yangzhou.api.business.item`,类级 `/api` 前缀,资源段方法级;请求体 Bean Validation(itemIds 非空)。
- **CLI**:替换 `747380e` 的 boardorder-apply 内部实现——resolve 全部 KEY-N → 单次 POST apply;`--json` 输出响应 ordered。帮助文案不变。
- spec 0273 的单票 `items boardorder` 保留不动(单票微调场景仍走 move)。

## 六、验收标准

- [ ] 集成测试(AbstractApiTest):B1-B8 全分支绿;B2/B3/B5 断言 400 且库内 board_item_order 无任何写入。
- [ ] B6 场景端到端:apply 后 `GET board` / move 的 ordered 与给定顺序一致。
- [ ] CLI:`items boardorder-apply` 单次调用(可在测试实例抓请求数验证 ≤1 次 apply + N 次 resolve GET);重复项报错。
- [ ] `gradle build` + 相关 integrationTest 绿;`npx`/web 无改动不涉及。
- [ ] `backend/script/api.http` 补 apply 端点手测块(改 REST 必同步)。
- [ ] 完成后用新命令对 YPJ 看板 To Do 列执行一次优先级重排(顺序另行给用户确认)。

## 七、开放决策清单(review 时拍板)

1. **D1 未列入成员后延**(§三.3):推荐「保持相对序接在目标序列之后」;备选「未列入即 400,要求传全列」——后者强制每次给全量,更严格但更繁琐。
2. **D2 prod 半改状态处理**:2026-11-25 偷跑中断,prod 列序现为半新半旧(快照 `.scratch/board-now.json` 可恢复)。推荐**不恢复**,本票落地后用新命令一次重排到位;若要求恢复,恢复操作也走新 API(落地后)或临时 node 脚本。
3. **D3 列定义**:校验「全部同列」是否过严?备选:允许跨列,逐列分组各自重写——实现更复杂,当前无场景,不推荐。

## 附注

- 本 spec 开工前用户已 review(2026-11-25 约定:文档 review 后才编码)。747380e 为被否决方案的提交,编码阶段在该分支上就地替换,不另开票。
