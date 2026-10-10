# 0016 — V17-3 CLI 独立发行:独立版本号 + release workflow + 死代码清理

> 票:YPJ-16。CLI 已是纯 REST client(jackson + JDK HttpClient,零内部模块依赖),按独立制品发行;模块留在 monorepo,不拆仓库。

## 1. 需求(详细)

### 1.1 背景

- 后端 monorepo 版本统一为 `0.1.0-SNAPSHOT`(根 `build.gradle.kts` 的 `allprojects`),CLI fat-jar 被动跟随 SNAPSHOT,无发行形态。
- `sync-linear`(Linear 联邦,V3)方向已反转:yangzhou 为唯一真相源,Linear 冻结只读(2026-10-06)。`LinearSync.kt` 已是死代码,帮助文本标着「(退役)」仍在发行物里。
- CLI 的目标用户(不开浏览器管项目的开发者)目前只能克隆仓库自己构建。

### 1.2 目标

1. **版本独立**:CLI 版本号从 `0.1.0` 起独立演进,不再跟 monorepo `SNAPSHOT`;发行时版本由 tag(`yz-v*`)决定,本地构建有合理默认值。
2. **release workflow**:推送 tag `yz-vX.Y.Z` → CI 构建 fat-jar,连同 `yz`(bash)/`yz.cmd`(Windows)启动脚本一起发到 GitHub Releases,可下载即用。
3. **死代码清理**:删除 `LinearSync.kt` 及其在 CLI 入口的全部痕迹(帮助文本、dispatch 分支、README 章节)。
4. **README 更新**:补「安装(Releases 下载)」方式,保持开发构建说明。
5. **本地可验证**:发行形态(jar + 两个脚本放同一目录)可独立运行,连 8081 走通核心命令。

### 1.3 非目标

- 不拆仓库(票备注明示)。
- 不做 CHANGELOG、自动版本 bump 机制——tag 即版本,人工演进。
- 不发 Maven Central / Homebrew 等渠道。
- `import` 命令(ExportImport 内的 Linear CSV 灌入)**保留**——票只点名 `LinearSync` 死代码。
- spec 0003(Linear 联邦)等历史 spec 不改,它们是历史存档。

### 1.4 验收(票内清单)

- [ ] CLI 版本号独立于后端(从 0.1.0 起独立演进,不再跟 monorepo SNAPSHOT)
- [ ] release workflow:tag `yz-v*` 推送 → 构建 fat-jar + yz/yz.cmd 启动脚本 → GitHub Releases
- [ ] 删除 LinearSync.kt 死代码(Linear 已退役只读)
- [ ] cli README 更新:安装方式(Releases 下载)
- [ ] 本地验证发行 jar 可独立运行(连 8081 核心命令走通)

## 2. 详细设计(代码级修改计划)

### 2.1 版本机制

`backend/cli/build.gradle.kts` 加一行,project 级覆盖根 `allprojects` 的 `0.1.0-SNAPSHOT`:

```kotlin
// CLI 独立发行(YPJ-16):默认 0.1.0;release workflow 用 tag 经 -PcliVersion 传入
version = (findProperty("cliVersion") as String?) ?: "0.1.0"
```

- 本地/普通 CI 构建:`yz-0.1.0.jar`(无 SNAPSHOT)。
- 发行:workflow 解析 tag `yz-v0.1.0` → `gradle :cli:jar -PcliVersion=0.1.0` → tag 与 jar 版本**构造上必然一致**。
- `yz.cmd` / `yz` 用 `yz-*.jar` 通配,版本演进不破脚本。

### 2.2 启动脚本(jar 查找分层)

现有 `backend/script/yz.cmd` 是 dev wrapper(先查 `backend/cli/build/libs`,缺则自动构建)。改为三层:①脚本同目录 `yz-*.jar`(发行形态)→ ②repo 布局(现状)→ ③自动构建(dev 兜底)。新增 `backend/script/yz`(bash)同逻辑——发行资产需覆盖 POSIX 侧。

### 2.3 release workflow

新增 `.github/workflows/release-yz.yml`,复用 backend.yml 的环境约定(checkout@v4 / setup-java@v5 temurin 25 / setup-gradle@v4):

- 触发:`on: push: tags: ['yz-v*']`;`permissions: contents: write`。
- 构建:`gradle :cli:jar -PcliVersion=${GITHUB_REF_NAME#yz-v}`(working-directory backend;`:cli:jar` 零内部依赖,不会牵动其它模块)。
- 一行守卫:`ls backend/cli/build/libs/yz-$v.jar`,文件不在即挂(防 tag 异形字符)。
- 发布:跑 `gh release create "$GITHUB_REF_NAME" --generate-notes`,资产 = jar + `script/yz` + `script/yz.cmd`(`GH_TOKEN: ${{ github.token }}`;不引第三方 action)。

### 2.4 死代码清理

- 删 `backend/cli/src/main/kotlin/yangzhou/cli/LinearSync.kt`(106 行,唯一引用方是 Main.kt)。
- `Main.kt`:删帮助文本行(`sync-linear <KEY> <linear.csv> (退役)…`)与 dispatch 分支(`if (noun == "sync-linear") { … }`)。
- `backend/cli/README.md`:删「Linear 联邦(V3)— 已退役」整节。
- `backend/cli/bin/` 下同名残留是 gitignored 构建产物,不碰。

### 2.5 README 更新

`backend/cli/README.md`:顶部加「安装(Releases)」节——从 GitHub Releases 下 `yz-<v>.jar` + `yz` + `yz.cmd` 置于同一目录,`./yz login --server <URL> -u me`;保留「构建」节(dev,提 `gradle :cli:jar` 与 wrapper)。

### 2.6 修改文件清单

| 文件 | 动作 |
|---|---|
| `backend/cli/build.gradle.kts` | + version 行 |
| `backend/cli/src/main/kotlin/yangzhou/cli/LinearSync.kt` | 删 |
| `backend/cli/src/main/kotlin/yangzhou/cli/Main.kt` | 删 2 处(帮助行 + dispatch) |
| `backend/script/yz.cmd` | jar 查找分层 |
| `backend/script/yz` | 新增(bash) |
| `backend/cli/README.md` | 安装节 + 删 Linear 节 |
| `.github/workflows/release-yz.yml` | 新增 |
| `docs/spec/0014` / `0015` | 补 As-built 欠账(并入本 PR,handoff 约定) |

### 2.7 不动清单

- 根 `build.gradle.kts`(`allprojects` 版本保持 0.1.0-SNAPSHOT,后端各模块不受影响)
- `ExportImport.kt`、`ApiClient.kt`、`import` 命令
- spec 0003 等 Linear 历史 spec、`docs/planning/*`
- AGENTS.md(`yz.cmd` wrapper 的描述在新分层下仍成立)

### 2.8 测试策略与风险

- CLI 模块无既有单测;本票改动为打包脚本、版本语义、删代码,**无可单测面**——验证靠手工用例(TC1–TC4)+ workflow 实跑。变更是删除与打包配置,无新业务分支。
- 风险①:tag 指向不含 workflow 的旧 main → workflow 不触发。对策:tag 在 PR 合并后打。
- 风险②:`gh release create` 重跑(同 tag 重复触发)会 422 已存在。对策:人为操作,tag 只打一次;重挂删 tag 重来。

## 3. 手工测试用例(用户执行)

前置:8081 后端在线(default profile);`chcp 65001` 或 Git Bash 下执行避免中文乱码。

- **TC1 发行形态独立运行**:`cd backend && gradle :cli:jar` → 确认产物 `cli/build/libs/yz-0.1.0.jar`(不带 SNAPSHOT)。建临时目录,拷入 jar + `script/yz` + `script/yz.cmd`;`yz.cmd login --server http://localhost:8081 -u me` → 登录成功;`yz.cmd items list YPJ` → 表格输出。bash 侧同验 `./yz`。
- **TC2 sync-linear 消失**:`yz.cmd`(无参帮助)无 sync-linear 行;`yz.cmd sync-linear YPJ x.csv` → 未知命令错误。
- **TC3 版本传参**:`gradle :cli:jar -PcliVersion=9.9.9` → `yz-9.9.9.jar`(验证 tag 传参机制;验完删临时 jar)。
- **TC4 release workflow**(PR 合并后由 agent 执行):打 tag `yz-v0.1.0` 推送 → Actions「release-yz」绿 → Releases 页见 yz-0.1.0.jar + yz + yz.cmd 三个资产。

## As-built

(完工后补)
