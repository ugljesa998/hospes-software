package rs.omniit.hospes.app

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import rs.omniit.hospes.esir.Esir
import rs.omniit.hospes.esir.FajlKatalog
import rs.omniit.hospes.esir.KatalogCsvFajl
import rs.omniit.hospes.esir.FolderZurnal
import rs.omniit.hospes.esir.OtvorenPfr
import rs.omniit.hospes.esir.Podesavanja
import rs.omniit.hospes.esir.PodesavanjaFajl
import rs.omniit.hospes.esir.SirinaPapira
import rs.omniit.hospes.esir.VirtuelniStampac
import rs.omniit.hospes.esir.otisak
import rs.omniit.hospes.esir.VrstaPfra
import rs.omniit.hospes.esir.otvoriPfr
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalDate
import kotlin.system.exitProcess

fun main() {
    val okruzenje = DesktopOkruzenje(korenPodataka())

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "${Esir.PROIZVOD} ${Esir.VERSION}",
            state = rememberWindowState(width = 1360.dp, height = 900.dp),
        ) {
            App(okruzenje)
        }
    }
    // HTTP biblioteka drži svoje niti još ~60 s; posle zatvaranja prozora program se odmah završava.
    exitProcess(0)
}

/**
 * Folder sa podacima kase (`config/local.json`, `racuni/`, `podaci/`, `stampa/`):
 * - `HOSPES_KOREN`, ako je zadat;
 * - koren projekta kada se pokreće iz razvoja (`./gradlew :app:run`): isti fajlovi kao komandna linija;
 * - inače (instalirana aplikacija) sistemski folder za podatke aplikacija:
 *   Windows `%APPDATA%\Hospes ESIR`, macOS `~/Library/Application Support/Hospes ESIR`.
 */
private fun korenPodataka(): File {
    System.getenv("HOSPES_KOREN")?.let { return File(it).absoluteFile.apply { mkdirs() } }
    generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .take(6)
        .firstOrNull { File(it, "settings.gradle.kts").exists() && File(it, "esir").isDirectory }
        ?.let { return it }
    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    val folder = when {
        "mac" in os -> File(home, "Library/Application Support/Hospes ESIR")
        "win" in os -> File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "Hospes ESIR")
        else -> File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "hospes-esir")
    }
    return folder.apply { mkdirs() }
}

private class DesktopOkruzenje(private val koren: File) : Okruzenje {
    private val folderZurnal = FolderZurnal(File(koren, "racuni"))
    private val podesavanjaFajl = PodesavanjaFajl(File(koren, "config/local.json"))

    override val folderPodataka: String = koren.path
    override val zurnal = folderZurnal

    override fun ucitajPodesavanja(): Podesavanja = podesavanjaFajl.ucitaj()

    override fun sacuvajPodesavanja(podesavanja: Podesavanja) = podesavanjaFajl.sacuvaj(podesavanja)

    /** Izabran sertifikat se kopira u `config/` (kod podešavanja; `*.p12` i `*.pfx` nisu u Git-u). */
    override fun izaberiSertifikat(): String? {
        val dijalog = FileDialog(null as Frame?, "Sertifikat za V-PFR (.p12 / .pfx)", FileDialog.LOAD)
        dijalog.isVisible = true
        val ime = dijalog.file ?: return null
        val izvor = File(dijalog.directory, ime)
        val cilj = File(File(koren, "config").apply { mkdirs() }, ime)
        if (izvor.canonicalFile != cilj.canonicalFile) izvor.copyTo(cilj, overwrite = true)
        return "config/$ime"
    }

    override fun opisSertifikata(putanja: String, lozinka: String): String =
        rs.omniit.hospes.esir.opisSertifikata(File(putanja).let { if (it.isAbsolute) it else File(koren, putanja) }, lozinka)

    /** Isti katalog kao u komandnoj liniji: `podaci/katalog.json` (nije u Git-u). */
    override val katalog = FajlKatalog(File(koren, "podaci/katalog.json"))

    override fun procitajCsv(): String? {
        val dijalog = FileDialog(null as Frame?, "Uvoz kataloga (CSV)", FileDialog.LOAD)
        dijalog.isVisible = true
        val ime = dijalog.file ?: return null
        return KatalogCsvFajl.procitaj(File(dijalog.directory, ime))
    }

    override fun sacuvajCsv(predlogImena: String, tekst: String): String? {
        val dijalog = FileDialog(null as Frame?, "Izvoz kataloga (CSV)", FileDialog.SAVE)
        dijalog.file = predlogImena
        dijalog.isVisible = true
        val ime = dijalog.file ?: return null
        val fajl = File(dijalog.directory, if (ime.endsWith(".csv", ignoreCase = true)) ime else "$ime.csv")
        KatalogCsvFajl.upisi(fajl, tekst)
        return fajl.path
    }

    override fun otvoriPfr(vrsta: VrstaPfra): OtvorenPfr = otvoriPfr(podesavanjaFajl.ucitaj(), vrsta, koren)

    override fun tekstRacuna(brojRacuna: String): String? = folderZurnal.tekstRacuna(brojRacuna)

    /** Dok nema pravog štampača: slike i ESC/POS bajtovi u `stampa/` (nije u Git-u). */
    private val stampac = VirtuelniStampac(File(koren, "stampa"))

    override fun pregledOtiska(brojRacuna: String, papir: SirinaPapira): ByteArray? = folderZurnal.otisak(brojRacuna, papir)?.png()

    override fun stampaj(brojRacuna: String, papir: SirinaPapira): String {
        val otisak = folderZurnal.otisak(brojRacuna, papir) ?: throw IllegalStateException("Račun $brojRacuna nema tekst u žurnalu")
        return stampac.stampaj(brojRacuna, otisak)
    }

    override fun danas(): String = LocalDate.now().toString()

    override fun pre30Dana(): String = LocalDate.now().minusDays(30).toString()
}

actual fun ByteArray.uSliku(): ImageBitmap? =
    runCatching { org.jetbrains.skia.Image.makeFromEncoded(this).toComposeImageBitmap() }.getOrNull()
