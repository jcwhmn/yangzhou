# 0273 · YPJ-273 CLI board-order 排序

## 一、目的与范围

CLI 新增 `items boardorder <KEY-N> <目标序号>`:把指定 item 在其看板列中排到目标序号(1-based),原目标位及其后的 item 全部后延一位(即"拔出插入"旋转语义)。

不新增任何 REST 端点;唯一契约 = spec 0166 §3.1 `POST /projects/{key}/board-order/move`。CLI 是瘦客户端。

## 二、语义

目标语义(拔出→插入)= 反复相邻交换(move)的复合,两者等价:item 从位 p 移到位 t(p>t 时,位 t..p-1 各后延一位)。

## 三、实现

`backend/cli Main.kt` 新分支 `items boardorder`:

1. `resolveItemId` 取 itemId,`GET /api/items/{itemId}` 取项目 KEY(仅用于报错语境,不需要列可见性规则)。
2. **探测位次**:调 `move(dir=up)`。响应 `ordered` 中该 item 下标 i:
   - i == 0:item 原在列首,无操作已发生,原位 p = 1;
   - i > 0:探测发生了交换,原位 p = i+2(1-based);调 `move(dir=down)` 撤回(swap 对称,精确还原)。
3. **步进**:列大小 n = ordered.length;目标 t 非法(t<1 或 t>n)→ 报错退出。
   - t < p:调 up ×(p−t);t > p:调 down ×(t−p)。
4. 输出:`已排序:YPJ-N → 第 t 位(列 n 项)`;`--json` 输出末次 ordered。

## 四、错误

- 目标序号非正整数 / 超列长 → CLI 报错,不调 move。
- item 不存在 / 不属项目 → 后端 404 透传。

## 五、验收

- [x] 探测-撤回往返后列序不变(单测:mock 或断言两次调用 ordered 一致)。
- [x] `items boardorder` 移到中间位:目标位及其后整体后延。
- [x] t=1 / t=n 边界可用;t 超界报错。
- [x] `gradle :cli:test` 绿。
