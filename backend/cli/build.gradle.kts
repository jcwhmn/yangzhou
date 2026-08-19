plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(25)
}

val jackson3 = "3.2.2"

dependencies {
    implementation("tools.jackson.core:jackson-databind:3.0.2")
    implementation("tools.jackson.module:jackson-module-kotlin:$jackson3")
}

application {
    mainClass = "yangzhou.cli.MainKt"
}

// fat-jar:JDK 内置合并,不引 Shadow 插件(ponytail:若出现 META-INF 服务合并冲突再升级)
tasks.jar {
    manifest {
        attributes["Main-Class"] = "yangzhou.cli.MainKt"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    archiveBaseName = "yz"
}
