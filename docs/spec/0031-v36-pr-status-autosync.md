# 0031 YPJ-36:PR 状态自动同步(开 PR → In Review,merged → Done)

2026-10-14 立项。状态:**完工**(As-built 见文末;用户 build/debug 通过)。分支 `cwjiang/YPJ-36-pr-autosync`。

## 需求(票面 + 调查修正)

给 item 建分支开的 PR → 该 item **自动迁 In Review**;PR merged → **自动迁 Done**(对称行为,已拍板 2026-10-12)。

**调查结论(设计依据)**:票面假设要"扩展 GithubSyncScheduler 查 PR 状态",实际 **V6-S2 已把整条管道建完**——`GithubSyncScheduler`(5min 出站轮询)→ `GithubSyncService.syncAll`(listPullRequests → 按 head 分支名解析票号 → item_git_ref 幂等锚点)→ `applyEvent`(经 project_workflow_rule 映射迁移,只前进/final 封口/WIP 防呆,与手动 move 同路径)→ 配置 API `PUT /api/projects/{key}/workflow-rules`。**根因只有一个:没有任何项目配过 rule**(`ProjectService.create` 只种 5 状态不种 rule;实测 YPJ 4 槽全 null)→ applyEvent 全部静默 no-op。

**范围**:①新项目建时种默认规则 3 槽;②存量项目 Flyway 数据回填;③集成测试(默认规则生效路径 + 幂等)。
**非目标**:PR 关闭未合并的回退(**裁决:不做,不配 rule = 人管**,留第 4 槽给用户自配);轮询节奏/防呆逻辑改动;UI 配置面改动(既有 PUT API 即可);REST 契约变更(无新端点 → api.http 不动;无 schema 变更 → entities.puml 不动)。

## 详细设计

### 1. `ProjectService.create` 种默认规则(`backend/api/.../project/ProjectService.kt`)

- 构造器加 `private val workflowRules: ProjectWorkflowRuleRepository`(persistence 既有 repo)+ import github 包事件常量(`EVENT_BRANCH_CREATED` 等,GithubBranchService 已有同款跨包 import 先例)。
- `create` 中 `defaultStatuses(...).forEach { save }` 改为接住保存结果(要 status.objectId),随后私有方法种规则:

```kotlin
/** YPJ-36:默认规则 3 槽;closed_unmerged 留空=人管(票面裁决)。 */
private fun seedDefaultRules(projectId: Long, saved: List<Status>) {
    val byName = saved.associateBy { it.name }
    mapOf(EVENT_BRANCH_CREATED to "In Progress", EVENT_PR_OPENED to "In Review", EVENT_PR_MERGED to "Done")
        .forEach { (event, statusName) ->
            byName[statusName]?.let {
                workflowRules.save(ProjectWorkflowRule(projectId = projectId, eventType = event, statusObjectId = it.objectId))
            }
        }
}
```

- 按**名字**映射而非 position:非标准 workflow(缺 In Review)自然少种一槽,不炸。
- 幂等天然成立:新项目无历史 rule;`uk_project_workflow_rule_project_event` 兜底。

### 2. Flyway 回填存量项目(`V30__seed_default_workflow_rules.sql`)

无 schema 变更,纯 insert-select,每事件一条,按状态名匹配,已配置的项目不覆盖:

```sql
-- YPJ-36:存量项目回填默认规则(按状态名;缺该状态的项目自然不种该槽)
insert into project_workflow_rule (object_id, project_id, event_type, status_object_id)
select gen_random_uuid(), s.project_id, 'branch_created', s.object_id
from status s where s.name = 'In Progress'
on conflict (project_id, event_type) do nothing;

-- pr_opened → 'In Review' / pr_merged → 'Done' 同构,共 3 条
```

- `gen_random_uuid()` PG13+ 内置,本机 PG 16。
- on conflict 兜底:未来某项目已手配时不覆盖。

### 3. 测试(集成,extends AbstractApiTest)

- **ProjectsApiTest +1**:建项目 → `GET /api/projects/{key}/workflow-rules` → branch_created=In Progress、pr_opened=In Review、pr_merged=Done、pr_closed_unmerged=null。
- **GithubSyncApiTest +1**(票验收直测):**不手配规则**,建项目+挂 repo+item → fake gateway 开 PR → sync → item In Review;置 merged → sync → item Done;**再 sync 一轮状态不动、activity 不重复**(幂等)。
- **GithubConfigApiTest 首例断言更新**(实现期补记:该例原名「四槽默认全空」,其前提恰是本票改变的现状)→ 改名「默认 3 槽已种」,断言 3 槽新默认 + closed_unmerged 空;PUT 回放段不变。
- 既有手配规则的用例不受影响(setup 里 replaceRules 全量覆盖)。

否决备选:只在 V30 回填不种新项目(未来项目仍哑,不收口);改用 bootstrap 全局补偿任务替代 migration(运行期魔法,排查难);pr_closed_unmerged 默认回退到 In Progress(票面裁决"不动,人管",且回退违反 applyEvent 只前进语义)。

## 手工测试用例(build/debug 阶段)

1. `gradle :api:bootRun`(default profile,PM 实例)启动 → V30 应用 → `GET /api/projects/YPJ/workflow-rules` 3 槽有值、closed_unmerged 空。
2. dogfood 实证:在 YPJ-36 分支上开 PR(#xxx)→ 等一个轮询周期(≤5min)→ YPJ-36 自动 In Review;PR merged 后 → 自动 Done。
3. e2e 无涉(纯后端);`gradle integrationTest` 本地全绿。

## 验收对照(票)

- [ ] 开 PR 后一个轮询周期内 item 自动 In Review → 设计 3 测试 + 手工 2
- [ ] merged 后自动 Done → 同上
- [ ] 集成测试覆盖状态映射与幂等 → GithubSyncApiTest 既有(V6-S2 已覆盖映射/幂等/final 封口)+ 新增默认路径用例

## As-built

> 2026-10-14 完工。与设计零结构性偏离;一处设计外改动:GithubConfigApiTest 首例「四槽默认全空」前提恰被本票改变,改名「默认 3 槽已种」并更新断言(PUT 回放段不动)。

- **ProjectService.kt**:构造器 + `ProjectWorkflowRuleRepository`;`create` 接住保存状态后调 `seedDefaultRules`(按名字种 branch_created→In Progress / pr_opened→In Review / pr_merged→Done;closed_unmerged 留空=人管;非标 workflow 缺列自然少种)。
- **V30__seed_default_workflow_rules.sql**:3 条 insert-select 按状态名回填存量项目,`on conflict (project_id, event_type) do nothing` 不覆盖手配;无 schema 变更。
- **测试 +3**:ProjectsApiTest(新项目默认 4 槽形状)/ GithubSyncApiTest(不手配规则直测票验收:open→In Review、merged→Done、重复轮询恰 2 条 activity)/ GithubConfigApiTest 首例改断言。
- **验证**:全量 integrationTest **129 passed**(首轮唯一失败即 GithubConfigApiTest 前提变更,预期内);`gradle build --no-daemon` 绿;api.http / entities.puml / domain.puml 无涉(无 REST、无 schema 变更)。
- **dogfood**:本票 PR 即默认规则首个真实用例——PM 实例运行新代码时,轮询 ≤5min 应自动迁 In Review(手动 move 仅作兜底)。
