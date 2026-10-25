# 0073 — YPJ-85 GitHub webhook 端点(秒级同步触发)

> 票:YPJ-85(YPJ-51 双向同步的前置)· 影响:`backend/api`(github slice + security)+ `application.yml` + `api.http`

## 1. 需求

GitHub push / pull_request 事件触发时,不再等 5 分钟轮询,而是秒级触发既有同步逻辑。

### 范围

1. 新端点 `POST /api/github/webhook`,**匿名**(permitAll),接收 GitHub webhook 回调
2. 签名校验:`X-Hub-Signature-256` = `sha256=<hex(HMAC-SHA256(secret, rawBody))>`;secret 来自配置 `yangzhou.github.webhook-secret`(env `YANGZHOU_GITHUB_WEBHOOK_SECRET`);**未配置/空 → 一律 403 全拒**
3. 事件路由:`push` / `pull_request` → 按 payload 的 `repository.full_name` 查 `project_repos` 挂载 → 对每个命中挂载调既有同步(幂等);其它事件 / 未知 repo → 200 忽略
4. 同步成功 → `202 Accepted`(同步为同步调用,dogfood 仓库小可接受)

### 非目标

- 事件内容建票、issue 事件、payload 细粒度解析(仍走全量 syncRepo,幂等)
- 5 分钟轮询保留作兜底,不移除
- secret 轮换、多 secret 并存

## 2. 详细设计

### 修改文件

| 文件 | 改动 |
|---|---|
| `application.yml` | 加 `yangzhou.github.webhook-secret:`(空默认;8081 启动需 env 注入,值在用户本地存档,不入仓) |
| `GithubSyncService.kt` | `syncRepo` 由 `private` 改 `internal`(同模块 webhook 触发复用;不加新方法) |
| `github/WebhookSignature.kt`(新) | 纯函数对象:`verify(secret, rawBody, header): Boolean`;HMAC-SHA256 + 常量时间比较(`MessageDigest.isEqual`),header 格式 `sha256=xxx` 不符即 false |
| `github/GithubWebhookController.kt`(新) | `@PostMapping("/api/github/webhook")`;`@RequestHeader("X-Hub-Signature-256")`、`@RequestHeader("X-GitHub-Event")`、`@RequestBody rawBody: String`(保原始字节做签名);Jackson `readTree` 取 `repository.full_name` |
| `persistence/repositories.kt` | `ProjectRepoRepository` 加 `findByRepo(repo: String)`(若已有等价查询则复用) |
| `security/SecurityConfig.kt` | permitAll 列表加 `/api/github/webhook` |

### 流程

```text
POST /api/github/webhook
  → secret 空 → 403
  → 签名不符 → 403
  → event ∉ {push, pull_request} → 200 {"status":"ignored"}
  → full_name 查不到挂载 → 200 {"status":"ignored"}
  → 对每个命中挂载:workspace PAT + syncRepo(project, repo, token)(异常 WARN 吞掉,不 500)
  → 202 {"status":"accepted"}
```

### 否决的备选

- 异步(@Async/队列):dogfood 规模同步 < 数秒,异步引入线程池/重试复杂度,不值;真超时再演进
- 解析 payload 增量同步(commits/changed refs):syncRepo 幂等且已有测试,增量解析是重复造逻辑
- 单独 `WebhookService`:逻辑 ~30 行,直接进 controller + 签名纯函数,不加层

## 3. 测试

- **单元**(新 `WebhookSignatureTest`):正确签名 / 篡改 body / 错误 secret / 非 sha256= 前缀 / 空 header
- **集成**(extends AbstractApiTest):403 无签名;403 错签名;200 忽略未知事件;202 push 命中(mock VcsGateway 或空挂载 200 路径)
- **手工测试用例**(用户执行):

| # | 步骤 | 期望 |
|---|---|---|
| 1 | 8081 带 secret env 启动;tunnel/funnel 已指 8081 | 启动正常 |
| 2 | `gh api repos/jcwhmn/yangzhou/hooks` 建 webhook(funnel URL + secret,events: push, pull_request) | ping 返回 200 |
| 3 | 错误 secret 的 curl POST | 403 |
| 4 | 推 `YPJ-85/...` 分支 | 数秒内 item 详情出现 branch 记录(不等 5 分钟) |

## 4. As-built

- 实现与设计一致,两处细化:
  - 未配 PAT 时命中挂载 → 也回 202(WARN 跳过同步),而非 200;契约与「事件已受理」语义一致
  - `ProjectRepoRepository` 新增派生查询 `findByRepo(repo): List<ProjectRepo>`(无自定义 SQL,按测试规矩不另写 repository 集成测试)
- 测试:单测 `WebhookSignatureTest`(4 例)+ 集成 `GithubWebhookApiTest`(5 例:403×2 / 200 ignored×2 / 202×1;202 用例不配 PAT,不触外网)
- `gradle build` 绿;`api.http` 第 12 节按序号新增正例 + 负例区 2 条
- 追加(手测实锤):GitHub webhook 10s 超时,syncRepo 全量拉取会超 → 改为控制器内单线程 daemon executor 后台执行,命中即回 202;spec 1 节「同步调用可接受」假设作废
- 待办(非本票):8081/8080 启动均需 env `YANGZHOU_GITHUB_WEBHOOK_SECRET`;GitHub 仓库侧 webhook 由用户经 `gh api` 配置(funnel URL + secret,events: push/pull_request)
