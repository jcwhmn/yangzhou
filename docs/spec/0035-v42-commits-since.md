# 0035 — YPJ-42 提交历史按需加载(since 过滤)

> 伞设计:spec 0033 §四。票:YPJ-42。分支 `cwjiang/YPJ-42-commits-since`。

## ① 需求

**背景**:`GET /api/items/{id}/commits`(V16-2)对分支 ref 全量拉第一页 20 条提交;分支从 main 抬出后,列表混入大量分支创建前的历史提交,dogfood 实证噪音大。`item_git_ref.created_at` 已记分支创建时刻(YPJ-27 起)——天然过滤锚点。

**范围**:commits 查询按 `ref.createdAt` 之后过滤;GitHub 侧 `since` 参数优先,响应内过滤兜底。CLI `yz log` 与 web 详情页消费同一端点,语义透明生效,两端零改动。

**非目标**:不动 schema、不加翻页、不动 CLI/web(端点契约形状不变,无新参数——api.http 不动)。

**验收**:commits 结果不含分支创建之前的提交。

## ② 详细设计

**后端 4 文件 + 测试 3 文件**,无 schema:

1. `GithubGateway.kt`:`listCommits(repo, sha, token)` → `listCommits(repo, sha, since: Instant?, token)`。
2. `RealGithubGateway.kt`:since 非空时 query 追加 `&since=<ISO_INSTANT>`(GitHub 原生参数)。
3. `GithubCommitService.commitsForItem`:传 `ref.createdAt`;**响应内兜底过滤**——GitHub 对 `since` 只按 commit date 过滤且边缘有迟到提交的可能,服务侧再滤一层 `date != null && Instant.parse(date) >= ref.createdAt`(date 为 null 的保留,宁多勿漏)。
4. 测试 fake 三处签名同步:`GithubCommitsApiTest`(fake 改为**不 honor since**,专验服务侧兜底过滤,并记录 lastSince 供断言)、`GithubSyncApiTest`、`TimeSuggestionApiTest`(仅加参数)。

**新测试**(GithubCommitsApiTest):
- fake 返回一条分支创建前 + 一条创建后的提交(fake 忽略 since,模拟 GitHub 不过滤)→ 响应只剩创建后那条 → 证明兜底生效;断言 fake 收到的 lastSince == ref.createdAt。
- 既有 fixture:seed SQL 补 `created_at = '2026-10-01'` 显式过去时刻,原 2026-10-06 提交日期不变,兼容兜底过滤。

**否决的备选**:
- 只做服务侧过滤、不传 since——浪费 GitHub 能力,首页 20 条可能被旧提交占满导致过滤后为空;两层都要。
- CLI/web 端各自过滤——重复逻辑,端点层收口即可。

## ③ 手工测试用例(供 build/debug)

1. PM 实例(8081):任一已建分支的 item 详情页 → 提交历史不含分支创建之前的提交(对比 `git log` 分支起始)。
2. CLI:`yz log <item>` 同上语义。
3. 未挂仓库/无 PAT 报错行为不变。
