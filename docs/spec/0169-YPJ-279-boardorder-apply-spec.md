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
{ "itemIds": ["<uuid>", ...] }   // 按目标顺序罗列;宽容校验,见下
```

处理(单事务,**宽容语义**,2026-11-25 用户拍板):不要求全列、不要求全部有效,无 400 校验分支——

1. **去重**:同一 item 出现多次,以后面的顺序为准(后者覆盖前者),即保留末次出现位置。
2. **忽略无效项**:不存在 / 不属该项目 / 未上看板的 item 直接忽略,不报错、不阻断。
3. **定位**:有效 item(去重后)按给定顺序写 `position = 1..n`;跨列允许——位次按 item 逐行存,各列视图只看自己成员,列表内同列成员的相对序即目标序。
4. **未列入的列内成员**:按当前显示序(既有 position ?? MAX 沉底 + 编号自然序)接在 n 之后,一次性物化(沿 0166「首次触碰物化」模式)。
5. 事务内完成;无效项不产生任何写入。

响应 = 0166 move 响应同形 + 报告字段(2026-11-25 用户拍板):

```json
{
  "status": "...",
  "ordered": [{ "itemId": "...", "boardOrder": 1 }, ...],   // 受影响各列重写后的新序
  "report": {
    "applied": ["YPJ-a", ...],     // 实际生效的目标顺序(编号)
    "ignored": ["YPJ-b", ...],     // 无效被忽略(不存在/不属项目/未上看板)
    "deduped": ["YPJ-c", ...]      // 重复且被后者覆盖、不再生效的早期出现
  }
}
```

## 四、分支真值表

| # | 输入情形 | 行为 |
|---|---------|------|
| B1 | 全部有效、无重复 | 按 1..n 重写位次,200 + 新 ordered |
| B2 | 列表跨多列(不同 status) | 合法:逐行写位次,各列内相对序 = 列表相对序 |
| B3 | itemIds 有重复 | 后者覆盖前者,200;report.deduped 列出被覆盖的早期出现 |
| B4 | itemIds 含不存在/不属项目/未上看板的 item | 忽略该项,200;report.ignored 列出 |
| B5 | itemIds 为空数组 / 全部无效 | 400(itemIds 至少一个有效项) |
| B6 | itemIds = 全列成员(完整列表) | 合法(B1 特例),列序=给定序 |
| B7 | 目标列全部成员都未排过(position 全 null) | 目标序列物化为 1..n,其余(若有)接后 |
| B8 | 单元素列表 | position=1,200 |

## 五、实现要点

- **后端**:`ItemService.applyBoardOrder(projectKey, itemIds)`;repo 层仅用既有 save(逐行 `save(row.copy(position=…))`),无跨表批量 SQL,不涉 JDBC client;无需新迁移(表已存在)。
- Controller 归 `yangzhou.api.business.item`,类级 `/api` 前缀,资源段方法级;请求体 Bean Validation(itemIds 非空)。有效项为 0 时后端 400。
- **CLI**:替换 `747380e` 的 boardorder-apply 内部实现——resolve 全部 KEY-N → 单次 POST apply;输出(2026-11-25 用户拍板):逐行生效序(`1. YPJ-a`…)+ 报告段:`忽略:`/`重复(后序覆盖):` 列出对应编号 + `最终顺序:` 单行列出 applied;`--json` 输出完整响应。帮助文案不变。
- spec 0273 的单票 `items boardorder` 保留不动(单票微调场景仍走 move)。

## 六、验收标准

- [ ] 集成测试(AbstractApiTest):B1-B8 全分支绿;B3/B4 断言 200 且 report 字段正确;B5 断言 400 且库内 board_item_order 无任何写入。
- [ ] B6 场景端到端:apply 后 `GET board` / move 的 ordered 与给定顺序一致。
- [ ] CLI:`items boardorder-apply` 单次调用(可在测试实例抓请求数验证 ≤1 次 apply + N 次 resolve GET);重复项报错。
- [ ] `gradle build` + 相关 integrationTest 绿;`npx`/web 无改动不涉及。
- [ ] `backend/script/api.http` 补 apply 端点手测块(改 REST 必同步)。
- [ ] 完成后用新命令对 YPJ 看板 To Do 列执行一次优先级重排(顺序另行给用户确认)。

## 七、开放决策清单(review 时拍板)

1. **D1 未列入成员后延**(§三.4):✅ 已拍板(2026-11-25):保持相对序接在目标序列之后。
2. **D2 prod 半改状态处理**:2026-11-25 偷跑中断,prod 列序现为半新半旧(快照 `.scratch/board-now.json` 可恢复)。推荐**不恢复**,本票落地后用新命令一次重排到位;若要求恢复,恢复操作也走新 API(落地后)或临时 node 脚本。**待拍板**。
3. **D3 同列校验**:✅ 已拍板(2026-11-25):宽容语义,取消同列强制与 400(§三.3);跨列合法。

## 附注

- 本 spec 开工前用户已 review(2026-11-25 约定:文档 review 后才编码)。747380e 为被否决方案的提交,编码阶段在该分支上就地替换,不另开票。
