plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":esir"))
    implementation(libs.kotlinx.coroutines.core)
    // Ktor piše dnevnik preko SLF4J; komandnoj liniji dnevnik ne treba.
    runtimeOnly(libs.slf4j.nop)
}

application {
    mainClass = "rs.omniit.hospes.esir.cli.MainKt"
}

// Komande se pokreću iz korena projekta, da bi putanja config/local.json bila ista kao u README-u.
tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
