plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":esir"))
}

application {
    mainClass = "rs.omniit.hospes.esir.cli.MainKt"
}
