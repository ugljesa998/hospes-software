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
import rs.omniit.hospes.esir.SirinaPapira
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
    private var brojRacuna = 0

    /** Telo zahteva za račun, kako ga je ESIR poslao PFR-u. */
    private fun poslatiRacuni() = zahtevi.filter { it.url.encodedPath.endsWith("/invoices") }.map {
        Json.parseToJsonElement((it.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
    }

    private fun kotlinx.serialization.json.JsonObject.tekst(polje: String) = this[polje]?.jsonPrimitive?.content
    private val odstampano = mutableListOf<Pair<String, SirinaPapira>>()
    private var stampacRadi = true

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
        override fun pregledOtiska(brojRacuna: String, papir: SirinaPapira): ByteArray? = null
        override fun stampaj(brojRacuna: String, papir: SirinaPapira): String {
            check(stampacRadi) { "nema papira" }
            odstampano += brojRacuna to papir
            return "Odštampan na test štampaču."
        }
        override fun procitajCsv(): String? = null
        override fun sacuvajCsv(predlogImena: String, tekst: String): String? = null
        override fun otvoriPfr(vrsta: VrstaPfra): OtvorenPfr {
            val http = EsirHttp.napravi(
                MockEngine { z ->
                    zahtevi += z
                    when {
                        z.url.encodedPath.endsWith("/attention") -> respond("", HttpStatusCode.OK)
                        z.url.encodedPath.endsWith("/status") -> respond(status, HttpStatusCode.OK, json)
                        z.url.encodedPath.endsWith("/invoices") -> {
                            // Kao pravi PFR: novi broj računa i ukupni brojač (po njemu se žurnal ređa kada je vreme isto).
                            val n = ++brojRacuna
                            val odgovor = izdat.replace("TEST1234-TEST1234-1", "TEST1234-TEST1234-$n")
                                .replace("\"totalAmount\"", "\"totalCounter\":$n,\"totalAmount\"")
                            respond(odgovor, HttpStatusCode.OK, json)
                        }
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

        assertEquals("Račun TEST1234-TEST1234-1 (Promet Prodaja) je izdat. Odštampan na test štampaču.", m.poruka?.tekst)
        assertFalse(m.poruka!!.greska)
        assertEquals(listOf("TEST1234-TEST1234-1" to SirinaPapira.MM80), odstampano, "štampa se jednom, odmah posle izdavanja")
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

    @Test
    fun stampaNijeUspelaAliJeRacunIzdat() = runTest {
        val m = EsirModel(okruzenje, this)
        m.osvezi()
        m.poslednjiPosao!!.join()
        assertNull(m.dodajStavku("Kafa", "1", "300", "A", ""))
        m.papir = SirinaPapira.MM58
        stampacRadi = false
        m.izdaj()
        m.poslednjiPosao!!.join()

        val poruka = assertNotNull(m.poruka)
        assertTrue(poruka.greska)
        assertEquals("Račun TEST1234-TEST1234-1 (Obuka Prodaja) JE izdat (ne izdaji ga ponovo), ali štampa nije uspela: nema papira", poruka.tekst)
        assertEquals(1, okruzenje.zurnal.svi().size, "račun je u žurnalu")
        assertTrue(m.stavke.isEmpty())
    }

    /** Izdaje račun sa jednom stavkom i vraća ga iz žurnala. */
    private suspend fun EsirModel.izdajProbni(vrsta: VrstaRacuna, nacin: NacinPlacanja = NacinPlacanja.GOTOVINA): rs.omniit.hospes.esir.IzdatRacun {
        if (status == null) {
            osvezi()
            poslednjiPosao!!.join()
        }
        assertNull(dodajStavku("Kafa", "3", "250", "A", "10"))
        vrstaRacuna = vrsta
        nacinPlacanja = nacin
        izdaj()
        poslednjiPosao!!.join()
        return assertNotNull(okruzenje.zurnal.nadji("TEST1234-TEST1234-$brojRacuna"))
    }

    @Test
    fun refundacijaGotovineIKopijaZaPotpisKupca() = runTest {
        val m = EsirModel(okruzenje, this)
        val original = m.izdajProbni(VrstaRacuna.PROMET)

        assertEquals("Unesi broj (Broj lične karte (domaće fizičko lice))", m.refundiraj(original, "20", " "))
        assertTrue(m.refundiraj(original, "99", "123")!!.contains("nepoznata šifra 99"))
        assertEquals(1, poslatiRacuni().size, "neispravan unos ne ide PFR-u")

        assertNull(m.refundiraj(original, "20", " 123456789 "))
        m.poslednjiPosao!!.join()

        val (_, refundacija, kopija) = poslatiRacuni()
        assertEquals("Normal", refundacija.tekst("invoiceType"))
        assertEquals("Refund", refundacija.tekst("transactionType"))
        assertEquals("20:123456789", refundacija.tekst("buyerId"))
        assertEquals("TEST1234-TEST1234-1", refundacija.tekst("referentDocumentNumber"))
        assertEquals("Copy", kopija.tekst("invoiceType"), "posle gotovinske refundacije: kopija za potpis (9.6)")
        assertEquals("Refund", kopija.tekst("transactionType"))
        assertEquals("TEST1234-TEST1234-2", kopija.tekst("referentDocumentNumber"))
        assertEquals("20:123456789", kopija.tekst("buyerId"))

        assertEquals(listOf("TEST1234-TEST1234-1", "TEST1234-TEST1234-2", "TEST1234-TEST1234-3"), odstampano.map { it.first })
        val poruka = assertNotNull(m.poruka)
        assertFalse(poruka.greska, poruka.tekst)
        assertTrue("TEST1234-TEST1234-2 (Promet Refundacija) je izdat" in poruka.tekst, poruka.tekst)
        assertTrue("TEST1234-TEST1234-3 (Kopija Refundacija) je izdat" in poruka.tekst, poruka.tekst)
        assertEquals("TEST1234-TEST1234-3", m.prikazan?.broj, "prikazana je kopija za potpis")

        assertEquals("TEST1234-TEST1234-2", m.refundacijaZa(original)?.racun?.invoiceNumber)
        assertEquals(
            "Račun TEST1234-TEST1234-1 je već refundiran računom TEST1234-TEST1234-2",
            m.refundiraj(original, "20", "123456789"),
        )
        assertEquals(3, poslatiRacuni().size, "drugi put se ne šalje")
    }

    @Test
    fun refundacijaKarticomIObukeBezKopije() = runTest {
        val m = EsirModel(okruzenje, this)
        val karticom = m.izdajProbni(VrstaRacuna.PROMET, NacinPlacanja.PLATNA_KARTICA)
        assertNull(m.refundiraj(karticom, "10", "123456789"))
        m.poslednjiPosao!!.join()
        assertEquals(2, poslatiRacuni().size, "kartica: bez kopije za potpis")

        val obuka = m.izdajProbni(VrstaRacuna.OBUKA)
        assertNull(m.refundiraj(obuka, "20", "1"))
        m.poslednjiPosao!!.join()
        assertEquals(4, poslatiRacuni().size, "obuka: bez kopije")
        assertEquals("Training", poslatiRacuni().last().tekst("invoiceType"))
        assertEquals("Refund", poslatiRacuni().last().tekst("transactionType"))
    }

    @Test
    fun kopijaSamoZaPromet() = runTest {
        val m = EsirModel(okruzenje, this)
        val obuka = m.izdajProbni(VrstaRacuna.OBUKA)
        assertEquals("Kopija se izdaje za račun vrste Promet, a ovo je Obuka Prodaja", m.izdajKopiju(obuka))
        assertEquals(1, poslatiRacuni().size)

        val promet = m.izdajProbni(VrstaRacuna.PROMET)
        assertNull(m.izdajKopiju(promet))
        m.poslednjiPosao!!.join()
        val kopija = poslatiRacuni().last()
        assertEquals("Copy", kopija.tekst("invoiceType"))
        assertEquals("Sale", kopija.tekst("transactionType"))
        assertEquals(promet.racun.invoiceNumber, kopija.tekst("referentDocumentNumber"))
        assertTrue(m.poruka!!.tekst.contains("(Kopija Prodaja) je izdat"), m.poruka!!.tekst)
    }

    @Test
    fun kupacNaRacunu() = runTest {
        val m = EsirModel(okruzenje, this)
        m.osvezi()
        m.poslednjiPosao!!.join()
        assertNull(m.dodajStavku("Kafa", "1", "300", "A", ""))
        m.sifraKupca = "99"
        m.brojKupca = "123"
        m.izdaj()
        assertTrue(m.poruka!!.tekst.contains("nepoznata šifra 99"), m.poruka!!.tekst)
        assertEquals(0, poslatiRacuni().size)

        m.sifraKupca = "10"
        m.brojKupca = " 115423827 "
        m.izdaj()
        m.poslednjiPosao!!.join()
        assertEquals("10:115423827", poslatiRacuni().single().tekst("buyerId"))
        assertEquals("", m.brojKupca, "sledeći račun je opet bez kupca")

        assertNull(m.dodajStavku("Kafa", "1", "300", "A", ""))
        m.izdaj()
        m.poslednjiPosao!!.join()
        assertNull(poslatiRacuni().last()["buyerId"]?.takeUnless { it is kotlinx.serialization.json.JsonNull })
    }

    @Test
    fun naplataPoPredracunu() = runTest {
        val m = EsirModel(okruzenje, this)
        val predracun = m.izdajProbni(VrstaRacuna.PREDRACUN)
        assertEquals("75", m.kusurZaPredracun(predracun, "750").toString(), "675 za naplatu, kupac dao 750")
        assertNull(m.prometIzPredracuna(predracun))

        assertNull(m.naplatiPredracun(predracun, NacinPlacanja.PLATNA_KARTICA))
        m.poslednjiPosao!!.join()
        val promet = poslatiRacuni().last()
        assertEquals("Normal", promet.tekst("invoiceType"))
        assertEquals("Sale", promet.tekst("transactionType"))
        assertEquals(predracun.racun.invoiceNumber, promet.tekst("referentDocumentNumber"))
        val placanje = promet["payment"]!!.jsonArray.single().jsonObject
        assertEquals("Card", placanje["paymentType"]!!.jsonPrimitive.content)
        assertEquals("675", placanje["amount"].toString())
        assertTrue(m.poruka!!.tekst.contains("(Promet Prodaja) je izdat"), m.poruka!!.tekst)

        assertEquals("TEST1234-TEST1234-2", m.prometIzPredracuna(predracun)?.racun?.invoiceNumber)
        assertEquals(
            "Predračun TEST1234-TEST1234-1 je već naplaćen računom TEST1234-TEST1234-2",
            m.naplatiPredracun(predracun, NacinPlacanja.GOTOVINA),
        )
        assertEquals(2, poslatiRacuni().size)
    }

    @Test
    fun delimicnaRefundacijaUDvaDela() = runTest {
        val m = EsirModel(okruzenje, this)
        val original = m.izdajProbni(VrstaRacuna.PROMET) // Kafa 3 × 225 (posle 10% popusta), gotovina
        assertEquals(listOf("3"), m.preostaloZaRefundaciju(original).map { it.toString() })
        assertEquals("225", m.iznosRefundacije(original, listOf("1")).toString())

        assertNull(m.refundiraj(original, "20", "123456789", listOf("1"), NacinPlacanja.GOTOVINA))
        m.poslednjiPosao!!.join()
        val prva = poslatiRacuni()[1]
        val stavka = prva["items"]!!.jsonArray.single().jsonObject
        assertEquals("1", stavka["quantity"].toString())
        assertEquals("225", stavka["totalAmount"].toString())
        assertEquals("225", prva["payment"]!!.jsonArray.single().jsonObject["amount"].toString())
        assertEquals("Copy", poslatiRacuni()[2].tekst("invoiceType"), "gotovina: kopija za potpis")

        assertEquals(listOf("2"), m.preostaloZaRefundaciju(original).map { it.toString() })
        assertEquals("„Kafa“: može da se vrati najviše 2, a traži se 3", m.refundiraj(original, "20", "123456789", listOf("3")))

        assertNull(m.refundiraj(original, "20", "123456789"), "bez količina: vraća se sve što je ostalo")
        m.poslednjiPosao!!.join()
        assertEquals("2", poslatiRacuni()[3]["items"]!!.jsonArray.single().jsonObject["quantity"].toString())
        assertEquals(listOf("0"), m.preostaloZaRefundaciju(original).map { it.toString() })
        assertEquals(
            "Račun TEST1234-TEST1234-1 je već refundiran računima TEST1234-TEST1234-2, TEST1234-TEST1234-4",
            m.refundiraj(original, "20", "123456789"),
        )
        assertEquals(5, poslatiRacuni().size)
    }
}
