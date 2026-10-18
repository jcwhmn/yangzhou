# spec 0054 — V70 后端单测并行(maxParallelForks)

> 票:YPJ-70。分支:`chore/YPJ-70-unit-test-parallel`。动机:`gradle clean build` 实测单线程太慢,单测占大头且无共享态。

## 1. 需求

`tasks.test`(纯单元,CI 同款)开 Gradle 多 fork 并行,缩短本地与 CI 单测时间。

**范围**:`backend/api/build.gradle.kts` 的 `tasks.test` 块。
**非目标**:

- `integrationTest` **保持顺序**——所有集成类共享一个 Testcontainers 容器 + `cleanDatabase()` 清表,并行互相冲表(skill 双轨表的顺序性约定),不加任何开关
- JUnit 平台内并行(intra-JVM)不做——maxParallelForks 已够,少一层配置面

## 2. 详细设计

`tasks.test { }` 增:

```kotlin
// YPJ-70:单测类级并行;fork 数 = 半数核,下限 2 上限 4(单测本就快,更多 fork 只费启动)
maxParallelForks = maxOf(2, minOf(4, Runtime.getRuntime().availableProcessors() / 2))
```

安全前提(skill 双轨表):单测无共享可变全局态、不碰 DB/网络。仓内单测(FakeXxxGateway 纯注入、domain 纯函数、RealXxx parse 单测)均满足。

否决备选:`forkEvery`(重建 JVM 无收益)、JUnit parallel(配置面大、与 fork 重复)。

## 3. 手工测试用例

1. `gradle test` 绿,观察耗时较改动前明显缩短(clean 后跑两次对比)
2. `gradle integrationTest --tests "yangzhou.api.github.*"` 绿(顺序未动)
3. `gradle build` 绿

## 4. As-built

(完工后补)
