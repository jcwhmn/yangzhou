# spec 0088 V121 YPJ-121 工程卫生:格式化工具链 + 行尾纪律 + 存量统一

票:YPJ-121。设计已获用户批准(2026-10-31),本 spec 为其转录 + 实现细节。

## 一、需求

### 背景
行尾混乱三连实证:详情页 `\r\r\n` 病态行尾致 edit 失效;ItemService 1175 行假 diff;Main.kt 行号翻倍史。根因:无 .gitattributes,各机器 autocrlf 各行其是;Kotlin/TS 无格式化工具链,格式全靠手守。

### 范围
- `.gitattributes`:`* text=auto eol=lf`;`*.ps1` / `*.bat` / `*.cmd` 显式 CRLF(PowerShell/Windows 批处理惯例);`*.jar binary`(gradle wrapper)。
- `.editorconfig`:根级,LF / UTF-8 / 末尾换行;md 不去行尾空白(md 硬换行语法)。
- backend 接 ktlint(gradle 插件,`ktlintCheck` 挂 `check`,`ktlintFormat` 供格式化)。
- web 接 Prettier(`endOfLine: lf` 显式声明,其余默认),`format` / `format:check` 脚本。
- CI 加 format check:backend.yml 跑 `gradle ktlintCheck`;e2e.yml 跑 `prettier --check`。
- 存量一次清:`git add --renormalize .` + `gradle ktlintFormat` + `prettier --write .`,单独基准提交;`.git-blame-ignore-revs` 指向该提交保 blame。
- AGENTS.md 加一行格式化纪律(agent 面向的真相源,防回归)。

### 非目标
- 不引入 ESLint / lint-staged / husky(提交钩子另票,当前 CI check 已够)。
- 不改 gradlew.bat / yz.cmd 内容本身。
- pnpm-lock.yaml / pnpm-workspace.yaml 已 gitignore,不处理。

## 二、详细设计

改 5 个配置/构建文件 + 2 个 workflow + 1 次性基准提交:

1. `.gitattributes`(新):
```
* text=auto eol=lf
*.ps1 text eol=crlf
*.bat text eol=crlf
*.cmd text eol=crlf
*.jar binary
```
2. `.editorconfig`(新):`root = true`;`[*]` charset/end_of_line=lf/insert_final_newline;`[*.md]` trim_trailing_whitespace=false;`[*.{ps1,bat,cmd}]` end_of_line=crlf。ktlint 读同文件,其余 Kotlin 规则走默认 official style。
3. `backend/build.gradle.kts`:root plugins 加 `id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false`;`subprojects { apply(plugin = "org.jlleitschuh.gradle.ktlint") }`。插件自动把 `ktlint*Check` 挂进 `check`,build 红 = 格式不对。版本 14.2.0 为插件门户最新,配 Gradle 9.6.1。
4. `web/package.json`:devDep `prettier: ^3` + scripts `format` / `format:check`;新 `.prettierrc.json` 仅 `{"endOfLine": "lf"}`;新 `.prettierignore`(.next/.next-e2e/test-results/package-lock.json/tsconfig.tsbuildinfo/build-out.txt/playwright-report)。
5. CI:`.github/workflows/backend.yml` 依赖安装后加 `gradle ktlintCheck`;`e2e.yml` 前端依赖后加 `npx prettier --check .`(e2e workflow 本就 web/backend 变更触发,全跑无害)。
6. 基准提交(一次):`git add --renormalize .` + `gradle ktlintFormat` + `web npx prettier --write .` → 单独 commit;随后第二个 commit 加 `.git-blame-ignore-revs`(内容 = 基准 commit hash + 注释;hash 无法自指,只能后置)。本地 `git config blame.ignoreRevsFile .git-blame-ignore-revs` 设 repo 级,GitHub 端该文件自动生效。

否决的备选:
- Spotless(ktlint engine)——多一层配置抽象,jlleitschuh 插件任务名(ktlintFormat/ktlintCheck)与设计措辞直合,单插件更薄。
- ktfmt——格式风格与 IntelliJ 默认差异大,存量 diff 更肥;ktlint official style 贴近现状。
- 提交钩子强制 format——打断本地迭代;CI check + 手动 ktlintFormat 已覆盖。
- renormalize 拆分多个提交——行尾归一必须是单提交才有 blame-ignore 单锚点。

## 三、手工测试用例

1. `gradle build` 绿(含 ktlintCheck,格式不对会红)。
2. web `npx prettier --check .` 绿。
3. `script/app-infra.ps1 status` 仍可跑(ps1 保 CRLF,PowerShell 不炸)。
4. 基准提交后 `git diff main --stat` 无整文件假 diff(ItemService 只剩真实改动行)。
5. 合并后 GitHub blame 该文件,历史归位到基准提交之前的原始作者(blame-ignore 生效)。

## As-built(完工后补)

按设计落地,偏差与实证坑如下。

**落地内容**
- `.gitattributes` / `.editorconfig` / backend ktlint(插件 14.2.0,`ktlintCheck` 挂 `check`)/ web Prettier 3(`.prettierrc.json` + `.prettierignore`)/ CI(backend.yml `gradle test ktlintCheck`;e2e.yml 前端依赖后 `prettier --check`)。
- 基准提交 e1fea05:renormalize + `gradle ktlintFormat` + `prettier --write` 一次过(252 文件,+17307/−7851);3aae099 加 `.git-blame-ignore-revs`;本地已设 `git config blame.ignoreRevsFile`。
- 验证:`gradle build` 绿(单元 + ktlint)、`prettier --check .` 绿、`app-infra.ps1 status test` 正常(ps1 保 CRLF)。

**超出设计的必要改动**
- ktlint filename 规则强制 PascalCase,四个源文件改名:`model.kt`→`Model.kt`、`entities.kt`→`Entities.kt`、`repositories.kt`→`Repositories.kt`(AGENTS 提及处同步)、`exceptions.kt`→`Exceptions.kt`。均为模块内文件名,无契约影响。
- `.editorconfig` 测试路径豁免三条规则(`property-naming` / `function-naming` / `class-naming`,glob `**/src/{test,integrationTest}/**/*.kt`):仓库测试惯例是中文标识符与 snake_case 中文函数名,规则误伤面远大于保护面;main 源集不受影响。
- 5 处超 140 字符长行手工折行(MetricsService 常量、cli 删除确认文案、MetricsApiTest SQL、GithubSync/Notification 测试),未关 max-line-length。
- AccessLogFilterTest 一处 KDoc+行注释顺序违规,合并进 KDoc。

**实证坑(复盘要点)**
- 并行失败掩盖:ktlint 多模块并行跑,先失败的模块挡住后面模块的报错,violations 是分批暴露的(domain → persistence/cli → api),需要跑到全绿才算清完。
- **`git checkout-index -f -a`(plumbing)不认 `eol` 属性**,按 `core.autocrlf` 写出 CRLF;porcelain `git checkout --` 才正确。renormalize 提交后强制 LF 重检出的可靠姿势:`git ls-files -z | xargs -0 rm -f && git checkout -- .`(本次实证,467 LF + 4 CRLF 属性文件 + 38 binary,零 mixed)。
- `git reset --hard` 只重写两 commit 间有差异的文件,不动存量 CRLF 字节——别指望它做 renormalize 收尾。

**后补:紧凑调用风格(2026-10-31 用户偏好)**
- ktlint 官方风格要求"多行调用一参一行",与用户偏好的紧凑 fixture 写法冲突;且 wrapping 家族规则存在**硬依赖链(trailing-comma-* → wrapping),任一条配置级 `disabled` 都会令 ktlint 整体抛 IllegalStateException**(插件包装成误导性的 "KtLint failed to parse file"),故配置级禁用不可行。
- 落地:紧凑调用点用 `@Suppress("ktlint:standard:wrapping", ...)` 注解级豁免(FeasibilityApiTest.seedFixture 首例);新建紧凑 fixture 时照此办理。
- 排查中误伤:Gradle 任务快照在文件改名(改大小写)后可能残留旧路径,ktlintFormat 报已不存在的文件名违规;`--rerun-tasks` 强制重跑即消,属陈旧缓存非真实违规。
- 排查中删除的 `*/bin/` 目录为 IDE 输出残留(已 gitignore,不入库)。

**待合并后验证**
- GitHub PR diff 只见真实改动;blame 经 ignore-revs 归位(手工用例 5)。
