# spec 0057 — V74 YPJ-40 代码托管平台抽象:gateway 泛化 + 默认状态映射去固化

> 票:YPJ-40(V21 架构收敛, feas GREEN);背景 = YPJ-36 #102 评审三处未尽。

## ⚠ 架构边界说明(置顶)

- **非目标(票面已定)**:GitLab/Gitee 具体 adapter(等真实需求);轮询/防呆改动。
- 本票是纯后端重构 + 种子语义修正,**零 API 契约变更、零 schema 变更**(无迁移)。
- 「默认状态映射去固化」二选一,本 spec 推荐 **方案 A 语义化**;理由与被否决的 B 见 §二.4。**请裁决后动工。**

## 一、需求

### 范围
1. gateway 泛化:平台无关 interface(无 GitHub 专名),现 GitHub 实现归入 adapter 形态。
2. 默认状态映射去固化:项目创建时的默认规则种子不再按状态**名字**匹配,改按**语义**(start/final/顺序)推导;非标 workflow 项目开箱行为有定义。
3. 事件集显式化:现有固定 4 事件(branch_created/pr_opened/pr_merged/pr_closed_unmerged)收敛为具名常量/枚举,作为平台无关事件集(超集留白)。

### 非目标
- GitLab/Gitee adapter;workspace 级映射模板配置(归 YPJ-49 项目模板);规则 CRUD/轮询节奏不动。

## 二、详细设计(代码级)

### 1. gateway 泛化(`api/github/` 包内收敛,不挪包——切片命名照旧,包内语义泛化)
- 新接口 `VcsGateway`(方法与现 `GithubGateway` 一一对应):
  - `listBranches(repo, token): List<String>`
  - `listPullRequests(repo, token): List<RepoPr>`
  - `createBranch(repo, branch, fromBranch, token)`
  - `listCommits(repo, sha, since, token): List<RepoCommit>`
  - `listPullRequestCommits(repo, number, token): List<RepoCommit>`
- DTO 改名:`GithubPr → RepoPr`、`GithubCommit → RepoCommit`(字段不变);`GithubApiException → VcsApiException`(保留 status)。
- 现接口更名 `GithubGatewayAdapter`(impl `RealGithubAdapter`),`@Service` 实现 `VcsGateway`;`@Primary`/唯一 bean,Spring 注入点全部改依赖 `VcsGateway`。注入方(GithubCommitService/GithubSyncService/branch 创建等)仅改类型,调用零改动。
- 未来多平台:新 adapter 实现同接口 + 按 workspace 配置选择(本轮不做,单一 bean 即可,留注释锚点)。

### 2. 事件集显式化
- 现 `EVENT_BRANCH_CREATED` 等 4 常量(github 包)收敛为 `enum class VcsEvent { BRANCH_CREATED, PR_OPENED, PR_MERGED, PR_CLOSED_UNMERGED }`(wire 值维持现字符串,`workflow_rules.event` 列零迁移)。
- 引用点:ProjectService 种子、WorkflowService 规则校验、GithubSyncService 事件触发。逐一改 enum,`value` 属性带原字符串。

### 3. 默认种子语义化(方案 A)
- 现:`mapOf(EVENT_BRANCH_CREATED to "In Progress", EVENT_PR_OPENED to "In Review", EVENT_PR_MERGED to "Done")` 按**名字**找 status。
- 改为按语义推导(V7 五列默认模板下结果与现状完全一致):
  - `BRANCH_CREATED` → 第一个非 start 列(无 → 不种)
  - `PR_OPENED` → 第二个非 start 列(无 → 不种,= 人管,沿 YPJ-36 裁决精神)
  - `PR_MERGED` → 第一个 final 列(无 → 不种)
  - `PR_CLOSED_UNMERGED` → 恒不种(现状不变)
- 非标 workflow(如 3 列 To Do/Doing/Done)开箱:branch→Doing、pr_merged→Done、pr_opened 不种——**有定义、不静默错配**。
- `defaultStatuses` 种子本身不动(状态模板是另一回事,票面未要求)。

### 4. 二选一裁决(推荐 A)
- **A 语义化(推荐)**:零 schema、零配置面、开箱行为有定义;改名/非标均不断链(规则落库本就是 statusId)。缺点:语义规则(第二非 start 列)略隐晦——代码注释 + spec 记录即可。
- **B workspace 级默认模板**:可配置性最强,但需要新表/配置端点/迁移,且 **YPJ-49(项目模板与建项向导)正要做模板系统**——B 是它的子集,现在做是重复建设。推迟到 49,届时本票的语义推导作为模板缺省值。

### 5. 测试(单测优先)
- 单测:种子语义推导(标准五列 = 现状快照;3 列/无 final/单列各一例);VcsEvent wire 值不变。
- 集成(关键路径):建项目 → 默认规则断言(改用 statusId 语义断言,不再断言名字);github 规则 CRUD 端点回归。
- 既有测试:改名导致的 import/类型替换跟着改;GithubSync 相关 fake gateway 实现新接口名。
- e2e:不改(行为对默认模板不变)。

## 三、手工测试用例(供 build/debug)

1. 新建项目(默认五列)→ 规则列表与旧行为一致(branch→In Progress、pr_open→In Review、pr_merged→Done、unmerged 空)。
2. 项目里把 "In Review" 改名 → 规则仍指向原列(statusId),不断链。
3. 手工删掉一列再建项目式验证(或看单测):3 列项目 pr_opened 不种,无报错。
4. api.http GitHub 区回归:规则 CRUD 照常(事件 wire 值不变)。
