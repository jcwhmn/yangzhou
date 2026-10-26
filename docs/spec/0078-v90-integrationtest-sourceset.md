# spec 0078 — V25 测试结构重构:integrationTest 物理分离(YPJ-100)

> 状态:**draft 待用户过目**。来源:2026-10-25 用户拍板"坚持把所有 integrationTest 放在 src/integrationTest 目录下,不要和 unit test 混在一起"(chess 项目 test skill 同款结构)。

## 1. 需求

### 现状与问题

- 集成与单元测试**同居** `backend/api/src/test/kotlin/`,仅靠 `@Tag("integration")` + 任务级 tag 过滤区分(`test` excludeTags / `integrationTest` includeTags)。
- `tasks.check` `dependsOn(integrationTest)` → `gradle build` 必跑集成测试(要 Docker),build 慢。
- `backend/gradle.properties` 为空:无 build cache、无并行、无 configuration cache。
- 用户裁定:物理分离是原则,当初混居是简化,现在纠正。

### 范围

- `backend/api`:测试目录二分——`src/test`(纯单元)与 `src/integrationTest`(集成,chess 同款)。
- Gradle 构建脚本改sourceSet 布线;`check` 与集成解耦;gradle.properties 提速参数。
- 文档同步:`AGENTS.md`(测试节/DoD)、`.agents/skills/backend-testing/SKILL.md`(双轨描述改物理二分)。

### 非目标

- 不改任何测试断言/场景本身;不引入 chess 的 Fixtures/TestPlayers 模式(现有 helpers 结构保留)。
- 不动 CI workflow 文件(`backend.yml` 跑 `gradle test`,分离后天然正确;nightly 跑全量不变)。
- 不动 web/e2e。

## 2. 详细设计

### 2.1 目录迁移

**移入 `src/integrationTest/kotlin/yangzhou/api/`**(61 文件):
- `AbstractApiTest.kt`(基类,去掉 `@Tag("integration")`)
- 全部 53 个 `extends AbstractApiTest` 的测试类(含 `OpenApiContractTest` 若其继承)
- 集成专用支撑类(随基类走:cleanDatabase 表清单、helpers 中仅集成使用的部分)

**留在 `src/test/kotlin/`**(纯单元,~35 文件):全部 `*UnitTest` / mockk 单测(`GithubAdapterPrParseTest`、`WebhookSignatureTest`、`ItemDueSignalsUnitTest` 等)。

- 包名不变(仅目录移动);集成侧 `AbstractApiTest` 的 `cleanDatabase` 等支撑代码随类走。

### 2.2 build.gradle.kts(chess 同款布线)

```kotlin
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}
val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
val integrationTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get(), configurations.runtimeOnly.get())
}

val integrationTest by tasks.registering(Test::class) {
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
}

tasks.test {
    useJUnitPlatform()   // 去掉 excludeTags —— src/test 已全是单元
}
// 删除: tasks.check { dependsOn(integrationTest) }
```

- 删除 `@Tag` 机制(目录即边界);`AbstractApiTest` 与 `ViewsApiTest` 上的 `@Tag` 注解移除。
- 编译期边界自动成立:src/test 再引用 Testcontainers/AbstractApiTest 会直接编译失败。

### 2.3 gradle.properties(backend/)

```properties
org.gradle.caching=true
org.gradle.parallel=true
org.gradle.configuration-cache=true   # 视 Kotlin/Spring 插件兼容性:翻车则撤此行,不阻塞本票
org.gradle.jvmargs=-Xmx2g
```

### 2.4 文档同步

- `AGENTS.md` 测试节:目录描述改物理二分;DoD 从"`gradle build` 绿"改为"`gradle build` + `gradle integrationTest` 绿(集成需 Docker,merge 前本人本地跑,CI 单元+nightly 兜底)"。
- `.agents/skills/backend-testing/SKILL.md`:双轨表改 chess 同款(位置/CI/并行三行);cleanDatabase 清单说明指向新位置;跑法命令不变。

### 2.5 否决的备选

- **保留 @Tag 只拆任务**(再快一点):被否——用户明确要物理分离,目录即边界才能在编译期阻止混居回潮。
- **E2E 一起动**:否——web/e2e 是另一条轨,本票不碰(外科手术)。

## 3. 手工测试用例(用户 build/debug 阶段执行)

1. `gradle :api:test --no-daemon` → 全绿,且**不触 Docker**(观察无 Testcontainers 拉起)
2. `gradle :api:integrationTest --no-daemon` → 53 类全绿(需 Docker postgres)
3. `gradle build --no-daemon` → 绿,且**不跑集成**(速度对比:记录分离前后 build 耗时)
4. 故意在 src/test 里 import AbstractApiTest → 编译失败(边界生效抽查)
5. 二次 `gradle build` 走 build cache,明显加速
6. `gradle integrationTest --no-daemon` 跑 configuration cache 开启状态;若报兼容性错误 → 撤该行重跑,记录结论进 As-built

## 4. As-built(2026-10-26,PR 待审)

- 迁移:54 文件(53 个 extends AbstractApiTest 子类 + AbstractApiTest 本体)→ `src/integrationTest/kotlin/`,包名不变;36 个单测留 `src/test`。@Tag 机制全删(目录即边界)。
- **偏离 spec 一处**:集成 sourceSet `compileClasspath += main.output + test.output`——`FakeObjectStorage/FakeStorageConfig` 原定义在被迁走的 AttachmentApiTest 里但单测 AttachmentServiceTest 也用,抽到单测侧 `attachment/FakeObjectStorage.kt` 共用(比复制一份干净)。
- gradle.properties:caching+parallel+**configuration-cache 全开**(验证可用:Reusing configuration cache)。
- 验证实测:单元 1m17s 全绿(不触 Docker);integrationTest 5m52s 全绿(53 类);`gradle build` 8s~29s **不再跑集成**;边界抽查:单测 import AbstractApiTest → 编译期 Unresolved reference。
- CI 影响面检查:backend.yml 跑 `gradle test` 不变,天然正确;nightly 跑全量 build+integrationTest 不变;e2e.yml bootRun 不受影响。
