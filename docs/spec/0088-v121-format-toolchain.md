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
