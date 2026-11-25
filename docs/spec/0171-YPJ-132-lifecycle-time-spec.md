# 0171 · YPJ-132 Item 生命周期时间:startedAt / completedAt(C 案:纯派生)

## 一、目的与范围

给 item 透出两个生命周期时刻:**startedAt**(首次开始开发)与 **completedAt**(完成时刻),**不新增任何存储**——读侧从既有 `item_status_history` 按甘特同款公式实时派生,经 ItemDto/列表行/详情 API 透出,供表格视图与详情页展示,并为 276(右 sidebar)铺路。

> 2026-11-25 用户拍板:C 案(派生),放弃票面 B 物化案——**主属性不随意增加**(用户约束);扩展属性案亦否决(系统托管时间戳不应混入用户词表,且 String 无时间类型)。

**非目标**:不加表列/不加扩展属性;不做工时/计时;不做迁移历史 UI;不做流程度量视图(另票);**不改动甘特图读侧**(它已用同一公式,本票把公式提取共享,甘特改调 helper,行为零变化)。

## 二、术语与现状

- 状态三态 = `Status.statusType` 枚举 `START / ACTIVE / FINAL`。
- `item_status_history` 已有:每次状态迁移留痕(建票、迁状态、GitHubSync 三处写入)。
- **既有口径(逐字复用)**:甘特图(spec 0150 §3.4/§6.6,YPJ-245 校准)actualStart = **首次**迁出 START(首条 to ∉ START 的 history);actualEnd = **最近一次**迁入 FINAL。

## 三、数据语义(= 派生公式,单点实现)

- `startedAt(item)` = 该 item history 中首条 `to ∉ START` 记录的 createdAt;无则 null。
- `completedAt(item)` = 该 item history 中最后一条 `to ∈ FINAL` 记录的 createdAt;无则 null。
- 重开、打回待办等语义由公式天然表达:**不存在物化值,无「清不清」问题**(原票面 D1/D2 随 C 案消解)。
- 实现落点:提取共享 helper(如 `LifecycleTimes.of(item, histories, startIds, finalIds)` 或 service 内私有函数),甘特 `ProjectService` 的 actualStart/actualEnd 改调同一 helper(行为等价,纯重构);ItemService 列表/详情与 276 后续读同一出口。

## 四、分支真值表(= 公式行为,集成测试断言)

| # | history 形态 | startedAt | completedAt |
|---|-------------|-----------|-------------|
| B1 | START→ACTIVE(首次) | 该条时刻 | null |
| B2 | START→ACTIVE→DONE | 首迁出时刻 | 迁入 FINAL 时刻 |
| B3 | …→DONE→ACTIVE(重开) | 不变(首迁出) | **保留末次 DONE 时刻** |
| B4 | ACTIVE→START(打回) | **保留首次迁出** | null |
| B5 | DONE→ACTIVE→DONE(返工) | 不变 | 最近一次 DONE 时刻 |
| B6 | 始终 START | null | null |
| B7 | 多 FINAL 状态间迁移(FINAL→FINAL) | 不变 | 覆盖为最近 |

## 五、实现要点

- **后端**:公式单点(共享 helper);ItemService 列表行(ItemDto 或列表 DTO)新增 `startedAt`/`completedAt` 两字段(计算值,向后兼容);甘特 ProjectService 两行改调 helper,响应形状不变。
- **批量读**:列表/表格场景一次 `findByItemObjectIdIn` 取整页 history,内存分组计算(甘特同款,不逐 item 查)。
- **前端**:
  - 表格视图加「开始」「完成」两列(colOrder 已支持,默认隐藏);
  - item 详情页既有元信息区展示两时刻(276 右 sidebar 是最终归宿,届时迁入)。
- **迁移**:无。**entities.puml**:无 schema 变化,不动。

## 六、验收标准

- [ ] 集成测试:B1-B7 全分支绿;同一项目内断言 DTO 的 startedAt/completedAt 与甘特行 actualStart/actualEnd **完全一致**(同一数据两个出口同值)。
- [ ] GitHubSync 触发的迁移同样被派生覆盖(有既有 sync 测试可仿)。
- [ ] `gradle build` + integrationTest 绿;前端 `npx tsc --noEmit` + format 过;e2e 固定 3002/8082(需环境时找用户)。
- [ ] 表格视图新列默认隐藏、可开启;详情页可见两时刻。

## 七、开放决策清单(review 时拍板)

1. **D3 表格视图两列默认隐藏**:票面已倾向,确认即可。
2. **D4 开发时长(= completedAt − startedAt)是否顺带展示**:倾向本票不加,276 sidebar 再议。

## 八、流程

- 已 assign me + In Progress;分支 `feature/YPJ-132-lifecycle-time`(编码阶段开);预估 4h。
