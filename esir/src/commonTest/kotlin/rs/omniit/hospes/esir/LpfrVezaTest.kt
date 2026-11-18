package rs.omniit.hospes.esir

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LpfrVezaTest {
    private val adresa = "http://devesdc.sandbox.suf.purs.gov.rs:8888/token-123/api/"

    private fun veza(odgovor: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): LpfrVeza =
        LpfrVeza(adresa, EsirHttp.napravi(MockEngine(odgovor)))

    private val jsonZaglavlje = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun statusGadjaIspravnuAdresuISaljeAccept() = runTest {
        val zahtevi = mutableListOf<HttpRequestData>()
        val v = veza { zahtev ->
            zahtevi += zahtev
            respond(Primeri.STATUS_LPFR, HttpStatusCode.OK, jsonZaglavlje)
        }
        v.status()

        val z = zahtevi.single()
        assertEquals(HttpMethod.Get, z.method)
        assertEquals("/token-123/api/v3/status", z.url.encodedPath)
        assertEquals("application/json", z.headers[HttpHeaders.Accept])
    }

    @Test
    fun citaStatusIPoreskeStope() = runTest {
        val status = veza { respond(Primeri.STATUS_LPFR, HttpStatusCode.OK, jsonZaglavlje) }.status()

        assertEquals(true, status.isPinRequired)
        assertEquals(listOf("1500"), status.gsc)
        assertEquals("TK7SV2AY", status.uid)
        val stope = status.currentTaxRates!!.taxCategories.flatMap { it.taxRates }.associate { it.label to it.rate.toString() }
        assertEquals(mapOf("A" to "9", "B" to "0", "P" to "0.5"), stope)
    }

    @Test
    fun attentionVracaTrueKadaJeLpfrDostupan() = runTest {
        val zahtevi = mutableListOf<HttpRequestData>()
        val v = veza { zahtev ->
            zahtevi += zahtev
            respond("", HttpStatusCode.OK)
        }
        assertTrue(v.attention())
        assertEquals("/token-123/api/v3/attention", zahtevi.single().url.encodedPath)
    }

    @Test
    fun attentionVracaFalseUmestoGreske() = runTest {
        assertFalse(veza { respond("", HttpStatusCode.ServiceUnavailable) }.attention())
        assertFalse(veza { throw RuntimeException("veza odbijena") }.attention())
    }

    @Test
    fun nedostupanLpfr() = runTest {
        val greska = assertFailsWith<EsirGreska.PfrNedostupan> {
            veza { throw RuntimeException("veza odbijena") }.status()
        }
        assertTrue("veza odbijena" in greska.message!!)
    }

    @Test
    fun neuspesanHttpStatus() = runTest {
        val greska = assertFailsWith<EsirGreska.NeocekivanOdgovor> {
            veza { respond("Not found", HttpStatusCode.NotFound) }.status()
        }
        assertEquals(404, greska.httpStatus)
        assertEquals("Not found", greska.telo)
    }

    @Test
    fun neispravanJson() = runTest {
        assertFailsWith<EsirGreska.NeispravanOdgovor> {
            veza { respond("ovo nije json", HttpStatusCode.OK, jsonZaglavlje) }.status()
        }
    }
}
