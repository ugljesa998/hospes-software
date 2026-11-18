package rs.omniit.hospes.esir

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VpfrVezaTest {
    private val jsonZaglavlje = headersOf(HttpHeaders.ContentType, "application/json")
    private val statusVpfr = """{"sdcDateTime":"2026-11-18T12:00:00+01:00","uid":"ACN84XSJ","supportedLanguages":["sr-Cyrl-RS"]}"""
    private val odgovorRacun = """{"invoiceNumber":"ACN84XSJ-ACN84XSJ-1","invoiceCounter":"1/1ОП","totalAmount":300}"""

    private fun veza(zahtevi: MutableList<HttpRequestData>, adresa: String = "https://vsdc.sandbox.suf.purs.gov.rs/"): VpfrVeza {
        val engine = MockEngine { z ->
            zahtevi += z
            when {
                z.url.encodedPath.endsWith("/v3/attention") -> respond("", HttpStatusCode.OK)
                z.url.encodedPath.endsWith("/v3/status") -> respond(statusVpfr, HttpStatusCode.OK, jsonZaglavlje)
                z.url.encodedPath.endsWith("/v3/invoices") -> respond(odgovorRacun, HttpStatusCode.OK, jsonZaglavlje)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        return VpfrVeza(adresa, pac = "ABC123", http = EsirHttp.napravi(engine))
    }

    private val racun = ZahtevZaRacun(
        invoiceType = VrstaRacuna.OBUKA,
        transactionType = TipTransakcije.PRODAJA,
        payment = listOf(Placanje(Decimal4.parse("300"), NacinPlacanja.GOTOVINA)),
        items = listOf(Stavka("Test kafa", Decimal4.parse("1"), Decimal4.parse("300"), Decimal4.parse("300"), listOf("A"))),
    )

    @Test
    fun statusIdeNaApiV3SaPacom() = runTest {
        val zahtevi = mutableListOf<HttpRequestData>()
        val status = veza(zahtevi).status()

        assertEquals("ACN84XSJ", status.uid)
        assertNull(status.isPinRequired, "V-PFR nema PIN")
        val z = zahtevi.single()
        assertEquals("vsdc.sandbox.suf.purs.gov.rs", z.url.host)
        assertEquals("/api/v3/status", z.url.encodedPath)
        assertEquals("ABC123", z.headers["PAC"])
    }

    @Test
    fun racunSeSaljeSaPacomIRequestId() = runTest {
        val zahtevi = mutableListOf<HttpRequestData>()
        val rezultat = veza(zahtevi).izdajRacun(racun, requestId = "abc123")

        assertEquals("ACN84XSJ-ACN84XSJ-1", assertIs<RezultatRacuna.Izdat>(rezultat).racun.invoiceNumber)
        assertEquals(listOf("/api/v3/attention", "/api/v3/invoices"), zahtevi.map { it.url.encodedPath })
        assertTrue(zahtevi.all { it.headers["PAC"] == "ABC123" }, "PAC ide uz svaki zahtev")
        assertEquals("abc123", zahtevi.last().headers["RequestId"])
    }

    @Test
    fun adresaBezKoseCrteNaKrajuRadiIsto() = runTest {
        val zahtevi = mutableListOf<HttpRequestData>()
        veza(zahtevi, adresa = "https://vsdc.sandbox.suf.purs.gov.rs").status()
        assertEquals("/api/v3/status", zahtevi.single().url.encodedPath)
    }

    @Test
    fun pacJeObavezanINeIspisujeSe() {
        val http = EsirHttp.napravi(MockEngine { respond("") })
        assertFailsWith<IllegalArgumentException> { VpfrVeza("https://vsdc.example", " ", http) }
        assertFalse("ABC123" in VpfrVeza("https://vsdc.example", "ABC123", http).toString())
    }

    @Test
    fun nazivVeze() {
        val http = EsirHttp.napravi(MockEngine { respond("") })
        val veze: List<PfrVeza> = listOf(LpfrVeza("http://lpfr/api", http), VpfrVeza("https://vsdc", "P", http))
        assertEquals(listOf("L-PFR", "V-PFR"), veze.map { it.naziv })
    }

    @Test
    fun tekstIzEkstenzijeSertifikata() {
        val url = "https://vsdc.sandbox.suf.purs.gov.rs"
        val sirov = url.encodeToByteArray()
        // OCTET STRING sa samim tekstom (kako ga TaxCore upisuje)
        assertEquals(url, TaxCoreSertifikat.tekstEkstenzije(byteArrayOf(0x04, sirov.size.toByte()) + sirov))
        // OCTET STRING sa UTF8String unutra
        val utf8 = byteArrayOf(0x0C, sirov.size.toByte()) + sirov
        assertEquals(url, TaxCoreSertifikat.tekstEkstenzije(byteArrayOf(0x04, utf8.size.toByte()) + utf8))
        // Već raspakovana vrednost
        assertEquals("RS115423827", TaxCoreSertifikat.tekstEkstenzije("RS115423827".encodeToByteArray()))
    }

    @Test
    fun poljaIzOid() {
        assertEquals(7, TaxCoreSertifikat.poljeIzOid("1.3.6.1.4.1.49952.5.8.7"))
        assertEquals(6, TaxCoreSertifikat.poljeIzOid("1.3.6.1.4.1.49952.3.8.6"))
        assertNull(TaxCoreSertifikat.poljeIzOid("2.5.29.15"))
    }

    @Test
    fun proveraPodesavanjaVpfr() {
        assertEquals(emptyList(), VpfrPodesavanja(null, "config/a.p12", "lozinka", "PAC123").proveri())
        val prazno = VpfrPodesavanja().proveri()
        assertTrue(prazno.any { "pfxPath" in it } && prazno.any { "pac" in it } && prazno.any { "pfxPassword" in it })
        assertTrue(VpfrPodesavanja("https://x", "<SERTIFIKAT>", "a", "b").proveri().any { "primer" in it })
        assertTrue(VpfrPodesavanja("http://x", "a", "b", "c").proveri().any { "https" in it })
    }
}
