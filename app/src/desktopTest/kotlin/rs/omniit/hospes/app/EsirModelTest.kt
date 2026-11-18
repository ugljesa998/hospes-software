package rs.omniit.hospes.app

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rs.omniit.hospes.esir.Artikal
import rs.omniit.hospes.esir.Decimal4
import rs.omniit.hospes.esir.EsirHttp
import rs.omniit.hospes.esir.KatalogUMemoriji
import rs.omniit.hospes.esir.LpfrVeza
import rs.omniit.hospes.esir.NacinPlacanja
import rs.omniit.hospes.esir.OtvorenPfr
import rs.omniit.hospes.esir.VrstaPfra
import rs.omniit.hospes.esir.VrstaRacuna
import rs.omniit.hospes.esir.ZurnalUMemoriji
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ceo tok ESIR test ekrana (bez prozora), sa lažnim L-PFR-om. */
class EsirModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val status = """
        {"isPinRequired":false,"gsc":["0100"],"uid":"TEST1234","supportedLanguages":["sr-Cyrl-RS"],
         "currentTaxRates":{"taxCategories":[{"name":"VAT","taxRates":[{"rate":10,"label":"A"},{"rate":0,"label":"B"}]}]}}
    """
    private val izdat = """
        {"invoiceNumber":"TEST1234-TEST1234-1","sdcDateTime":"2026-11-18T13:00:00+01:00","totalAmount":675,
         "journal":"=== ФИСКАЛНИ РАЧУН ===\nKafa (A)\n======== КРАЈ ФИСКАЛНОГ РАЧУНА ========="}
    """

    private val zahtevi = mutableListOf<HttpRequestData>()

    private val okruzenje = object : Okruzenje {
        override val pin = "1234"
        override val zurnal = ZurnalUMemoriji()
        override val katalog = KatalogUMemoriji(
            listOf(
                Artikal("101", "Espresso", Decimal4.parse("180"), "A", kategorija = "Kafa"),
                Artikal("201", "Coca-Cola 0,25", Decimal4.parse("250"), "A", gtin = "5449000000996"),
                Artikal("900", "Uvozni artikal", Decimal4.parse("100"), "Ђ"),
            ),
        )
        override fun procitajCsv(): String? = null
        override fun sacuvajCsv(predlogImena: String, tekst: String): String? = null
        override fun otvoriPfr(vrsta: VrstaPfra): OtvorenPfr {
            val http = EsirHttp.napravi(
                MockEngine { z ->
                    zahtevi += z
                    when {
                        z.url.encodedPath.endsWith("/attention") -> respond("", HttpStatusCode.OK)
                        z.url.encodedPath.endsWith("/status") -> respond(status, HttpStatusCode.OK, json)
                        z.url.encodedPath.endsWith("/invoices") -> respond(izdat, HttpStatusCode.OK, json)
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                },
            )
            return OtvorenPfr(LpfrVeza("http://lpfr.test/api", http), http, "L-PFR: test")
        }
        override fun tekstRacuna(brojRacuna: String): String? = null
        override fun pre30Dana() = "2026-01-01"
    }

    @Test
    fun obracunINaplataIzdavanjeIZurnal() = runTest {
        val m = EsirModel(okruzenje, this)
        m.osvezi()
        m.poslednjiPosao!!.join()
        assertEquals("TEST1234", m.status?.uid)
        assertEquals(listOf("A", "B"), m.stope.map { it.label })

        assertNull(m.dodajStavku("Kafa", "3", "250", "A", "10"))
        assertEquals("675", m.ukupno.toString())
        m.nacinPlacanja = NacinPlacanja.GOTOVINA
        m.primljeno = "1000"
        assertEquals("325", m.kusur.toString())

        m.vrstaRacuna = VrstaRacuna.PROMET
        m.izdaj()
        m.poslednjiPosao!!.join()

        assertEquals("Račun TEST1234-TEST1234-1 je izdat", m.poruka?.tekst)
        assertFalse(m.poruka!!.greska)
        assertTrue(m.stavke.isEmpty(), "posle izdavanja obračun je prazan")
        assertEquals("", m.primljeno)
        assertEquals(1, okruzenje.zurnal.svi().size)
        assertEquals(listOf("TEST1234-TEST1234-1"), m.zurnal.map { it.racun.invoiceNumber })
        assertNotNull(m.prikazan).let { assertTrue("Promet Prodaja" in it.naslov) }

        val telo = (zahtevi.last { it.url.encodedPath.endsWith("/invoices") }.body as OutgoingContent.ByteArrayContent)
            .bytes().decodeToString()
        val placanje = Json.parseToJsonElement(telo).jsonObject["payment"]!!.jsonArray.single().jsonObject
        assertEquals("675", placanje["amount"].toString(), "PFR-u ide iznos koji ostaje u kasi, ne 1000")
        assertEquals("Cash", placanje["paymentType"]!!.jsonPrimitive.content)
    }

    @Test
    fun neispravanUnosStavke() = runTest {
        val m = EsirModel(okruzenje, this)
        assertEquals("Unesi naziv", m.dodajStavku(" ", "1", "100", "A", ""))
        assertEquals("Izaberi poresku oznaku", m.dodajStavku("Kafa", "1", "100", null, ""))
        assertEquals("Količina može da ima najviše 3 decimale", m.dodajStavku("Kafa", "0,0005", "100", "A", ""))
        assertNull(m.dodajStavku("Sir /kg", "0,250", "1199,99", "A", ""), "zarez kao decimalni separator")
        assertEquals("300", m.ukupno.toString())
        m.ukloniStavku(0)
        assertTrue(m.stavke.isEmpty())
    }

    @Test
    fun izdavanjeBezStavkiNeIdePfru() = runTest {
        val m = EsirModel(okruzenje, this)
        m.izdaj()
        assertEquals("Račun nema stavki", m.poruka?.tekst)
        assertTrue(zahtevi.none { it.url.encodedPath.endsWith("/invoices") })
    }

    @Test
    fun artikliIzKataloga() = runTest {
        val m = EsirModel(okruzenje, this)
        m.ucitajKatalog()
        assertEquals(3, m.artikli.size)
        assertEquals("Stanje PFR-a još nije učitano, pa se ne znaju poreske oznake", m.dodajArtikal(m.artikli[0]))

        m.osvezi()
        m.poslednjiPosao!!.join()
        assertNull(m.dodajArtikal(m.artikli[0]))
        assertNull(m.dodajArtikal(m.artikli[0]), "drugi klik povećava količinu")
        assertNull(m.dodajPoKodu("5449000000996"), "bar-kod čitač")
        assertEquals(listOf("Espresso /kom" to "2", "Coca-Cola 0,25 /kom" to "1"), m.stavke.map { it.name to it.quantity.toString() })
        assertEquals("5449000000996", m.stavke[1].gtin)
        assertEquals("610", m.ukupno.toString())

        assertTrue(m.dodajArtikal(m.artikli[2])!!.startsWith("Artikal 900 ima oznaku Ђ, a PFR je ne vraća"))
        assertTrue(m.dodajPoKodu("777")!!.startsWith("U katalogu nema artikla"))
        assertEquals(2, m.stavke.size)
    }

    @Test
    fun uredjivanjeKataloga() = runTest {
        val m = EsirModel(okruzenje, this)
        assertNull(m.sacuvajArtikal(true, "102", "Kafa sa mlekom", "", "220,5", "A", "", " Kafa "))
        val novi = m.artikli.single { it.sifra == "102" }
        assertEquals("kom", novi.jedinicaMere)
        assertEquals("220.5", novi.cena.toString())
        assertEquals("Kafa", novi.kategorija)

        assertEquals("Šifra 102 već postoji; izmeni taj artikal u listi", m.sacuvajArtikal(true, "102", "Drugi", "kom", "1", "A", "", ""))
        assertNull(m.sacuvajArtikal(false, "102", "Kafa sa mlekom", "kom", "240", "A", "", "Kafa"), "promena cene")
        assertEquals("240", m.artikli.single { it.sifra == "102" }.cena.toString())
        assertEquals("Artikal 103: Cena može da ima najviše 2 decimale", m.sacuvajArtikal(true, "103", "Čaj", "kom", "1,555", "A", "", ""))

        m.uveziKatalog("sifra;naziv;cena;oznaka\n104;Čaj;180,00;A\n105;;10;A\n")
        assertTrue(m.porukaKataloga!!.tekst.startsWith("Uvezeno 1 artikala. Preskočeno 1: Red 3"), m.porukaKataloga!!.tekst)
        assertNotNull(m.artikli.singleOrNull { it.sifra == "104" })

        m.ukloniArtikal("104")
        assertNull(m.artikli.singleOrNull { it.sifra == "104" })
    }
}
