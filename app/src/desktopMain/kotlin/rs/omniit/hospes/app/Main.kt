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
    val koren = pronadjiKoren() ?: run {
        System.err.println("Ne nalazim config/local.json. Pokreni aplikaciju iz foldera projekta hospes-app.")
        exitProcess(1)
    }
    val podesavanja = Podesavanja.izJsona(File(koren, "config/local.json").readText())
    val okruzenje = DesktopOkruzenje(koren, podesavanja)

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Hospes ESIR ${Esir.VERSION}",
            state = rememberWindowState(width = 1360.dp, height = 900.dp),
        ) {
            App(okruzenje)
        }
    }
    // HTTP biblioteka drži svoje niti još ~60 s; posle zatvaranja prozora program se odmah završava.
    exitProcess(0)
}

/** Koren projekta: prvi folder (od trenutnog naviše) u kome postoji `config/local.json`. */
private fun pronadjiKoren(): File? =
    generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .take(6)
        .firstOrNull { File(it, "config/local.json").exists() }

private class DesktopOkruzenje(private val koren: File, private val podesavanja: Podesavanja) : Okruzenje {
    private val folderZurnal = FolderZurnal(File(koren, "racuni"))

    override val pin: String? = podesavanja.lpfr?.pin
    override val zurnal = folderZurnal

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

    override fun otvoriPfr(vrsta: VrstaPfra): OtvorenPfr = otvoriPfr(podesavanja, vrsta, koren)

    override fun tekstRacuna(brojRacuna: String): String? = folderZurnal.tekstRacuna(brojRacuna)

    /** Dok nema pravog štampača: slike i ESC/POS bajtovi u `stampa/` (nije u Git-u). */
    private val stampac = VirtuelniStampac(File(koren, "stampa"))

    override fun pregledOtiska(brojRacuna: String, papir: SirinaPapira): ByteArray? = folderZurnal.otisak(brojRacuna, papir)?.png()

    override fun stampaj(brojRacuna: String, papir: SirinaPapira): String {
        val otisak = folderZurnal.otisak(brojRacuna, papir) ?: throw IllegalStateException("Račun $brojRacuna nema tekst u žurnalu")
        return stampac.stampaj(brojRacuna, otisak)
    }

    override fun pre30Dana(): String = LocalDate.now().minusDays(30).toString()
}

actual fun ByteArray.uSliku(): ImageBitmap? =
    runCatching { org.jetbrains.skia.Image.makeFromEncoded(this).toComposeImageBitmap() }.getOrNull()
