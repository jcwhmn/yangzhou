plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output + sourceSets["test"].output
        runtimeClasspath += sourceSets.main.get().output + sourceSets["test"].output
    }
}

// YPJ-101(spec 0079):构建期把 git 最近 tag 注入 version.txt,无 git/shallow clone 落 dev
val resolveVersion =
    providers
        .exec {
            // 只认 v* 日期 tag(yz-v* 是 CLI 发版 tag,不算应用版本);无则 dev
            commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "v*").isIgnoreExitValue = true
        }.standardOutput.asText
        .map { it.trim().ifEmpty { "dev" } }
        .orElse("dev")

// configuration cache 要求不可捕获脚本引用:配置期固化成 String(每构建一次,成本可忽略)
val appVersion = resolveVersion.get()

tasks.processResources {
    val v = appVersion // 局部化,避免 filesMatching lambda 捕获脚本实例(configuration cache 禁止)
    filesMatching("version.txt") {
        expand("version" to v)
    }
}
val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
val integrationTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get(), configurations.runtimeOnly.get())
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":persistence"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("tools.jackson.module:jackson-module-kotlin:3.2.2")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-flyway")
    runtimeOnly("org.postgresql:postgresql")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-scalar:2.8.13")
    implementation("org.apache.poi:poi-ooxml:5.3.0")
    implementation("software.amazon.awssdk:s3:2.57.0") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
        exclude(group = "org.apache.httpcomponents.client5", module = "httpclient5")
    }
    implementation("software.amazon.awssdk:url-connection-client:2.57.0")

    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(kotlin("test"))
    testImplementation("org.testcontainers:postgresql:1.21.3")
    testImplementation("io.mockk:mockk:1.13.13") // YPJ-43:service 单测(mockk 计划 P0)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.test {
    // YPJ-70: 单测类级并行(无 DB/共享态,安全);fork 数 = 半数核夹 2..4,更多只费 JVM 启动
    maxParallelForks = maxOf(2, minOf(4, Runtime.getRuntime().availableProcessors() / 2))
    // YPJ-100: src/test 已物理二分为纯单元,不再需要 tag 过滤(spec 0078)
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Integration tests (Spring Boot + Testcontainers, needs Docker; spec 0014/0078)"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
    // 集成串行:共享 Testcontainers 容器 + 清表节奏,不并行
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
