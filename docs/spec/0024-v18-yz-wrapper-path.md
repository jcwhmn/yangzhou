# 0024 — YPJ-27:yz wrapper dev 路径修复 + CLI items branch

> 2026-10-12 立项。状态:**已过目(2026-10-12):④ 并入本票;分支命名另立 YPJ-37**。As-built 编码完成后补。
> 关联:spec 0016(发行三层结构)。

## 需求

**背景**:`backend/script/yz`(bash)与 `backend/script/yz.cmd`(Windows)的 dev 布局查找把 repo 根算错:脚本实际在 `<repo>/backend/script/`,`dirname`/`cd ..` 只退一层到 `backend/`,再拼 `backend/cli/build/libs` 得到 `backend/backend/cli/...`——jar 明明在也报「首次运行:构建 CLI」,自动构建的 `cd backend` 同样落错目录。当前绕过:`java -jar backend/cli/build/libs/yz-0.1.0.jar`。

**范围**
1. 修两个 wrapper 的 repo 根计算,dev 布局 jar 查找与自动构建目录均正确。
2. CLI 新增 `items branch <KEY-N> <分支名> [--base <基线>]`(默认 main)——经 `POST /api/items/{id}/branches` 建分支并落 item_git_refs 关联(`yz log` 依赖)。当前 CLI 无此命令,2026-10-12 起的新开工流程每次都要手搓 curl。
3. `GithubBranchService.createBranch` 对 GithubApiException 只特判 422/404,401(如 PAT 失效)裸抛 500;同族 commits 端点映射为可操作 400。本 session 实际踩中:建分支请求因 PAT 失效吃 500,排障多绕一圈。

**非目标**
- wrapper 不重构,保持 0016 三层结构(脚本同目录 → repo 布局 → 自动构建)。
- 不做 `items branch` 之外的 CLI 面扩充(分支列表/删除等)。
- PAT 失效的配置管理 UX(web 表单等)不在本票。

## 详细设计

**① `backend/script/yz`(bash,1 行)**
- `ROOT="$(dirname "$DIR")"`(= `backend/`)改为 `ROOT="$(cd "$DIR/../.." && pwd)"`(= repo 根)。
- 三层结构不动:发行层 `$DIR/yz-*.jar` 与 ROOT 无关(用户放任意目录,先命中,YPJ-16 不回归);dev 层与自动构建层的 `$ROOT/backend/...` 随修正自然正确。
- 否决备选:多候选路径轮询——治标,根因是 ROOT 算错。

**② `backend/script/yz.cmd`(Windows,1 处)**
- `cd /d "%~dp0.."` 改 `cd /d "%~dp0..\.."`;dev 层查找与 `pushd backend` 依赖 CWD,随之修正。发行层 `%~dp0yz-*.jar` 不动。

**③ CLI `items branch`(`backend/cli/src/main/kotlin/yangzhou/cli/Main.kt`)**
- dispatch `when ("$noun $verb")` 加 `"items branch" ->`(紧邻 `items move`):
  1. `resolveItemId(api, rest[0])`
  2. 项目 key = item `number` 前缀(`substringBefore("-")`),`GET /api/projects/{key}/repos` 取第一个挂载;无挂载则 error(提示先挂 repo)
  3. `--base` 可选(默认 `main`),分支名 = rest[1] 必填
  4. `POST /api/items/{id}/branches`,body `{repoId, baseBranch, name}`,打印 `分支已建:branch(repo) url`
- ApiClient 已有泛型 `json(method, path, body)`,零新依赖。帮助文本补一行。

**④ `GithubBranchService` 兜底映射(约 3 行 + 1 测试;2026-10-12 用户拍板并入)**
- catch GithubApiException 补通用分支,对齐 `GithubCommitService.kt:56`:`BadRequestException("GitHub API 错误(${e.status}):先检查 PAT/仓库名")`,422/404 特判保留在前。
- 测试:`GithubBranchApiTest` 补一例(gateway mock/桩 抛 401 → 断言 400 而非 500)。

## 手工测试用例(build/debug 阶段执行)

前置:jar 已在 `backend/cli/build/libs/`。
1. **dev 直用(bash)**:`backend/script/yz attrs list` → 直接输出词表,无「首次运行:构建」。
2. **dev 直用(cmd)**:`backend\script\yz.cmd caps list` → 同上,中文不乱码。
3. **自动构建**:jar 临时改名 → `backend/script/yz items list YPJ` → 打印「首次运行:构建」且产物落 `backend/cli/build/libs/`,跑通;恢复 jar 名。
4. **发行布局不回归**:空目录放 `yz`(或 `yz.cmd`)+ jar → `./yz caps list` → 脚本同目录层命中。
5. **items branch**(需 PAT 有效):`yz items branch YPJ-27 cwjiang/YPJ-27-scratch --base main` → 201 提示,GitHub 远端见分支,`yz log YPJ-27` 可拉;测完删远端分支。
6. **负例**:项目无挂载仓库 → `items branch` 明确报错提示挂载;分支名非法(空格/`..`)→ API 400 透出。

> 附带:web 分支自动命名对 CJK 标题产出残迹 → 已立 YPJ-37(backlog),不在本票。

## 验收对照(票)

- [ ] dev 布局下 `backend/script/yz` 直接可用 → 用例 1/2
- [ ] 打包/发行布局不回归(YPJ-16 release 路径)→ 用例 4

## As-built

> 2026-10-12 完工(PR #95)。与设计差异:无结构性偏离;spec 状态节在 PR 后经 main 订正(编码期编辑漏入 PR)。

**实际落地**

- `backend/script/yz`:`ROOT="$(dirname "$DIR")"` → `ROOT="$(cd "$DIR/../.." && pwd)"`(1 行);三层查找结构不动。
- `backend/script/yz.cmd`:`cd /d "%~dp0.."` → `cd /d "%~dp0..\.."`(1 处)。
- CLI `items branch <KEY-N> <分支名> [--base <基线>]`:dispatch 一例 + help 一行;repoId 取项目第一个挂载,无挂载报错提示;`ApiClient.json` 泛型直调,零新依赖。
- `GithubBranchService.createBranch`:catch 尾改 `throw BadRequestException("GitHub API 错误(${e.status}):先检查 PAT/仓库名")`(对齐 `GithubCommitService:56` 先例);422→409 / 404→400 特判保留。
- `GithubBranchApiTest`:既有负例用例补 401→400 一例。
- `api.http`:§12 补 branches 请求;repos POST 补 handler 回填 `repoId`(消未解析变量)。

**验证**

- wrapper 直用(dev 布局)`backend/script/yz attrs list` 无「首次构建」✓(agent 自测 + 用户复测 yz 命令 ✓)
- `gradle :cli:jar :api:compileTestKotlin` 全绿;新 jar `items branch` dispatch 冒烟通
- 发行布局用例 4 未单独跑(发行层路径不触 ROOT,零改动面);集成测试走 nightly/合并前本地双轨

**遗留**

- web 分支自动命名 CJK 残迹 → YPJ-37(backlog)
- 旧 ref 行(`YPJ-27-yz-wrapper-dev-…`,改名残留)在 PM 库留一节空历史;SQL 清理待用户点头
