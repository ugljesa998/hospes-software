package rs.omniit.hospes.esir

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LpfrPinTest {
    private val jsonZaglavlje = headersOf(HttpHeaders.ContentType, "application/json")

    /** Lažni L-PFR: odgovara na attention, status i pin i beleži sve zahteve. */
    private class LazniLpfr(
        val dostupan: Boolean = true,
        val status: String = """{"isPinRequired": true, "gsc": ["1500", "0210"]}""",
        val odgovorNaPin: String = "\"0100\"",
    ) {
        val zahtevi = mutableListOf<HttpRequestData>()
        val pinZahtevi get() = zahtevi.filter { it.url.encodedPath.endsWith("/v3/pin") }
    }

    private fun veza(lazni: LazniLpfr): LpfrVeza {
        val engine = MockEngine { zahtev ->
            lazni.zahtevi += zahtev
            val putanja = zahtev.url.encodedPath
            when {
                putanja.endsWith("/v3/attention") ->
                    respond("", if (lazni.dostupan) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable)
                putanja.endsWith("/v3/status") -> respond(lazni.status, HttpStatusCode.OK, jsonZaglavlje)
                putanja.endsWith("/v3/pin") -> respond(lazni.odgovorNaPin, HttpStatusCode.OK, jsonZaglavlje)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        return LpfrVeza("http://lpfr.local:8888/api", EsirHttp.napravi(engine))
    }

    @Test
    fun saljePinKaoTekstSaJsonZaglavljem() = runTest {
        val lazni = LazniLpfr()
        val sifra = veza(lazni).posaljiPin("1234")

        assertEquals("0100", sifra.kod)
        val z = lazni.pinZahtevi.single()
        assertEquals(HttpMethod.Post, z.method)
        assertEquals("/api/v3/pin", z.url.encodedPath)
        assertEquals("1234", (z.body as TextContent).text)
        assertTrue(z.body.contentType!!.match(ContentType.Application.Json))
        assertEquals("application/json", z.headers[HttpHeaders.Accept])
    }

    @Test
    fun razumeOdgovorIBezNavodnika() = runTest {
        assertEquals("2100", veza(LazniLpfr(odgovorNaPin = "2100")).posaljiPin("1234").kod)
    }

    @Test
    fun neispravanFormatPinaSeNeSalje() = runTest {
        val lazni = LazniLpfr()
        assertFailsWith<IllegalArgumentException> { veza(lazni).unesiPin("12a4") }
        assertFailsWith<IllegalArgumentException> { veza(lazni).posaljiPin("12345") }
        assertTrue(lazni.zahtevi.isEmpty())
    }

    @Test
    fun ispravanPinSeSaljeJednom() = runTest {
        val lazni = LazniLpfr()
        assertEquals(RezultatPina.Prihvacen, veza(lazni).unesiPin("1234"))
        assertEquals(1, lazni.pinZahtevi.size)
    }

    @Test
    fun pogresanPinSeNePonavlja() = runTest {
        val lazni = LazniLpfr(odgovorNaPin = "\"2100\"")
        val rezultat = veza(lazni).unesiPin("9999")

        val odbijen = assertIs<RezultatPina.Odbijen>(rezultat)
        assertEquals("2100", odbijen.sifra.kod)
        assertEquals(1, lazni.pinZahtevi.size)
    }

    @Test
    fun bezKarticePinSeNeSalje() = runTest {
        val lazni = LazniLpfr(status = """{"isPinRequired": true, "gsc": ["1500", "1300"]}""")
        val rezultat = veza(lazni).unesiPin("1234")

        val nijePoslat = assertIs<RezultatPina.NijePoslat>(rezultat)
        assertEquals("1300", nijePoslat.sifra?.kod)
        assertTrue(lazni.pinZahtevi.isEmpty())
    }

    @Test
    fun zakljucanaKarticaPinSeNeSalje() = runTest {
        val lazni = LazniLpfr(status = """{"isPinRequired": true, "gsc": ["2110"]}""")
        assertIs<RezultatPina.NijePoslat>(veza(lazni).unesiPin("1234"))
        assertTrue(lazni.pinZahtevi.isEmpty())
    }

    @Test
    fun akoPinNijePotrebanNeSaljeSe() = runTest {
        val lazni = LazniLpfr(status = """{"isPinRequired": false, "gsc": ["0210"]}""")
        assertEquals(RezultatPina.NijePotreban, veza(lazni).unesiPin("1234"))
        assertTrue(lazni.pinZahtevi.isEmpty())
    }

    @Test
    fun nedostupanLpfrNePitaNiStatusNiPin() = runTest {
        val lazni = LazniLpfr(dostupan = false)
        assertIs<RezultatPina.NijePoslat>(veza(lazni).unesiPin("1234"))
        assertEquals(listOf("/api/v3/attention"), lazni.zahtevi.map { it.url.encodedPath })
    }

    @Test
    fun cudanOdgovorNaPin() = runTest {
        assertFailsWith<EsirGreska.NeispravanOdgovor> {
            veza(LazniLpfr(odgovorNaPin = "<html>greska</html>")).posaljiPin("1234")
        }
    }
}
