plugins {
    kotlin("jvm") version "2.3.0" apply false
    kotlin("plugin.spring") version "2.3.0" apply false
    id("org.springframework.boot") version "4.0.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "yangzhou"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        // Spring Security 7 用 JSpecify 注解,Kotlin 2.3 默认 strict 会把 encode() 判成 String?;
        // 恢复 warn 语义(与 chess/Kotlin 2.1 实证行为一致)。
        compilerOptions.freeCompilerArgs.add("-Xjspecify-annotations=warn")
    }
}
