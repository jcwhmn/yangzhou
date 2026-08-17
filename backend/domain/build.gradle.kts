plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass = "yangzhou.domain.DemoKt"
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

dependencies {
    testImplementation(kotlin("test"))
}
