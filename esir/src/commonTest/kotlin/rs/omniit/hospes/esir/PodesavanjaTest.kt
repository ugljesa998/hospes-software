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
}
