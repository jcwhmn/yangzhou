# 0070 — YPJ-83 mockk 子票④:time 工时建议 + 记录校验单测

> 票:YPJ-83(YPJ-38 子票④)· 影响:仅新增 `TimeEntryServiceUnitTest`

## 1. 需求

TimeEntryService 规则分支 mockk 单测:记录/计时模式校验、自动停表、stop 归属与幂等、commit 时间轴建议(buildSuggestions 纯函数直测)。

### 范围

- `create`:minutes 与 startedAt/endedAt 互斥 400;endedAt 无 startedAt 400;endedAt ≤ startedAt 400;minutes ≤ 0 400;池外 409(assertAssignable);计时模式自动停表中未结束条目;记录模式 minutes 落库(startedAt=now-min);
- `stop`:非本人 404(不暴露他人记录);已结束 409;正常停(endedAt 落);
- `buildSuggestions`(companion 纯函数):<2 个有效 commit → 剔除;正常 → first/last 时间轴 + commitCount;坏日期被忽略。

### 非目标

listForItem/summary 装配、commitsForItem 拉取链路(集成覆盖)。

## 2. 详细设计

- 新文件 `.../business/time/TimeEntryServiceUnitTest.kt`;协作者 relaxed;泛型 save 显式 firstArg(P0);`Instant.now()` 直用(被测代码即如此,断言不依赖具体时刻)。

否决:集成补(已有覆盖)。

## 3. 手工测试用例

| # | 步骤 | 期望 |
|---|---|---|
| TC1 | `gradle :api:test --tests "*TimeEntryServiceUnitTest"` | 全绿 |
| TC2 | `gradle build --no-daemon` | 全绿 |
