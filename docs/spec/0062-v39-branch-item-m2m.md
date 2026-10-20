# 0062 — YPJ-39 branch×item 多对多:共享分支补挂、PR 扇出、yz log 按票可拉

> 状态:**草案(等用户过目后编码)**
> 票:YPJ-39(V23 功能件)· 影响:persistence(V37 迁移)· api(github 切片)· cli(`yz items branch`)

## 1. 需求

### 背景

item↔Git 关联现行 **1:1**:`item_git_ref` 的 `uk_item_git_ref_repo_kind_ref (repo, kind, ref)` 全局唯一,一分支只能挂一票。多票同回合共用分支时(PR #100 实证:YPJ-34 主挂,22/23 从票),从票无关联记录——`yz log` 断链、PR 不可见、收口靠人工。

### 范围(票面需求要点)

1. **branch×item 多对多**:一分支可挂多票
   - 新建即多票:`yz items branch YPJ-A <分支> --items YPJ-B,YPJ-C`;
   - 既有分支补挂:新 REST 端点(修 PR #100 历史场景);
2. **PR 关联可查**:分支挂载的所有票均可见该分支的 PR(状态/链接)——sync 扇出;
3. **`yz log <KEY-N>`** 按票拉到共享分支提交(commits 查询本就按票,行落齐即通,零改动);
4. **既有 1:1 数据兼容**(旧关联不丢,零数据改写)。

### 非目标

- yz CLI 补挂命令(历史修复走 api.http 一次性;CLI attach 另立摩擦票);
- GitHub 双向同步(YPJ-51 占坑);
- branch 升一等实体(规范化双表,见 §2 否决);
- `VcsGateway` 接口变更;
- 补挂从未入册的分支(端点只收「库内已有行」的分支)。

## 2. 详细设计(代码级修改计划)

### V37 迁移(persistence)

`V37__item_git_ref_m2m.sql`:

- `drop constraint uk_item_git_ref_repo_kind_ref`;
- `add constraint uk_item_git_ref_item_repo_kind_ref unique (item_id, repo, kind, ref)`;
- 既有行天然满足新 uk(全局唯一 ⊆ 每票唯一),**零数据改写**;
- `ItemGitRef` 实体注释同步(轮询幂等锚点语义改述);
- `docs/architecture/entities.puml` 同步(改 schema 必更图)。

### api github 切片

- `repositories.kt` `ItemGitRefRepository`:`findByRepoAndKindAndRef` 改返回 `List`(扇出与幂等判断用);
- `GithubBranchService` 新 `attach(itemId, repo, ref)`:
  - 查同 `(repo, kind="branch", ref)` 既有行(任意票)→ 无则 **404**(分支未入册);
  - 目标票已挂同分支 → **409**;
  - 复制行(url/state 照抄),不调 gateway;
  - ****createdAt 继承锚点行**:行内显式值会被 @CreatedDate 审计覆盖,save 后经 repository `@Modifying` `updateCreatedAt` 校正(commits 的 since 过滤以入册时刻为准,补挂不得丢历史);
  - wire 型 `AttachGitRefRequest(repo, ref)` 同文件;
- `GithubBranchController` 加端点:`POST /items/{itemId}/git-refs`(资源段在方法级,照 house 规则);响应复用既有 ref DTO 形状;
- `GithubSyncService`:
  - **PR 扇出**:poll 经 `findPrByHead(分支.ref)` 命中 PR → 为持有该分支行的**每一票**各写一行 `kind=pr`;
  - 既有 pr 行状态更新改为按 `(repo, KIND_PR, ref)` 全部行 `copy(state=...)`;
  - `github_status_changed` activity 每票各记一条(票级留痕语义不变);
  - 分支 poll 幂等判断改 List 语义(行存在即跳过,判定不变)。

### cli(Main.kt)

- `items branch` 增加 `--items YPJ-B,YPJ-C`:主票走既有 create,从票逐个走新 attach;任一失败即报错并打出已成功清单(**部分成功可见,不回滚**);
- `yz log` 不动。

### api.http

- 新端点按序入对应区块:`POST /items/{itemId}/git-refs`;
- 负例区:未入册 404 / 重复挂 409 / `kind=pr` 行 409。

### 测试(照 backend-testing skill;唯一 seam = REST 黑盒,extends AbstractApiTest)

- `GithubBranchApiTest` 加:
  - 两票共享:create(主)+ attach(从)→ 两票 refs 均含该分支;
  - 负例:未入册 404、重复 409、对 `kind=pr` 行 409;
- `GithubSyncApiTest` 加(fake gateway 既有 seam):
  - PR open→merged:两票各落 pr 行且 state 同为 merged;activity 各一条;
  - 单票既有路径回归不破;
- `GithubCommitsApiTest` 加:
  - 共享分支:两票 commits 端点均可拉(等价 yz log)。

### 否决的备选

- **规范化双表**(`git_ref` 主表 + join)→ 迁移面与双表写放大,验收不增值;branch 需一等实体时再议;
- **createBranch 请求加 `alsoItems` 批量字段** → API 保持单票语义,部分失败编排留给 CLI,更简;
- **放开 uk + 代码去重** → 唯一锚点即轮询幂等来源,不可去。

## 3. 手工测试用例(用户执行)

| # | 步骤 | 期望 |
|---|---|---|
| TC1 | 新建共享:`yz items branch YPJ-A <分支> --items YPJ-B` | 两票详情均见分支;sync 后均见 PR;两票 `yz log` 均拉到提交 |
| TC2 | 历史补挂(模拟 PR#100):主票已有分支,api.http 调 `POST /items/{从票}/git-refs` | 从票详情见分支;sync 后 PR 行落;log 通 |
| TC3 | 负例三条(未入册/重复/pr 行) | 404 / 409 / 409,错误形状统一 |
| TC4 | 回归:单票建分支、commits 照旧 | 全绿 |
