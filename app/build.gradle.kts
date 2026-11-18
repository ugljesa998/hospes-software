plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)

    // Desktop (macOS sada, Windows kasnije). Android cilj dodajemo kada krenemo na SUNMI L2s PRO.
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(project(":esir"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
        getByName("desktopMain").dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            runtimeOnly(libs.slf4j.nop)
        }
        getByName("desktopTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

compose.desktop {
    application {
        mainClass = "rs.omniit.hospes.app.MainKt"
        // Naziv u traci menija na macOS-u (opcija postoji samo na macOS-u).
        if (System.getProperty("os.name").startsWith("Mac")) jvmArgs += "-Xdock:name=Hospes ESIR"
    }
}
