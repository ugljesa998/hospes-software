plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)

    // Desktop (Windows, macOS) i komandna linija.
    // Android cilj dodajemo kada krenemo sa aplikacijom na SUNMI uređaju.
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            api(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // Samo u testovima: čita odštampan QR kod, da proverimo da je posle smanjivanja i dalje ispravan.
            implementation(libs.zxing.core)
        }
    }
}

// Crtanje otiska (AWT) u testovima, bez prozora i bez ikonice u Dock-u.
tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
}
