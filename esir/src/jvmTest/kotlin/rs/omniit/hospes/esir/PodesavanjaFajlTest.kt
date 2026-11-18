package rs.omniit.hospes.esir

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PodesavanjaFajlTest {
    private val folder: File = Files.createTempDirectory("podesavanja").toFile()

    @AfterTest
    fun obrisi() {
        folder.deleteRecursively()
    }

    @Test
    fun prvoPokretanjeBezFajlaPaCuvanje() {
        val fajl = PodesavanjaFajl(File(folder, "config/local.json"))
        assertEquals(Podesavanja(), fajl.ucitaj())

        val p = Podesavanja(lpfr = LpfrPodesavanja("http://192.168.1.10:7555/api"), kasiri = listOf("Marko"), papir = SirinaPapira.MM80)
        fajl.sacuvaj(p)
        assertEquals(p, fajl.ucitaj())
        fajl.sacuvaj(p.copy(kasiri = listOf("Marko", "Ana")))
        assertEquals(listOf("Marko", "Ana"), PodesavanjaFajl(File(folder, "config/local.json")).ucitaj().kasiri)
        assertFalse(File(folder, "config/local.json.tmp").exists())
    }

    @Test
    fun sertifikatKojiNePostojiIliNijeDobar() {
        assertTrue(assertFailsWith<IllegalArgumentException> { opisSertifikata(File(folder, "nema.p12"), "x") }.message!!.startsWith("Nema fajla"))
        val los = File(folder, "los.p12").apply { writeText("nije sertifikat") }
        assertEquals(
            "Sertifikat ne može da se otvori: lozinka nije dobra ili fajl nije .p12/.pfx",
            assertFailsWith<IllegalArgumentException> { opisSertifikata(los, "x") }.message,
        )
    }
}
