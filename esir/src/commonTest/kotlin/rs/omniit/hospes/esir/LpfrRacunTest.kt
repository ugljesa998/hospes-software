package rs.omniit.hospes.esir

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpfrRacunTest {
    private val jsonZaglavlje = headersOf(HttpHeaders.ContentType, "application/json")

    /** Training Sale: „Test kafa“, 1 × 300 RSD, gotovina, oznaka A. */
    private val testRacun = ZahtevZaRacun(
        invoiceType = VrstaRacuna.OBUKA,
        transactionType = TipTransakcije.PRODAJA,
        payment = listOf(Placanje(Decimal4.parse("300"), NacinPlacanja.GOTOVINA)),
        items = listOf(
            Stavka(
                name = "Test kafa",
                quantity = Decimal4.parse("1"),
                unitPrice = Decimal4.parse("300"),
                totalAmount = Decimal4.parse("300"),
                labels = listOf("A"),
            ),
        ),
        cashier = "Hospes test",
    )

    private val odgovorRacun = """
        {
          "requestedBy": "DB75GBZD", "signedBy": "HE29WWO0",
          "sdcDateTime": "2026-07-17T09:08:55+02:00",
          "invoiceCounter": "3/3ОП", "invoiceCounterExtension": "ОП",
          "invoiceNumber": "DB75GBZD-HE29WWO0-3",
          "taxItems": [ { "categoryType": 0, "label": "A", "amount": 27.2727, "rate": 10, "categoryName": "VAT" } ],
          "verificationUrl": "https://sandbox.taxcore.online/v/?vl=primer",
          "verificationQRCode": "R0lGODlhAQABAAAAACw=",
          "journal": "=== ОВО НИЈЕ ФИСКАЛНИ РАЧУН ===",
          "messages": "Success",
          "totalCounter": 3, "transactionTypeCounter": 3, "totalAmount": 300,
          "taxGroupRevision": 2, "businessName": "Primer", "tin": "123123123", "mrc": "99-3800-HE29WWO0"
        }
    """

    private class LazniLpfr(
        val dostupan: Boolean = true,
        val naRacun: (suspend () -> Pair<HttpStatusCode, String>)? = null,
        val poRequestId: (suspend () -> Pair<HttpStatusCode, String>)? = null,
    ) {
        val zahtevi = mutableListOf<HttpRequestData>()
        fun zahteviNa(sufiks: String) = zahtevi.filter { it.url.encodedPath.endsWith(sufiks) }
        val postovi get() = zahtevi.filter { it.method == HttpMethod.Post }
    }

    private fun veza(lazni: LazniLpfr): LpfrVeza {
        val engine = MockEngine { zahtev ->
            lazni.zahtevi += zahtev
            val putanja = zahtev.url.encodedPath
            val (status, telo) = when {
                putanja.endsWith("/v3/attention") ->
                    (if (lazni.dostupan) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable) to ""
                putanja.endsWith("/v3/invoices") -> lazni.naRacun!!.invoke()
                putanja.contains("/v3/invoices/") -> lazni.poRequestId!!.invoke()
                else -> HttpStatusCode.NotFound to ""
            }
            respond(telo, status, jsonZaglavlje)
        }
        return LpfrVeza("http://lpfr.local:8888/api", EsirHttp.napravi(engine))
    }

    private fun telo(zahtev: HttpRequestData): JsonObject {
        val sadrzaj = zahtev.body
        val tekst = when (sadrzaj) {
            is TextContent -> sadrzaj.text
            is OutgoingContent.ByteArrayContent -> sadrzaj.bytes().decodeToString()
            else -> error("Neočekivan sadržaj: ${sadrzaj::class}")
        }
        return Json.parseToJsonElement(tekst).jsonObject
    }

    @Test
    fun zahtevSeSaljePremaTaxCoreFormatu() = runTest {
        val lazni = LazniLpfr(naRacun = { HttpStatusCode.OK to odgovorRacun })
        veza(lazni).izdajRacun(testRacun, requestId = "abc123")

        val z = lazni.zahteviNa("/v3/invoices").single()
        assertEquals("/api/v3/invoices", z.url.encodedPath)
        assertEquals("abc123", z.headers["RequestId"])
        assertEquals("sr-Cyrl-RS", z.headers[HttpHeaders.AcceptLanguage])
        assertEquals("application/json", z.headers[HttpHeaders.Accept])

        val json = telo(z)
        assertEquals("Training", json["invoiceType"]!!.jsonPrimitive.content)
        assertEquals("Sale", json["transactionType"]!!.jsonPrimitive.content)
        assertEquals("Hospes test", json["cashier"]!!.jsonPrimitive.content)
        val placanje = json["payment"]!!.jsonArray.single().jsonObject
        assertEquals("Cash", placanje["paymentType"]!!.jsonPrimitive.content)
        assertEquals("300", placanje["amount"].toString(), "iznos ide kao JSON broj, bez navodnika")
        val stavka = json["items"]!!.jsonArray.single().jsonObject
        assertEquals("Test kafa", stavka["name"]!!.jsonPrimitive.content)
        assertEquals("1", stavka["quantity"].toString())
        assertEquals("[\"A\"]", stavka["labels"].toString())
        assertFalse("buyerId" in json, "prazna polja se ne šalju")
        assertFalse("gtin" in stavka)
    }

    @Test
    fun citaFiskalizovanRacun() = runTest {
        val rezultat = veza(LazniLpfr(naRacun = { HttpStatusCode.OK to odgovorRacun })).izdajRacun(testRacun)

        val izdat = assertIs<RezultatRacuna.Izdat>(rezultat)
        assertFalse(izdat.pronadjenNaknadno)
        assertEquals("DB75GBZD-HE29WWO0-3", izdat.racun.invoiceNumber)
        assertEquals("3/3ОП", izdat.racun.invoiceCounter)
        assertEquals("300", izdat.racun.totalAmount.toString())
        assertEquals("27.2727", izdat.racun.taxItems.single().amount.toString())
        assertEquals(32, izdat.requestId.length)
    }

    @Test
    fun odbijenZahtevCitaSifrePoPoljima() = runTest {
        val greska = """{"message":"The request is invalid.","modelState":[{"property":"items[0].labels[0]","errors":["2310"]}]}"""
        val lazni = LazniLpfr(naRacun = { HttpStatusCode.BadRequest to greska })
        val rezultat = veza(lazni).izdajRacun(testRacun)

        val odbijen = assertIs<RezultatRacuna.Odbijen>(rezultat)
        assertEquals(400, odbijen.httpStatus)
        assertEquals("items[0].labels[0]", odbijen.greska!!.modelState.single().property)
        assertEquals(listOf("2310"), odbijen.greska.modelState.single().errors)
        assertTrue(lazni.zahteviNa("/invoices/abc").isEmpty(), "odbijen račun se ne traži po RequestId")
    }

    @Test
    fun prekidPosleSlanjaRacunSePronalaziPoRequestId() = runTest {
        val lazni = LazniLpfr(
            naRacun = { throw RuntimeException("isteklo vreme") },
            poRequestId = { HttpStatusCode.OK to odgovorRacun },
        )
        val rezultat = veza(lazni).izdajRacun(testRacun, requestId = "abc123")

        val izdat = assertIs<RezultatRacuna.Izdat>(rezultat)
        assertTrue(izdat.pronadjenNaknadno)
        assertEquals(1, lazni.postovi.size, "račun se ne šalje ponovo")
        assertEquals("/api/v3/invoices/abc123", lazni.zahteviNa("/invoices/abc123").single().url.encodedPath)
    }

    @Test
    fun prekidPosleSlanjaRacunNijeIzdat() = runTest {
        val lazni = LazniLpfr(
            naRacun = { throw RuntimeException("isteklo vreme") },
            poRequestId = { HttpStatusCode.OK to "null" },
        )
        assertIs<RezultatRacuna.NijeIzdat>(veza(lazni).izdajRacun(testRacun))
        assertEquals(1, lazni.postovi.size)
    }

    @Test
    fun prekidIProveraNeuspesnaZnaciNepoznatoStanje() = runTest {
        val lazni = LazniLpfr(
            naRacun = { throw RuntimeException("isteklo vreme") },
            poRequestId = { throw RuntimeException("i dalje nema veze") },
        )
        val rezultat = veza(lazni).izdajRacun(testRacun, requestId = "abc123")

        val nepoznato = assertIs<RezultatRacuna.NepoznatoStanje>(rezultat)
        assertEquals("abc123", nepoznato.requestId)
        assertEquals(1, lazni.postovi.size)
    }

    @Test
    fun nedostupanLpfrRacunSeNeSalje() = runTest {
        val lazni = LazniLpfr(dostupan = false)
        assertIs<RezultatRacuna.NijeIzdat>(veza(lazni).izdajRacun(testRacun))
        assertTrue(lazni.postovi.isEmpty())
    }

    @Test
    fun racunPoRequestIdKojiNePostoji() = runTest {
        assertNull(veza(LazniLpfr(poRequestId = { HttpStatusCode.NotFound to "" })).racunPoRequestId("abc"))
        assertNull(veza(LazniLpfr(poRequestId = { HttpStatusCode.OK to "" })).racunPoRequestId("abc"))
    }

    @Test
    fun neispravanZahtevSeNeSalje() = runTest {
        val lazni = LazniLpfr()
        val bezStavki = testRacun.copy(items = emptyList())
        assertFailsWith<IllegalArgumentException> { veza(lazni).izdajRacun(bezStavki) }
        val refundacijaBezReference = testRacun.copy(transactionType = TipTransakcije.REFUNDACIJA)
        assertFailsWith<IllegalArgumentException> { veza(lazni).izdajRacun(refundacijaBezReference) }
        assertFailsWith<IllegalArgumentException> { veza(lazni).izdajRacun(testRacun, requestId = "x".repeat(33)) }
        assertTrue(lazni.zahtevi.isEmpty())
    }

    @Test
    fun proveraStavke() {
        val s = testRacun.items.single()
        assertTrue(s.copy(quantity = Decimal4.parse("0.0001")).proveri().any { "3 decimale" in it })
        assertTrue(s.copy(labels = listOf("A", "A")).proveri().any { "ponovi" in it })
        assertTrue(s.copy(labels = emptyList()).proveri().any { "oznaku" in it })
        assertTrue(s.proveri().isEmpty())
    }

    @Test
    fun requestIdIma32HeksaZnaka() {
        val id = noviRequestId()
        assertEquals(32, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(id == noviRequestId())
    }
}
