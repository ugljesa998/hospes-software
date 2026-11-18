package rs.omniit.hospes.esir

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FajlKatalogTest {
    private val folder: File = Files.createTempDirectory("katalog").toFile()

    @AfterTest
    fun obrisi() {
        folder.deleteRecursively()
    }

    @Test
    fun cuvaIPonovoUcitava() {
        val fajl = File(folder, "podaci/katalog.json")
        val k = FajlKatalog(fajl)
        k.sacuvaj(Artikal("101", "Espresso", Decimal4.parse("180"), "Ђ"))
        k.promeniCenu("101", Decimal4.parse("200"))
        assertTrue(fajl.exists())

        val ponovo = FajlKatalog(fajl)
        assertEquals("200", ponovo.nadji("101")!!.cena.toString())
        ponovo.ukloni("101")
        assertEquals(emptyList(), FajlKatalog(fajl).svi())
    }

    @Test
    fun csvIzExcelaUWindows1250() {
        val tekst = "sifra;naziv;cena;oznaka\n104;Čaj đumbir;180,00;Ђ\n"
        val fajl = File(folder, "excel.csv").apply { writeBytes(tekst.replace("Ђ", "A").toByteArray(charset("windows-1250"))) }
        val uvoz = KatalogCsv.uvoz(KatalogCsvFajl.procitaj(fajl))
        assertEquals(emptyList(), uvoz.greske)
        assertEquals("Čaj đumbir", uvoz.artikli.single().naziv)
    }

    @Test
    fun izvozSaBomomSeVracaIsto() {
        val artikli = listOf(Artikal("101", "Ćevapi", Decimal4.parse("950"), "Ђ", gtin = "5449000000996"))
        val fajl = File(folder, "izvoz/katalog.csv")
        KatalogCsvFajl.upisi(fajl, KatalogCsv.izvoz(artikli))
        assertTrue(fajl.readBytes().take(3) == listOf(0xEF, 0xBB, 0xBF).map { it.toByte() }, "UTF-8 BOM za Excel")
        assertEquals(artikli, KatalogCsv.uvoz(KatalogCsvFajl.procitaj(fajl)).artikli)
    }
}
