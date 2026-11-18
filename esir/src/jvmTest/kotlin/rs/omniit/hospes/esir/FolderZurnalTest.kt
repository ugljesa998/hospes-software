package rs.omniit.hospes.esir

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FolderZurnalTest {
    private val folder: File = Files.createTempDirectory("zurnal").toFile()

    @AfterTest
    fun obrisi() {
        folder.deleteRecursively()
    }

    private val izdat = IzdatRacun(
        requestId = "abc",
        zahtev = ZahtevZaRacun(
            invoiceType = VrstaRacuna.PROMET,
            transactionType = TipTransakcije.PRODAJA,
            payment = listOf(Placanje(Decimal4.parse("300"), NacinPlacanja.GOTOVINA)),
            items = listOf(Stavka("Kafa", Decimal4.parse("1"), Decimal4.parse("300"), Decimal4.parse("300"), listOf("A"))),
        ),
        racun = FiskalizovanRacun(
            invoiceNumber = "AAA-BBB-7",
            sdcDateTime = "2026-11-18T12:00:00+01:00",
            journal = "=== РАЧУН ===",
            verificationQRCode = "R0lGODlhAQABAAAAACw=",
        ),
    )

    @Test
    fun cuvaZahtevOdgovorTekstIQr() {
        val zurnal = FolderZurnal(folder)
        zurnal.sacuvaj(izdat, dodatak = listOf("Повраћај: 25,00"))

        assertEquals(izdat, zurnal.nadji("AAA-BBB-7"))
        assertEquals(listOf(izdat), zurnal.svi())
        val tekst = zurnal.tekstRacuna("AAA-BBB-7")!!
        assertTrue(tekst.startsWith("=== РАЧУН ==="))
        assertTrue("Повраћај: 25,00" in tekst && FolderZurnal.NASLOV_DODATKA in tekst)
        assertTrue(File(folder, "AAA-BBB-7.gif").readBytes().copyOfRange(0, 3).decodeToString() == "GIF")
    }

    @Test
    fun preskaceFajloveKojiNisuIzdatiRacuni() {
        File(folder, "stari.json").writeText("""{"invoiceNumber":"X-Y-1"}""")
        File(folder, "pokvaren.json").writeText("nije json")
        val zurnal = FolderZurnal(folder)
        zurnal.sacuvaj(izdat)
        assertEquals(listOf("AAA-BBB-7"), zurnal.svi().map { it.racun.invoiceNumber })
        assertNull(zurnal.nadji("X-Y-1"))
    }

    @Test
    fun prazanIliNepostojeciFolder() {
        assertEquals(emptyList(), FolderZurnal(File(folder, "nema")).svi())
    }
}
