package rs.omniit.hospes.app

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import rs.omniit.hospes.esir.ElektronskiZurnal
import rs.omniit.hospes.esir.EsirHttp
import rs.omniit.hospes.esir.FolderZurnal
import rs.omniit.hospes.esir.IzdatRacun
import rs.omniit.hospes.esir.KatalogCsv
import rs.omniit.hospes.esir.KatalogUMemoriji
import rs.omniit.hospes.esir.LpfrPodesavanja
import rs.omniit.hospes.esir.LpfrVeza
import rs.omniit.hospes.esir.OtvorenPfr
import rs.omniit.hospes.esir.Podesavanja
import rs.omniit.hospes.esir.SirinaPapira
import rs.omniit.hospes.esir.VpfrPodesavanja
import rs.omniit.hospes.esir.VrstaPfra
import rs.omniit.hospes.esir.otisak
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Generator slika ekrana za uputstva (docs/). Radi samo na zahtev:
 * `./gradlew :app:desktopTest --tests "*SlikeZaUputstvo*" -Phospes.slike=../docs/slike`
 *
 * Podešavanja su izmišljena (nijedna prava adresa, lozinka ni PAC), PFR je lažan, a žurnal se samo čita iz
 * foldera `racuni/` (računi izdati na razvojnom okruženju Poreske uprave), da bi računi na slikama bili pravi.
 */
@OptIn(ExperimentalTestApi::class)
class SlikeZaUputstvoTest {
    private val izlaz = System.getProperty("hospes.slike")?.takeIf { it.isNotBlank() }?.let { File(it) }

    private val zurnalFolder = FolderZurnal(File("../racuni"))
    private val samoCitanje = object : ElektronskiZurnal {
        override fun sacuvaj(izdat: IzdatRacun, dodatak: List<String>) = error("samo čitanje")
        override fun nadji(brojRacuna: String) = zurnalFolder.nadji(brojRacuna)
        override fun svi() = zurnalFolder.svi()
    }

    /** Stope kakve vraća razvojni L-PFR Poreske uprave (probne oznake). */
    private fun status(traziPin: Boolean) = """{"isPinRequired":$traziPin,"gsc":["${if (traziPin) "1500" else "0100"}"],"uid":"7EHXTU6T",
        "supportedLanguages":["sr-Cyrl-RS"],"currentTaxRates":{"taxCategories":[{"name":"VAT","taxRates":[
        {"rate":10,"label":"A"},{"rate":19,"label":"Ж"},{"rate":0,"label":"B"},{"rate":11,"label":"F"}]}]}}"""

    private fun okruzenje(traziPin: Boolean) = object : Okruzenje {
        private var sacuvano = Podesavanja(
            lpfr = LpfrPodesavanja("http://192.168.1.20:7555/api"),
            vpfr = VpfrPodesavanja(pfxPath = "config/sertifikat.p12", pfxPassword = "primer-lozinke", pac = "PRIMER"),
            kasiri = listOf("Marko Petrović", "Ana Jovanović"),
            tekstIspodRacuna = "Hvala na poseti!",
            serijskiBroj = "HP-7KQ2-M9XD",
            papir = SirinaPapira.MM80,
        )
        override val folderPodataka = "C:\\Users\\Kasa\\AppData\\Roaming\\Hospes ESIR"
        override fun ucitajPodesavanja() = sacuvano
        override fun sacuvajPodesavanja(podesavanja: Podesavanja) {
            sacuvano = podesavanja
        }
        override fun izaberiSertifikat(): String? = null
        override fun opisSertifikata(putanja: String, lozinka: String) = "UID ACN84XSJ · PIB 115423827 · V-PFR vsdc.sandbox.suf.purs.gov.rs:443"
        override val zurnal = samoCitanje
        override val katalog = KatalogUMemoriji(KatalogCsv.uvoz(File("../config/katalog-primer.csv").readText()).artikli)
        override fun procitajCsv(): String? = null
        override fun sacuvajCsv(predlogImena: String, tekst: String): String? = null
        override fun pregledOtiska(brojRacuna: String, papir: SirinaPapira) = zurnalFolder.otisak(brojRacuna, papir)?.png()
        override fun stampaj(brojRacuna: String, papir: SirinaPapira) = "ne štampa"
        override fun otvoriPfr(vrsta: VrstaPfra): OtvorenPfr {
            val http = EsirHttp.napravi(
                MockEngine { z ->
                    when {
                        z.url.encodedPath.endsWith("/attention") -> respond("", HttpStatusCode.OK)
                        z.url.encodedPath.endsWith("/status") ->
                            respond(status(traziPin), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                },
            )
            return OtvorenPfr(LpfrVeza("http://192.168.1.20:7555/api", http), http, "L-PFR: 192.168.1.20:7555")
        }
        override fun tekstRacuna(brojRacuna: String) = zurnalFolder.tekstRacuna(brojRacuna)
        override fun danas() = java.time.LocalDate.now().toString()
        override fun pre30Dana() = java.time.LocalDate.now().minusDays(30).toString()
    }

    /** Jedan ekran: pokrene aplikaciju, sačeka PFR, izvrši [radnje] i sačuva sliku `ime.png`. */
    private fun scena(ime: String, traziPin: Boolean = false, radnje: DesktopComposeUiTest.() -> Unit = {}) {
        val dir = izlaz ?: return
        runDesktopComposeUiTest(width = 1360, height = 900) {
            setContent { App(okruzenje(traziPin)) }
            waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Dostupan", substring = true).fetchSemanticsNodes().isNotEmpty() }
            klik("Marko Petrović") // kasir na početku smene
            radnje()
            waitForIdle()
            dir.mkdirs()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$ime.png"))
        }
    }

    /** Klik na prvi element sa tekstom; ako je van vidljivog dela, prvo se do njega pomera (kao korisnik). */
    private fun DesktopComposeUiTest.klik(tekst: String, deo: Boolean = false) {
        val cvor = onAllNodesWithText(tekst, substring = deo)[0]
        runCatching { cvor.performScrollTo() }
        cvor.performClick()
        waitForIdle()
    }

    private fun DesktopComposeUiTest.upisi(polje: String, tekst: String) {
        val cvor = onNodeWithText(polje)
        runCatching { cvor.performScrollTo() }
        cvor.performTextInput(tekst)
        waitForIdle()
    }

    /** Otvara račun iz žurnala (lista se po potrebi pomera do njega). */
    private fun DesktopComposeUiTest.otvoriIzZurnala(broj: String) {
        klik("Žurnal")
        onNode(hasScrollToIndexAction()).performScrollToNode(hasText(broj))
        klik(broj)
    }

    private fun DesktopComposeUiTest.stavke() {
        klik("Espresso")
        klik("Espresso")
        klik("Kapućino")
        klik("Sendvič šunka-sir")
    }

    @Test
    fun slike() {
        if (izlaz == null) return
        scena("01-glavni-ekran") {
            stavke()
            otvoriIzZurnala("7EHXTU6T-7EHXTU6T-81")
        }
        scena("02-podeljeno-placanje") {
            stavke()
            klik("+ Podeli plaćanje", deo = true)
            upisi("Iznos", "500")
            upisi("Kupac dao", "1000")
        }
        scena("03-kupac") {
            stavke()
            klik("Kupac (na firmu", deo = true)
            upisi("Broj (npr. PIB firme)", "123456789")
            upisi("Broj (opciono polje kupca)", "1234567")
            onNodeWithText("Broj (opciono polje kupca)").performScrollTo()
        }
        scena("04-pin", traziPin = true)
        scena("05-zurnal") {
            klik("Žurnal")
            upisi("Pretraga", "Espresso")
            klik("Traži")
        }
        scena("06-refundacija") {
            otvoriIzZurnala("7EHXTU6T-7EHXTU6T-81")
            klik("Refundacija")
        }
        scena("07-predracun-naplata") {
            otvoriIzZurnala("7EHXTU6T-7EHXTU6T-83")
            klik("Naplati")
        }
        scena("08-avans") {
            klik("Avans")
            upisi("Iznos avansa", "2000")
            klik("Prenos na račun")
        }
        scena("09-avansni-posao") { otvoriIzZurnala("7EHXTU6T-7EHXTU6T-85") }
        scena("10-konacni-racun") {
            otvoriIzZurnala("7EHXTU6T-7EHXTU6T-85")
            klik("Konačni račun")
            repeat(5) { klik("Pivo točeno 0,5") }
        }
        scena("11-korporativna-kartica") {
            stavke()
            klik("Korporativna kartica")
            upisi("PIB firme", "123456789")
            upisi("Broj kartice", "6666666")
        }
        scena("12-bez-naknade") {
            stavke()
            klik("Bez naknade")
        }
        scena("13-vaucer") {
            klik("Vaučer")
            upisi("Iznos vaučera", "2000")
        }
        scena("14-katalog") { klik("Katalog") }
        scena("15-podesavanja") { klik("Podešavanja") }
        scena("16-podesavanja-pfr") {
            klik("Podešavanja")
            onNodeWithText("Proveri sertifikat").performScrollTo()
        }
        scena("17-podesavanja-placanje-tekst") {
            klik("Podešavanja")
            onNodeWithText("Sačuvaj tekst").performScrollTo()
        }
        scena("18-papir-58") {
            otvoriIzZurnala("7EHXTU6T-7EHXTU6T-81")
            klik("Papir 58 mm")
        }
        scena("19-ponistavanje") {
            otvoriIzZurnala("7EHXTU6T-7EHXTU6T-81")
            klik("Poništi (greška)")
        }
        scena("20-mesecna-refundacija") {
            klik("Žurnal")
            klik("Korporativne kartice: mesečna refundacija", deo = true)
            upisi("PIB firme", "115423827")
        }
    }
}
