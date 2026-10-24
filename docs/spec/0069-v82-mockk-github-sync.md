# 0069 — YPJ-82 mockk 子票③:github sync 事件扇出/封口规则单测

> 票:YPJ-82(YPJ-38 子票③)· 影响:仅新增 `GithubSyncServiceUnitTest`

## 1. 需求

GithubSyncService 的规则分支单测:applyEvent 迁移规则 + syncAll 扇出装配。引擎无关;gateway/仓库协作者 mockk(relaxed)。

### 范围

- **applyEvent**(核心,8 例):rule 缺失 / target status 缺失 / item 缺失 → no-op;current final → 封口跳过;target.position ≤ current → 只前进跳过;WIP 限满 → 只记 WIP activity 不迁移;happy → items.save + historyRepo.save(source="github") + notifyStatusChange;
- **syncAll**(4 例):无 workspace / 无 token / 无挂载 → 静默;gateway 404(VcsApiException)→ WARN 不炸,下一挂载继续;
- **扇出**(3 例):分支 head 内嵌键命中 → gitRef 落行 + BRANCH_CREATED 事件;既有 gitRef → 跳过;键不匹配项目 → 跳过。

### 非目标

PR author 映射细节(V10-S5,集成覆盖)、轮询调度器。

## 2. 详细设计

- 新文件 `.../business/github/GithubSyncServiceUnitTest.kt`;
- 协作者 relaxed;泛型 save 坑显式 firstArg(gitRefs/items/history);
- `PR_MERGED` 事件常量经 `VcsEvent.PR_MERGED.wire` 引用,不抄字符串。

否决:集成补(已有 GithubSyncApiTest)。

## 3. 手工测试用例

| # | 步骤 | 期望 |
|---|---|---|
| TC1 | `gradle :api:test --tests "*GithubSyncServiceUnitTest"` | 全绿 |
| TC2 | `gradle build --no-daemon` | 全绿 |
