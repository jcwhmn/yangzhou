# spec 0125 — YPJ-184 工时建议:提交快照落库(数据自持,离 CLI 离 GitHub 均可得)

> 状态:**草案——待用户过目**。三件套齐备(①需求 ②详细设计 ③手工测试用例)。
> 票面历经四轮拍板(见 YPJ-184 description),本 spec 为定稿整合。

## 一、需求

### 问题

工时建议(commit 时间轴 → 建议段)读 item 关联分支的 GitHub commits,存在两个断供点:

1. **分支删除**:读路径虽有 PR 回溯降级链(YPJ-69,实锤在 `commitsForItem`),但它依赖 GitHub 托管的 `refs/pull/N/head` 特性——自建 Git 无此保障,数据不自持;
2. **单 commit 分支**:≥2 个带时间戳 commit 才成段,单 commit 永不出建议(YPJ-183 实例)。

用户拍板链(2026-11-14 四轮):快照方向 A → 单 commit 用 commit 时间 + Done 时刻成段 → 删分支动作系统化(降级为加分项)→ **终拍:脱离 CLI——Done 联动 + 读时缓存,数据自持**。

### 范围(本票做)

1. **commits 快照表**:item 的分支提交时间轴落库(数据第一手来源,自持);
2. **Done 联动快照(主)**:item 状态进入 FINAL 时,后端自动快照该 item 全部关联分支的 commits,**终止时间锚 = FINAL 时刻**;
3. **读时缓存(兜底)**:`commitsForItem` 每次成功读到 commits(log / 工时建议)顺手 upsert 快照——分支活着期间任何一次成功查看即保底;
4. **单 commit 成段**:有 FINAL 锚快照的 ref,单 commit 也出建议段:`startedAt = commit 时间,endedAt = FINAL 时刻`;
5. **FINAL 钩子基建**:`ItemFinalizeService`——状态迁移进 FINAL 的统一后置动作点(YJP-181 的「自动停表」后续接入同一钩子,实现共享、验收各归各票)。

### 非目标

- 不做删分支的系统化命令(branch-finish)——已降级为流程整洁加分项,工时可得性不依赖它(裸 git 删 / 忘删 / 早删均不影响本票机制);将来要做另立票;
- 不做 UI 变更(工时建议区现有展示直接吃新数据;单 commit 段的 note 文案沿用现有格式);
- 不改 ≥2 commit 段的既有口径(first→last commit 不变);
- 不做 PR 回溯机制的移除(保留为读路径降级链,快照优先于回溯)。

### 验收

- item Done 后**删掉远程分支**(甚至裸 git 删),工时建议仍出段——数据来自快照,不依赖分支 ref 或 GitHub PR 特性;
- 单 commit 分支的 item Done 后,建议出段(commit → Done 时刻);
- 分支活着期间看过一次建议/log,未经 Done 删分支 → ≥2 commit 段仍可出(读时缓存);
- GitHub 不可用/未挂仓库/无 PAT 时,item **照常完成状态迁移**(快照 best-effort,失败仅日志,不阻塞业务);
- 快照幂等:同一 ref 反复 Done(重开)/反复读取不产生重复行。

## 二、详细设计(代码级修改计划)

### 0. 现状实锤(设计依据)

- 读链:`GithubCommitService.commitsForItem` → `gitRefs.findByItemObjectId(kind="branch")` → `fetchBranchCommits`(分支 ref)→ 空(已删)则 `fetchPrCommits`(YPJ-69 降级链,已存在);
- 建议纯函数:`TimeEntryService.buildSuggestions`(≥2 dated commits 才成段);
- 状态迁移:`ItemService.moveStatus`(~:284),目标 `status.statusType`(StatusType enum:START/ACTIVE/FINAL)在钩子点可用;
- 关联实体:`ItemGitRef(kind/repo/ref/state)`;
- 仓内无事件机制先例(@EventListener 零使用)→ 用直接服务调用,不引事件框架。

### 1. 持久层(backend/persistence)

**新表 `item_commit_snapshot`**(Flyway `V59__item_commit_snapshot.sql`):

```sql
create table item_commit_snapshot (
    id             bigserial primary key,
    object_id      uuid not null unique constraint ux_ics_object,
    item_object_id uuid not null,
    repo           text not null,
    ref            text not null,
    via            varchar(16) not null default 'branch',   -- branch | pr(数据来源链)
    commits_json   jsonb not null,                          -- [{sha,message,author,date,url},...]
    commit_count   int  not null,
    captured_at    timestamptz not null,                    -- 快照时刻;FINAL 锚快照 = Done 时刻
    captured_by    varchar(16) not null default 'read',     -- read(读时缓存) | finalize(Done 联动)
    created_at     timestamptz not null default now(),
    updated_at     timestamptz,
    constraint fk_ics_item foreign key (item_object_id) references item (object_id),
    constraint ux_ics_item_repo_ref unique (item_object_id, repo, ref)   -- upsert 幂等
);
```

- 实体 `ItemCommitSnapshot`(不可变 data class,主构造 id/objectId + 审计列,仓内惯例);
- repo:`ItemCommitSnapshotRepository`——`findByItemObjectId`、`findByItemObjectIdAndRepoAndRef`(upsert 判存);**禁止 JDBC client,普通派生查询即可**;
- **新表进 `AbstractApiTest.cleanDatabase` 清单**(子表,排在 item_git_ref 之后)——backend-testing 规矩。

### 2. github 切片(backend/api/business/github)

**`GithubCommitService`** 两处:

- 新增 `snapshotForItem(itemObjectId, capturedBy): SnapshotResult`(公开供 finalize 调用):遍历 item 的 branch gitRefs → 复用现有 fetch 链(fetchBranchCommits→fetchPrCommits)→ commits 非空则 upsert 快照行(via=来源,captured_at=now);**单 ref 失败不中断整轮**(try/catch,记 warn,汇总结果返回);
- `commitsForItem` 读路径加**读时缓存**:每 ref 成功取到非空 commits(分支路或 PR 路皆可)后 upsert 快照(capturedBy="read");失败不影响返回。**注意幂等**:upsert 按 (item,repo,ref),覆盖旧行(updated_at 刷新)。

### 3. item 切片(backend/api/business/item)

**新组件 `ItemFinalizeService`**(business/item 包;YJP-181 后续在此追加停表动作):

```kotlin
@Service
class ItemFinalizeService(private val commitSnapshots: /* github 切片快照入口 */) {
    /** 状态进 FINAL 的统一后置动作;全部 best-effort,不抛出。 */
    fun onFinalized(itemObjectId: UUID) {
        runCatching { commitSnapshots.snapshotForItem(itemObjectId, capturedBy = "finalize") }
            .onFailure { log.warn("FINAL 快照失败(不阻塞状态迁移) item={} cause={}", itemObjectId, it.message) }
    }
}
```

**`ItemService.moveStatus`** 钩子(save 成功、activity/history 落库后):

```kotlin
if (status.statusType == StatusType.FINAL && oldStatus?.statusType != StatusType.FINAL) {
    itemFinalize.onFinalized(current.objectId!!)
}
```

- 只在「首次进 FINAL」触发(重开再完成会再次快照,刷新 captured_at——快照幂等靠 upsert,语义=以最后一次 Done 为准);
- **快照失败绝不影响状态迁移**(best-effort 包裹)。

### 4. time 切片(backend/api/business/time)

**`TimeEntryService.buildSuggestions` 扩展(纯函数,现有签名旁新增)**:

```kotlin
/** 快照回退:live 数据不足(<2 dated)时,查快照——ref 有 FINAL 锚(capturedBy=finalize)且恰 1 个 dated commit → 段=commit→captured_at。 */
fun buildSuggestionsFromSnapshots(
    itemId: UUID,
    snapshots: List<SnapshotRow>,
): List<SuggestedEntry>
```

- 规则:per ref——live 已出段的跳过;快照 commits ≥2 → first→last(同现有口径);**恰 1 dated commit 且 capturedBy=finalize** → 段 = commit 时间 → captured_at;0 dated / 多个 1-commit 无 FINAL 锚 → 不出;
- note 文案:`分支 <ref> · 单提交(commit→完成时刻)`(区分于现有 `· N commits`);
- **`suggest()` 编排**:先 live `commitsForItem`(顺带触发读时缓存)→ 建段;live 不足的 ref → 查快照表补段;合并去重(ref 为键,live 优先)。

### 5. 否决的备选

- **Spring 事件(@EventListener)**:仓内零先例,单钩子单消费者,引事件框架属投机抽象;
- **快照按 commit 一行一行存**:查询方(建议)永远整 ref 时间轴消费,JSONB 一行一 ref 更贴合,行数少;要 commit 级查询的未来需求出现再拆;
- **快照挂删分支命令**(branch-finish):依赖人的流程动作,用户已否(无人能保证);降级为将来加分票;
- **PR 回溯当主机制**:依赖 GitHub 托管特性,自建 Git 无保障——用户已否;降级为读路径既有降级链,快照优先。

### 6. 测试设计

- 单元(纯函数/分支,src/test):
  - `buildSuggestionsFromSnapshots`:单 commit+FINAL 锚成段 / ≥2 快照段 / 无 FINAL 锚不出 / 多 ref 合并;
  - `ItemFinalizeService.onFinalized`:快照抛异常不外泄(mockk);
  - `commitsForItem` 读时缓存:成功读取后 upsert 调用(mockk);
- 集成(src/integrationTest,extends AbstractApiTest):
  - **Done 联动**:建项目挂 repo(mock gateway?)→ item 关联分支 → moveStatus 到 FINAL → 断言快照行落库(capturedBy=finalize)→ 删分支(gateway 层)→ GET suggestions 仍出段;
  - **读时缓存**:GET log/建议成功 → 快照行存在(capturedBy=read);
  - **best-effort**:无 PAT 项目 item 照常 Done(不 500);
  - 幂等:重复 Done(重开)不产生重复行;
- 注:GitHub 交互走 Fake gateway(仓内 mockk/fake 惯例,集成里服务层直测 + 端点黑盒);
- api.http:`github` 区块新增快照相关可观测节?——快照无独立端点(纯内部),经 GET suggestions 验证即可,不新增端点 = 不动 api.http(契约无变化)。

## 三、手工测试用例(用户 build/debug 阶段执行;dev 实例)

前置:dev 项目挂 GitHub 仓库、workspace 配 PAT。

1. **多 commit 分支全链**:item 建分支推 ≥2 commits → 关联 item → Done → `git push origin --delete <branch>` → item 详情工时 tab「生成建议」→ 仍出段(数据自持 ✓,与 YPJ-69 回溯无关——可临时删 workspace PAT 复测更狠)。
2. **单 commit 成段**:分支仅 1 commit → Done → 建议 → 出段,start/end = commit 时刻 → Done 时刻,note 标「单提交」。
3. **读时缓存**:分支活着时点一次建议(出段或不足都行)→ 不经 Done 直接删分支 → 再点建议 → ≥2 段仍出(缓存兜底);单 commit 段不此路径出(无 FINAL 锚,预期不出)。
4. **best-effort**:临时清 workspace PAT → item 走到 Done → 状态迁移成功、无 500(日志有 warn)。
5. **幂等**:Done → 重开回 ACTIVE → 再 Done → 无重复快照行(可经建议区不重复出段佐证)。
6. **既有回归**:≥2 commit 分支的建议段与改造前一致(first→last 不变)。

## 四、As-built(2026-11-14 交付,PR 待开)

### 与 §二设计的偏差(均收窄,无范围膨胀)

| 项 | 设计 | 实落 | 说明 |
|----|------|------|------|
| commits_json 列型 | jsonb | **text 存 JSON 数组** | 消费方永远整 ref 时间轴读取(无 SQL 级 commit 查询);Spring Data JDBC 仓内无 jsonb 实体先例,引入 Converter 属投机基建;将来要 commit 级查询再迁 jsonb(text→jsonb 迁移零损) |
| 快照覆盖规则 | upsert 覆盖旧行 | **FINAL 锚保护** | 集成测试抓实锤(台账 #20):读时缓存无条件覆盖会把 finalize 锚冲掉,单提交段永久消失。实落:existing=finalize 且本次=read → 只刷新提交数据,保留 capturedAt/capturedBy;其余全覆盖(finalize>read、重开再 Done 刷锚) |
| 审计人列 | (未定) | **无 created_by/updated_by** | 快照是系统动作(钩子/读缓存)无 actor 语义;cleanDatabase 两段清单仅第二段(FK delete 序)需挂 |

### 落点清单

- 持久层:`V59__item_commit_snapshot.sql`(枚举列 VARCHAR+CHECK 仓纪律);`ItemCommitSnapshot` 实体(Entities.kt);`ItemCommitSnapshotRepository`(findByItemObjectId / findByItemObjectIdAndRepoAndRef);entities.puml 同步(V59 实体+note;注:ItemType 为 YPJ-177 欠账另行补)
- github 切片:`GithubCommitService` 注入 +4(snapshots/clock/objectMapper/tools.jackson);`fetchRefCommits` 合并双路(分支→PR 降级链原语义不变);`commitsForItem` 成功非空即 upsert(capturedBy=read);新增 `snapshotForItem(itemObjectId, capturedBy)`(best-effort,单 ref 失败 warn 不中断,无挂载/无 PAT 早退);`upsertSnapshot` 含 FINAL 锚保护
- item 切片:新 `ItemFinalizeService.onFinalized`(runCatching 包裹,不阻塞状态迁移);`ItemService` 构造注入 + 首次进 FINAL 触发(`statusType==FINAL && oldStatus!=FINAL`)
- time 切片:`TimeEntryService` 注入 +2(commitSnapshots/objectMapper);`SnapshotRef`(解码视图)+ companion 纯函数 `buildSuggestionsFromSnapshots`(live 已出段跳过;快照 ≥2 dated 同口径;恰 1 dated + finalize → commit→captured_at,note「单提交(commit→完成时刻)」);`suggest()` 编排:live → 段;live 不足 ref 查快照补;readTree 手解 JSON(仓内 Jackson 3 惯例,无 data class 反序列化依赖)
- 测试:单测 +8(TimeEntryServiceUnitTest Snapshots 节 4 / ItemFinalizeServiceUnitTest 2 / GithubCommitServiceUnitTest 2);集成 `FinalizeSnapshotApiTest` 5 例(Done 联动删分支仍出段 / 单 commit 出段+锚不被冲 / 读时缓存不经 Done 删分支仍出 / 无 PAT best-effort / 幂等重开);FakeFinalizeGateway 同款 Fake 惯例
- api.http:不动(无新端点,契约零变化,经 GET suggestions 验证)
- 台账 #19(Jackson 3 包名)/#20(读缓存冲 FINAL 锚设计缺陷)/#21(mockk infix 左结合)

### 验证

- 编译三路绿;全量单测绿;全量 integrationTest 绿;entities.puml 同步
- 自动化全量绿(用户实证:gradle test + integrationTest);手工 §三 6 例**用户拍板豁免**(2026-11-14,数据量起来后再走查;机制等价性已由集成 5 例覆盖:Fake gateway 黑盒链路)

### 遗留(均设计内非目标,勿当欠账)

- branch-finish 系统化(用户已降级为将来加分票)
- YPJ-181 停表接入同一 FINAL 钩子(实现共享、验收各归各票)
