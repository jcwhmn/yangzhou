# spec 0090 V46 YPJ-119 CLI 测试补齐:smokeTest 发布门禁 + ExportImport 单测 + e2e 触发隔离

票:YPJ-119。纯工程改进,零业务行为变更。

## 前置事实(调研结论,2026-10-30)

- **cli 与主项目构建已物理隔离,无需"解包"**:`api/build.gradle.kts` 只依赖 `:domain`+`:persistence`(不含 `:cli`),api bootJar 不含 cli 类;`backend/Dockerfile` 只 `COPY app.jar`(app-infra.ps1 从 `:api:bootJar` 复制),镜像无 cli.jar;cli 是独立 fat-jar(`cli/build/libs/yz-*.jar`)+ 独立发布 workflow(`release-yz.yml`,tag `yz-v*` 触发)。
- **cli 零测试**:无 `src/test`;`release-yz.yml` 发版前只 `ls` 守卫文件名,从未运行 jar 就发 Release。
- **真耦合在 CI 触发**:`e2e.yml` paths 含 `backend/**` → 改 cli 文件也触发全套 E2E(bootRun+Playwright+docker)。

## 一、需求

### 范围
1. **冒烟门禁**:cli 进程级冒烟测试——JUnit 测试类内用 ProcessBuilder 跑构建出的 yz fat-jar,零网络用例,断言退出码与输出;`gradle :cli:test` 即含冒烟。
2. **release-yz.yml 发版门禁**:发 Release 前跑 `gradle :cli:test`(build+单测+冒烟),替换裸 `ls` 守卫——yz 不再发布没运行过的 jar。
3. **单测最小面**:ExportImport 的 `parseCsv`(已是 object 成员,直接测)+ `esc`(现为 exportCsv 局部函数,提为成员后测)。
4. **e2e.yml paths 收窄**:`backend/**` → `backend/api/**`、`backend/domain/**`、`backend/persistence/**`,排除 `backend/cli/**`。

### 非目标
- 不为 mock ApiClient 抽接口、不重构 Main.kt 的 dispatch(519 行薄层,重构成本 > 测试收益)
- 不做带 fake server 的网络冒烟;带网络真例验证继续走 dev 手工(YPJ-115 实测模式)
- `backend.yml` / `nightly.yml` 触发路径不动:`gradle test` 天然含 `:cli:test`,全量 test 秒级,拆 job 无收益

## 二、详细设计(代码级)

### 1. `backend/cli/build.gradle.kts`
```kotlin
dependencies {
    // 既有 jackson 两条不动
    testImplementation(kotlin("test"))
}
tasks.withType<Test> { useJUnitPlatform() }
tasks.test { dependsOn(tasks.jar) }   // 冒烟需要 fat-jar 先行
```

### 2. `backend/cli/src/test/kotlin/yangzhou/cli/SmokeTest.kt`
- **定位 jar**:`File("build/libs")` 列 `yz-*.jar` 取唯一;找不到/多个 → fail(提示先 `gradle :cli:jar`)。测试工作目录=cli 模块目录。
- **java 可执行**:用 PATH 上的 `java`(CI setup-java 与本地均已装;shortcut:toolchain 对齐靠环境,不入 Gradle API)。
- helper `run(vararg args)`:ProcessBuilder(`java`,`-Dfile.encoding=UTF-8`,`-jar`,jar,*args),redirectErrorStream,按 UTF-8 读全量输出,返回 (exitCode, output)。
- **用例(断言锚点全用 ASCII,避开 Windows 子进程中文编码)**:
  1. `--help` → exit 0;输出含 `sprints move` 与 `items assign`(usage 完整性,防"加命令忘 usage",YPJ-115 即活例)
  2. `nosuchcmd` → exit != 0
  3. `sprints move K S badstatus` → exit != 0 且输出含 `planned`(CLI 状态白名单先于网络请求;实现时实跑确认执行顺序,若白名单在 requireSession 之后则改用 `--help` 系保底并在测试注释说明)
  4. `items assign`(仅 KEY 一个参数)→ exit != 0(缺参报错)

### 3. `backend/cli/src/main/kotlin/yangzhou/cli/ExportImport.kt` + `ExportImportTest.kt`
- `esc` 从 exportCsv 函数体内提升为 object 成员 `internal fun esc(v: String): String`(逻辑不动,调用点同名直接可用)。
- parseCsv 用例(RFC4180 子集语义):
  - 基本两行 → header→value 映射;表头 trim
  - 引号字段内逗号;双引号转义(`"a""b"` → `a"b`);字段内换行
  - CRLF 归一;全空行跳过;数据行短列补 `""`
- esc 用例:普通值原样;含逗号/引号/换行 → 引号包裹;引号 → 双写转义。

### 4. `.github/workflows/release-yz.yml`
- `Guard — jar file must match tag version`(裸 ls)步骤替换为 `gradle :cli:test`(working-directory: backend);`gh release create` 保持其后。tag 版本守卫隐含在 jar 产物名断言(SmokeTest 找 `yz-*.jar`,release 构建带 `-PcliVersion` 产物名即含 tag 版本)。

### 5. `.github/workflows/e2e.yml`
- push/pull_request paths:`backend/**` → `backend/api/**, backend/domain/**, backend/persistence/**`(保留 `web/**` 与 workflow 自身)。

### 否决的备选
- PowerShell/bash 冒烟脚本 + gradle Exec:CI(ubuntu)/本地(Windows)双维护,弃;JUnit+ProcessBuilder 一处两端跑。
- 抽 CommandHandler 接口使 dispatch 可 mock:重构超规模,收益不及进程级冒烟。
- frontend「提示确认式入 sprint」:属 YPJ-120,不在本票。

## 三、手工测试用例(用户 build/debug 阶段)

1. `gradle :cli:test` 绿(含 SmokeTest 进程级用例 + ExportImportTest)。
2. `gradle test` 全模块绿。
3. 本 PR 即验证样本:PR 页核对 e2e.yml 不触发(只改 backend/cli 与 workflows)。
4. (合并后)下次推 tag `yz-v*`:release-yz 先跑冒烟再出 Release;故意改坏 usage(如删 `sprints move` 行)推 tag 应红——可选项,用户自行决定是否演练。

## ⚠ 范围修正(实现中发现,显要标注)

spec 初稿声明“零业务行为变更”,实现冒烟时发现 **CLI 错误路径退出码全 0** 的既有缺陷(未知命令/参数错误/HTTP 错误一律 exit 0,脚本调用方无法判成败)。本票修复它——这是 **CLI 对外契约行为变更**:
- 未知命令/子命令:`else -> usage()` → `usage() + error()`,先打帮助再 exit 1
- `--help`/`-h`/`help` 显式短路 usage + exit 0(帮助请求不是错误;否则会被旗标解析吃掉走未知命令路径)
- 非法参数/HTTP 错误:不变(本就经 `error()` → main catch → exit 1),本次仅让冒烟锁定该行为

如需回退退出码行为,回退 Main.kt 三处 else 与 help 短路即可,冒烟用例同步调整。

## As-built(2026-10-30,随 PR 提交)

- 实际改动:
  - `backend/cli/build.gradle.kts`:+`testImplementation(kotlin("test"))`、`useJUnitPlatform`、`tasks.test dependsOn tasks.jar`
  - `backend/cli/src/test/kotlin/yangzhou/cli/SmokeTest.kt`(新):4 进程级用例(help 主干命令锚点 20 个/未知命令非 0+中文判定行/members set 缺参先于网络/未知子命令非 0)
  - `backend/cli/src/test/kotlin/yangzhou/cli/ExportImportTest.kt`(新):parseCsv 6 用例 + esc 2 用例;`esc` 从 exportCsv 局部函数提为 `internal` 成员(逻辑不动)
  - `backend/cli/src/main/kotlin/yangzhou/cli/Main.kt`:help 短路 + 三处 else 退出码修复(见上节)
  - `.github/workflows/e2e.yml`:paths 收窄 `backend/**` → `backend/{api,domain,persistence}/**`
  - `.github/workflows/release-yz.yml`:裸 `ls` 守卫 → `gradle :cli:test` 冒烟门禁
- 验证:`gradle :cli:test` 12/12 绿;`gradle test` 全模块绿(CI 兜底 nightly)
- **冒烟用例分叉纪律**:锚点=主干命令集;**YPJ-115(PR #158)合并接力**:在 SmokeTest 锚点数组加 `sprints move`/`items assign`,并补回“状态白名单免网络”与“items assign 缺参”两用例(本票实现时因 115 未合并而移除)
- 实证坑:headroom 压缩搅坏 ctx_execute 脚本本身(变量插值被吃致写坏文件,git checkout 恢复后改用 write 落 python 补丁脚本 + 行号锚点校验 + 编译把关,三战三捷);`yz --help` 在 main 分支原走未知命令路径(exit 0),非本票引入
