package rs.omniit.hospes.esir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PodesavanjaTest {
    private val ispravno = """
        {
          "lpfr": { "baseUrl": "http://devesdc.sandbox.suf.purs.gov.rs:8888/tajni-token/api", "pin": "1234" },
          "vpfr": { "baseUrl": "https://vsdc.sandbox.suf.purs.gov.rs", "pfxPassword": "tajna", "pac": "TAJNIPAC" }
        }
    """

    @Test
    fun citaIspravnaPodesavanja() {
        val p = Podesavanja.izJsona(ispravno)
        assertEquals("1234", p.lpfr?.pin)
        assertEquals(emptyList(), p.lpfr!!.proveri())
    }

    @Test
    fun prepoznajeNepopunjenPrimer() {
        val primer = LpfrPodesavanja(baseUrl = "http://devesdc.sandbox.suf.purs.gov.rs:8888/<TOKEN_SA_PORTALA>/api", pin = "<PIN>")
        val problemi = primer.proveri()
        assertTrue(problemi.any { "primer" in it })
        assertTrue(problemi.any { "4 cifre" in it })
    }

    @Test
    fun trazeAdresuKojaSeZavrsavaSaApi() {
        val bezApi = LpfrPodesavanja(baseUrl = "http://192.168.1.10:8888", pin = "1234")
        assertTrue(bezApi.proveri().any { "/api" in it })
    }

    @Test
    fun toStringNeOdajeTajne() {
        val p = Podesavanja.izJsona(ispravno)
        val tekst = p.toString()
        assertFalse("tajni-token" in tekst)
        assertFalse("1234" in tekst)
        assertFalse("tajna" in tekst)
        assertFalse("TAJNIPAC" in tekst)
        assertTrue("devesdc.sandbox.suf.purs.gov.rs:8888" in tekst)
    }

    @Test
    fun kasiriPapirIIzborPfraSeCuvajuIUcitavaju() {
        val p = Podesavanja.izJsona(ispravno).copy(kasiri = listOf("Marko", "Ana"), papir = SirinaPapira.MM58, pfr = VrstaPfra.VPFR)
        val json = p.uJson()
        assertEquals(p, Podesavanja.izJsona(json))
        assertTrue("\"papir\": \"MM58\"" in json && "\"pfr\": \"VPFR\"" in json, json)
        assertTrue("null" !in json, "prazna polja se ne upisuju")
    }

    @Test
    fun stariFajlBezNovihPolja() {
        val p = Podesavanja.izJsona(ispravno)
        assertEquals(emptyList(), p.kasiri)
        assertEquals(null, p.papir)
        assertEquals(VrstaPfra.LPFR, p.izabraniPfr)
        assertEquals(VrstaPfra.VPFR, Podesavanja(vpfr = VpfrPodesavanja(pfxPath = "x.p12")).izabraniPfr, "samo V-PFR je podešen")
        assertEquals(VrstaPfra.LPFR, Podesavanja().izabraniPfr)
    }

    @Test
    fun imeKasira() {
        assertEquals("Unesi ime kasira", Kasiri.proveri("  "))
        assertEquals("Ime kasira može da ima najviše 50 znakova", Kasiri.proveri("A".repeat(51)))
        assertEquals(null, Kasiri.proveri("Marko Marković"))
    }
}
