# spec 0053 — V69 GITHub 栏 commits 数据源治理:分支删除后降级 PR 回溯

> 票:YPJ-69。分支:`feat/YPJ-69-commits-pr-fallback`。方案用户已拍板:**远程分支优先 → 无远程分支再查 PR**。

## 1. 需求

合并后删远程分支是最佳实践,但 GITHub 栏提交历史与工时建议按 `item_git_ref` 的 branch ref 直查 `commits?sha={ref}`——分支一删即 404 断供(现 Real gateway 把 404 吞成空表,表现为「无提交」而非报错)。已实证:GitHub PR commits API 在分支删除后永久可用(本仓 20+ 历史 PR 验证)。

**范围**:

1. commits 获取降级链:branch ref 查得到 → 走原路;查不到(空表)→ 按 head 分支名匹配 PR,取 `pulls/{n}/commits`
2. `GithubGateway` 增 `listPullRequestCommits` 方法(gateway 是出网唯一 seam,接口变更波及各 fake)
3. 响应 DTO 增来源标注(`viaPr`),前端 GITHub 栏标注「经 PR 回溯(分支已删)」,数据照常展示
4. AGENTS.md 恢复「合并后删远程分支」步骤

**非目标**:

- 不做存量 `item_git_ref` 迁移——降级匹配走 `listPullRequests` 的 head 分支名,不依赖 DB 里的 pr ref(V6 轮询已在落 `kind=pr`,存量补齐无必要)
- PR commits 不翻页(与现 branch 路径同为第一页量级假设)
- since 语义不变:仍以 **branch ref 的 createdAt** 为界(分支创建前的基线提交不算);PR commits API 不支持 since,在服务侧按 commit date 过滤

## 2. 详细设计

### 2.1 Gateway 接口(`GithubGateway.kt`)

```kotlin
/** PR 上的提交(第一页,新→旧;不支持 since,由调用方按 date 过滤);PR 不存在 → 空表。 */
fun listPullRequestCommits(repo: String, number: Int, token: String): List<GithubCommit>
```

- `RealGithubGateway`:`GET /pulls/{number}/commits?per_page=100`,解析复用 listCommits 的字段提取(抽私有 `parseCommit(node)` 共用);404 → 空表(与 listCommits 归一)
- 测试 fakes 补实现:`FakeCommitGateway`(GithubCommitsApiTest 内)、`FakeGithubGateway`(GithubSyncApiTest 内,其他测试复用)——默认空表/可注入

### 2.2 查询链(`GithubCommitService.commitsForItem`)

对每个 branch ref:

1. `gateway.listCommits(repo, ref, ref.createdAt, token)`(原路,since 服务端语义在 GitHub 侧)。非空 → `RefCommitsDto(viaPr=false)`
2. 空 → 降级:`gateway.listPullRequests(repo, token).firstOrNull { it.headRef == ref.ref }` 按 head 分支名匹配;命中 → `gateway.listPullRequestCommits(repo, number, token)`,服务侧过滤 `date != null && Instant.parse(date) >= ref.createdAt`(date 缺失宁多勿漏,保留)→ `RefCommitsDto(viaPr=true, prNumber=n)`;未命中 → 空表 DTO(viaPr=false)
3. 错误处理保持现状:非 404 `GithubApiException` → `BadRequestException("GitHub API 错误(...)")`;404 在 gateway 内已归一空表

DTO 变更(同文件):

```kotlin
data class RefCommitsDto(val repo: String, val ref: String, val viaPr: Boolean = false, val prNumber: Int? = null, val commits: List<CommitDto>)
```

`TimeEntryService.suggest/buildSuggestions` 只消费 commits 列表,零改动自动受益(工时建议同样获得降级能力)。

### 2.3 前端(`web/app/p/[key]/i/[itemId]/page.tsx` + `web/lib/texts.ts`)

- `CommitBlock` 类型补 `viaPr?: boolean; prNumber?: number`
- commits 区块标题行:viaPr 时在 `{repo} · {ref}` 后追加 muted 文案,如 ` · 分支已删,经 PR #123 回溯`(文案入 texts.ts,中文)

### 2.4 AGENTS.md

Issue 工作流第 5 步(合并后)补回「删远程分支」:`gh pr merge --squash --delete-branch` 或合并后 `git push origin --delete <branch>`。

### 2.5 否决的备选

- **在 DB pr ref 存 head 分支名做匹配**:要迁移 + 回填存量,而 `listPullRequests`(已有 API,50 条窗口)按 head 名匹配即可覆盖 dogfood 量级——不加列
- **branch 404 时报错提示用户**:违背本票目的(数据可回溯,不该报错)
- **给 branch ref 加 `deleted` 标记列**:状态可由查询结果推导,不需要持久化

## 3. 手工测试用例(用户 build/debug 阶段执行)

前置:8081(default profile,PM 库),YZJ 挂载真实仓库,workspace PAT 有效。

1. **分支仍在(回归)**:开一张新票 → 建分支 → 提交 ≥1 commit → PR。item 页 GITHub 栏「加载提交历史」:看到提交,无「经 PR 回溯」标注;工时建议正常
2. **分支已删(降级)**:将用例 1 的远程分支删除(`git push origin --delete <branch>`)→ 再点加载:提交历史仍完整,区块标注「分支已删,经 PR #N 回溯」;工时建议不丢
3. **分支与 PR 均无**:手工建一个无 PR 的 item_git_ref(或找历史无 PR 票):显示「无提交」,不报错

## 4. As-built

(完工后补)
