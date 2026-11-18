import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(17)

    // Desktop: Windows (instalacija .msi) i macOS (razvoj). Android dolazi u sledećoj verziji.
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
            // Samo za generator slika ekrana za uputstva (SlikeZaUputstvoTest): klik po tekstu i snimak ekrana.
            implementation(libs.compose.ui.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// Slike ekrana za uputstva prave se samo na zahtev:
// ./gradlew :app:desktopTest --tests "*SlikeZaUputstvo*" -Phospes.slike=../docs/slike
tasks.withType<Test>().configureEach {
    systemProperty("hospes.slike", providers.gradleProperty("hospes.slike").getOrElse(""))
}

compose.desktop {
    application {
        mainClass = "rs.omniit.hospes.app.MainKt"
        // Windows sa Javom 17 inače čita i piše tekst u windows-1250; aplikacija svuda radi u UTF-8.
        jvmArgs += "-Dfile.encoding=UTF-8"
        // Naziv u traci menija na macOS-u (opcija postoji samo na macOS-u).
        if (System.getProperty("os.name").startsWith("Mac")) jvmArgs += "-Xdock:name=Hospes POS"

        // Instalacija za Windows (.msi) sa ugrađenom Javom; pravi je GitHub Actions (.github/workflows/windows.yml).
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Hospes POS"
            // Ista verzija kao Esir.VERSION i kao u prijavi 1795 kod Poreske uprave.
            packageVersion = "0.0.1"
            // Samo ASCII: WiX (pravi .msi) podrazumevano radi u kodnoj strani 1252, u kojoj nema č, ć, đ, š, ž.
            description = "Hospes POS - elektronski sistem za izdavanje racuna (ESIR)"
            vendor = "OmniIT"
            copyright = "Copyright 2026 OmniIT"
            // Predlog alata (suggestRuntimeModules) i moduli koje alat ne vidi: šifrovanje za HTTPS ka V-PFR-u,
            // kodne strane za CSV iz Excela (windows-1250) i java.naming za HTTP biblioteku.
            modules("java.instrument", "java.management", "jdk.unsupported", "jdk.crypto.ec", "jdk.charsets", "java.naming")
            windows {
                menuGroup = "Hospes"
                menu = true
                shortcut = true
                dirChooser = true
                // Instalira se za korisnika, bez administratorskih prava; podaci su u %APPDATA%\Hospes ESIR.
                perUserInstall = true
                // Isti broj u svim verzijama: nova verzija zamenjuje staru umesto da se instalira pored nje.
                upgradeUuid = "787859b6-c2b3-4cc5-8fff-9c78f9ce88dc"
            }
            // jpackage na macOS-u ne prima verziju koja počinje nulom; macOS paket služi samo za lokalnu proveru.
            macOS {
                packageVersion = "1.0.0"
            }
        }
    }
}
