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
import rs.omniit.hospes.esir.LpfrPodesavanja
import rs.omniit.hospes.esir.LpfrVeza
import rs.omniit.hospes.esir.Podesavanja
import rs.omniit.hospes.esir.NacinPlacanja
import rs.omniit.hospes.esir.OgranicenjePlacanja
import rs.omniit.hospes.esir.OtvorenPfr
import rs.omniit.hospes.esir.SirinaPapira
import rs.omniit.hospes.esir.VrstaPfra
import rs.omniit.hospes.esir.VrstaRacuna
import rs.omniit.hospes.esir.ZurnalUMemoriji
import kotlinx.serialization.json.JsonObject
import rs.omniit.hospes.esir.Avans
import rs.omniit.hospes.esir.SifraAvansa
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ceo tok ESIR test ekrana (bez prozora), sa lažnim L-PFR-om. */
class EsirModelTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    /** L-PFR traži PIN (status) i šta odgovara na PIN. */
    private var traziPin = false
    private var odgovorNaPin = "\"0100\""
    private val status get() = """
        {"isPinRequired":$traziPin,"gsc":["0100"],"uid":"TEST1234","supportedLanguages":["sr-Cyrl-RS"],
         "currentTaxRates":{"taxCategories":[{"name":"VAT","taxRates":[{"rate":10,"label":"A"},{"rate":0,"label":"B"}]}]}}
    """
    private val izdat = """
        {"invoiceNumber":"TEST1234-TEST1234-1","sdcDateTime":"2026-11-18T13:00:00+01:00","tin":"RS115423827","totalAmount":675,
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

    /** Žurnal u memoriji; pamti i redove koje ESIR dodaje ispod računa. */
    private val zurnalUMemoriji = ZurnalUMemoriji()
    private val dodaci = mutableMapOf<String, List<String>>()

    /** Sačuvana podešavanja (umesto fajla); jedan kasir, pa je izabran odmah. */
    private var sacuvano = Podesavanja(lpfr = LpfrPodesavanja("http://lpfr.test/api"), kasiri = listOf("Test kasir"))

    private val okruzenje = object : Okruzenje {
        override val folderPodataka = "/test"
        override fun ucitajPodesavanja() = sacuvano
        override fun sacuvajPodesavanja(podesavanja: Podesavanja) {
            sacuvano = podesavanja
        }
        override fun izaberiSertifikat(): String? = "config/test.p12"
        override fun opisSertifikata(putanja: String, lozinka: String): String {
            require(lozinka == "dobra") { "Sertifikat ne može da se otvori: lozinka nije dobra ili fajl nije .p12/.pfx" }
            return "UID TEST1234 · PIB 115423827"
        }
        override val zurnal = object : rs.omniit.hospes.esir.ElektronskiZurnal by zurnalUMemoriji {
            override fun sacuvaj(izdat: rs.omniit.hospes.esir.IzdatRacun, dodatak: List<String>) {
                dodaci[izdat.racun.invoiceNumber] = dodatak
                zurnalUMemoriji.sacuvaj(izdat, dodatak)
            }
        }
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
                        z.url.encodedPath.endsWith("/pin") -> respond(odgovorNaPin, HttpStatusCode.OK, json)
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
        override fun danas() = "2026-11-18"
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
        assertEquals("1795/0.0.1", Json.parseToJsonElement(telo).jsonObject["invoiceNumber"]!!.jsonPrimitive.content, "ESIR broj")
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
        m.izaberiPapir(SirinaPapira.MM58)
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

    @Test
    fun kasirNaRacunuIRefundaciji() = runTest {
        sacuvano = sacuvano.copy(kasiri = listOf("Marko", "Ana"))
        val m = EsirModel(okruzenje, this)
        assertNull(m.kasir, "dva kasira: bira se na početku smene")
        m.osvezi()
        m.poslednjiPosao!!.join()
        assertNull(m.dodajStavku("Kafa", "1", "300", "A", ""))
        m.izdaj()
        assertEquals("Izaberi kasira (gore desno)", m.poruka?.tekst)
        assertEquals(0, poslatiRacuni().size)

        m.izaberiKasira("Ana")
        m.vrstaRacuna = VrstaRacuna.PROMET
        m.izdaj()
        m.poslednjiPosao!!.join()
        assertEquals("Ana", poslatiRacuni().single().tekst("cashier"))

        m.izaberiKasira("Marko")
        val original = assertNotNull(okruzenje.zurnal.nadji("TEST1234-TEST1234-1"))
        assertNull(m.refundiraj(original, "20", "123456789"))
        m.poslednjiPosao!!.join()
        assertEquals(listOf("Ana", "Marko", "Marko"), poslatiRacuni().map { it.tekst("cashier") }, "refundaciju i kopiju izdaje Marko")
    }

    @Test
    fun bezKasiraNemaRacuna() = runTest {
        sacuvano = sacuvano.copy(kasiri = emptyList())
        val m = EsirModel(okruzenje, this)
        assertNull(m.dodajStavku("Kafa", "1", "300", "A", ""))
        m.izdaj()
        assertEquals("Dodaj kasira u Podešavanjima", m.poruka?.tekst)
    }

    @Test
    fun pinUnosiKasirIJednomSeSalje() = runTest {
        traziPin = true
        val m = EsirModel(okruzenje, this)
        m.osvezi()
        m.poslednjiPosao!!.join()
        assertEquals(true, m.status?.isPinRequired)

        assertEquals("PIN ima tačno 4 cifre", m.posaljiPin("12"))
        assertTrue(zahtevi.none { it.url.encodedPath.endsWith("/pin") })

        odgovorNaPin = "\"2100\""
        assertNull(m.posaljiPin("4321"))
        m.poslednjiPosao!!.join()
        val pinZahtevi = zahtevi.filter { it.url.encodedPath.endsWith("/pin") }
        assertEquals(1, pinZahtevi.size, "odbijen PIN se ne šalje ponovo sam")
        assertEquals("4321", (pinZahtevi.single().body as io.ktor.http.content.TextContent).text)
        assertTrue(m.poruka!!.greska && "posle 5 pogrešnih kartica se zaključava" in m.poruka!!.tekst, m.poruka!!.tekst)

        odgovorNaPin = "\"0100\""
        traziPin = false
        assertNull(m.posaljiPin("1234"))
        m.poslednjiPosao!!.join()
        assertEquals("L-PFR već ima ispravan PIN; PIN nije poslat", m.poruka?.tekst)
        assertEquals(1, zahtevi.count { it.url.encodedPath.endsWith("/pin") })
    }

    @Test
    fun podesavanjaSeCuvaju() = runTest {
        val m = EsirModel(okruzenje, this)
        assertEquals("Test kasir", m.kasir)

        assertEquals("Unesi ime kasira", m.dodajKasira(" "))
        assertNull(m.dodajKasira(" Ana "))
        assertEquals("Kasir Ana već postoji", m.dodajKasira("Ana"))
        assertEquals(listOf("Test kasir", "Ana"), sacuvano.kasiri)
        m.ukloniKasira("Test kasir")
        assertEquals(listOf("Ana"), sacuvano.kasiri)
        assertEquals("Ana", m.kasir, "ostao je jedan kasir")

        m.izaberiPapir(SirinaPapira.MM58)
        assertEquals(SirinaPapira.MM58, sacuvano.papir)

        assertEquals("Adresa mora da počinje sa http:// ili https://; Adresa mora da se završava sa /api", m.sacuvajLpfr("ftp://x"))
        assertNull(m.sacuvajLpfr(" http://192.168.1.20:7555/api "))
        m.poslednjiPosao?.join()
        assertEquals("http://192.168.1.20:7555/api", sacuvano.lpfr?.baseUrl)

        assertEquals("Sertifikat nije upisan; Lozinka nije upisan; PAC nije upisan", m.sacuvajVpfr(null, "", ""))
        assertEquals(
            "Sertifikat ne može da se otvori: lozinka nije dobra ili fajl nije .p12/.pfx",
            m.sacuvajVpfr("config/test.p12", "losa", "ABC123"),
        )
        assertNull(m.sacuvajVpfr("config/test.p12", "dobra", " ABC123 "))
        assertEquals("ABC123", sacuvano.vpfr?.pac)
        assertEquals("V-PFR je sačuvan: UID TEST1234 · PIB 115423827", m.porukaPodesavanja?.tekst)

        m.izaberiPfr(VrstaPfra.VPFR)
        assertEquals(VrstaPfra.VPFR, sacuvano.pfr)
        assertEquals(VrstaPfra.VPFR, EsirModel(okruzenje, this).vrstaPfra, "posle ponovnog pokretanja isti PFR")
        m.zatvori()
    }

    /** Prvo stanje PFR-a, pa jedna stavka na računu. */
    private suspend fun EsirModel.spremi(naziv: String = "Kafa", cena: String = "300") {
        if (status == null) {
            osvezi()
            poslednjiPosao!!.join()
        }
        assertNull(dodajStavku(naziv, "1", cena, "A", ""))
    }

    @Test
    fun prometBezNaknade() = runTest {
        val m = EsirModel(okruzenje, this)
        m.spremi()
        m.posebanSlucaj = PosebanSlucaj.BEZ_NAKNADE
        m.izdaj()
        m.poslednjiPosao!!.join()
        val r = poslatiRacuni().single()
        assertEquals("Normal", r.tekst("invoiceType"))
        assertEquals("00: Kafa", r["items"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("Other", r["payment"]!!.jsonArray.single().jsonObject["paymentType"]!!.jsonPrimitive.content)
        assertEquals(PosebanSlucaj.NE, m.posebanSlucaj, "sledeći račun je opet običan")
    }

    @Test
    fun korporativnaKarticaIMesecnaRefundacija() = runTest {
        val m = EsirModel(okruzenje, this)
        m.spremi()
        m.posebanSlucaj = PosebanSlucaj.KORPORATIVNA
        m.korporativniPib = "12345"
        m.korporativnaKartica = "6666666"
        m.izdaj()
        assertEquals("PIB kupca mora imati 9 cifara", m.poruka?.tekst)

        m.korporativniPib = "123456789"
        m.izdaj()
        m.poslednjiPosao!!.join()
        val prodaja = poslatiRacuni().single()
        assertEquals("10:123456789", prodaja.tekst("buyerId"))
        assertEquals("50:6666666", prodaja.tekst("buyerCostCenterId"))
        assertEquals("Voucher", prodaja["payment"]!!.jsonArray.single().jsonObject["paymentType"]!!.jsonPrimitive.content)

        m.spremi(cena = "500")
        m.posebanSlucaj = PosebanSlucaj.KORPORATIVNA
        m.korporativnaKartica = "7777777"
        m.izdaj()
        m.poslednjiPosao!!.join()

        assertEquals("2026-11", m.ovajMesec())
        val pregled = m.pregledMesecneRefundacije("123456789", "2026-11").getOrThrow()
        assertEquals(2, pregled.racuni.size)
        assertEquals("800", pregled.iznos.toString())

        assertNull(m.izdajMesecnuRefundaciju("123456789", "2026-11"))
        m.poslednjiPosao!!.join()
        val zbirna = poslatiRacuni().last()
        assertEquals("Refund", zbirna.tekst("transactionType"))
        assertEquals("60:01112026_30112026", zbirna.tekst("buyerCostCenterId"))
        assertEquals("TEST1234-TEST1234-2", zbirna.tekst("referentDocumentNumber"), "poziv na poslednji račun u mesecu")
        assertEquals("800", zbirna["payment"]!!.jsonArray.single().jsonObject["amount"].toString())

        assertEquals(
            "Zbirna refundacija za 2026-11-01 – 2026-11-30 je već izdata: TEST1234-TEST1234-3",
            m.izdajMesecnuRefundaciju("123456789", "2026-11"),
        )
        val prvaProdaja = assertNotNull(okruzenje.zurnal.nadji("TEST1234-TEST1234-1"))
        assertEquals("TEST1234-TEST1234-3", m.zbirnaRefundacijaZa(prvaProdaja)?.racun?.invoiceNumber, "obuhvata i račun na koji se ne poziva")
        assertEquals("Račun je obuhvaćen zbirnom mesečnom refundacijom TEST1234-TEST1234-3", m.refundiraj(prvaProdaja, "10", "123456789"))
        assertEquals("Račun je obuhvaćen zbirnom mesečnom refundacijom TEST1234-TEST1234-3", m.ponisti(prvaProdaja))
        assertEquals(3, poslatiRacuni().size)
        assertEquals("Nema prodaja korporacijskom karticom kupcu 123456789 od 2026-10-01 do 2026-10-31", m.izdajMesecnuRefundaciju("123456789", "2026-10"))
    }

    @Test
    fun vaucerKaoStavka() = runTest {
        val m = EsirModel(okruzenje, this)
        m.osvezi()
        m.poslednjiPosao!!.join()
        assertEquals("Izaberi poresku oznaku", m.dodajVaucer("1000", null))
        assertNull(m.dodajVaucer("1000", "A"))
        assertEquals("Na računu već postoji vaučer sa oznakom A (najviše jedan po poreskoj stopi)", m.dodajVaucer("500", "A"))
        assertEquals("Iznos vaučera može da ima najviše 2 decimale", m.dodajVaucer("1,555", "B"))
        assertNull(m.dodajVaucer("500", "B"), "druga stopa može")
        assertEquals(listOf("20: Једнонаменски ваучер (A)", "20: Једнонаменски ваучер (B)"), m.stavke.map { it.name })
        m.vrstaRacuna = VrstaRacuna.PROMET
        m.izdaj()
        m.poslednjiPosao!!.join()
        assertEquals("1500", poslatiRacuni().single()["payment"]!!.jsonArray.single().jsonObject["amount"].toString())
    }

    @Test
    fun ponistavanjeNaSopstveniPib() = runTest {
        val m = EsirModel(okruzenje, this)
        val obuka = m.izdajProbni(VrstaRacuna.OBUKA)
        assertEquals("Poništava se Promet Prodaja ili Avans Prodaja, a ovo je Obuka Prodaja", m.ponisti(obuka))

        val promet = m.izdajProbni(VrstaRacuna.PROMET)
        assertNull(m.ponisti(promet))
        m.poslednjiPosao!!.join()
        val ponistavanje = poslatiRacuni().last()
        assertEquals("Normal", ponistavanje.tekst("invoiceType"))
        assertEquals("Refund", ponistavanje.tekst("transactionType"))
        assertEquals("10:115423827", ponistavanje.tekst("buyerId"), "sopstveni PIB iz odgovora PFR-a")
        assertEquals(promet.racun.invoiceNumber, ponistavanje.tekst("referentDocumentNumber"))
        assertEquals(3, poslatiRacuni().size, "bez kopije za potpis: novac se ne vraća")
        assertTrue("je poništen; izdaj novi, ispravan račun" in m.poruka!!.tekst, m.poruka!!.tekst)

        assertEquals("Račun je već refundiran (TEST1234-TEST1234-3); poništavanje nije moguće", m.ponisti(promet))
        assertEquals(listOf("0"), m.preostaloZaRefundaciju(promet).map { it.toString() }, "poništen račun se više ne refundira")
    }

    private fun JsonObject.iznos(i: Int = 0) = this["payment"]!!.jsonArray[i].jsonObject["amount"].toString()
    private fun JsonObject.nacin(i: Int = 0) = this["payment"]!!.jsonArray[i].jsonObject["paymentType"]!!.jsonPrimitive.content

    @Test
    fun avansniPosaoOdPrvogAvansaDoKonacnogRacuna() = runTest {
        val m = EsirModel(okruzenje, this)
        m.osvezi()
        m.poslednjiPosao!!.join()
        m.vrstaRacuna = VrstaRacuna.AVANS
        m.izdaj()
        assertEquals("Unesi iznos avansa", m.poruka?.tekst)

        m.avansIznos = "1000"
        m.avansOznaka = "A"
        assertEquals(SifraAvansa.POSEBNA_STOPA, m.sifraAvansa(), "A = 10% → šifra 11")
        m.nacinPlacanja = NacinPlacanja.PRENOS_NA_RACUN
        m.sifraKupca = "10"
        m.brojKupca = "123456789"
        m.izdaj()
        m.poslednjiPosao!!.join()
        val prvi = poslatiRacuni().single()
        assertEquals("Advance", prvi.tekst("invoiceType"))
        assertEquals("Sale", prvi.tekst("transactionType"))
        assertEquals("11: Аванс (A)", prvi["items"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("1000", prvi.iznos())
        assertEquals("WireTransfer", prvi.nacin())
        assertEquals("10:123456789", prvi.tekst("buyerId"))

        val ap1 = assertNotNull(okruzenje.zurnal.nadji("TEST1234-TEST1234-1"))
        m.zapocniSledeciAvans(assertNotNull(m.avansniPosao(ap1)))
        assertEquals(ap1, m.prethodniAvans)
        assertEquals("123456789", m.brojKupca, "kupac iz posla")
        m.avansIznos = "500"
        m.avansDatumUplate = "2026-11-19"
        m.izdaj()
        assertEquals("Datum uplate ne može biti posle današnjeg dana", m.poruka?.tekst)
        m.avansDatumUplate = "2026-11-10"
        m.nacinPlacanja = NacinPlacanja.GOTOVINA
        m.primljeno = "600"
        m.izdaj()
        m.poslednjiPosao!!.join()
        val drugi = poslatiRacuni().last()
        assertEquals("TEST1234-TEST1234-1", drugi.tekst("referentDocumentNumber"))
        assertEquals("2026-11-10T00:00:00", drugi.tekst("dateAndTimeOfIssue"), "9.2: ESIR vreme = datum uplate")
        assertEquals("500", drugi.iznos(), "u kasi ostaje 500, kusur 100")
        assertNull(m.prethodniAvans)

        val ap2 = assertNotNull(okruzenje.zurnal.nadji("TEST1234-TEST1234-2"))
        assertEquals("1500", assertNotNull(m.avansniPosao(ap1)).ukupno.toString())
        assertEquals("Dodaj stavke konačnog računa (levo), po punoj ceni", m.izdajKonacniRacun(ap1, NacinPlacanja.PLATNA_KARTICA))
        assertNull(m.dodajStavku("Najam vozila /dan", "1", "1000", "A", ""))
        assertTrue(m.izdajKonacniRacun(ap1, NacinPlacanja.PLATNA_KARTICA)!!.startsWith("Avansi (1.500,00) su veći od računa (1.000,00)"))
        assertNull(m.dodajStavku("Dodatno osiguranje /dan", "2", "1000", "A", ""))

        assertNull(m.izdajKonacniRacun(ap1, NacinPlacanja.PLATNA_KARTICA))
        m.poslednjiPosao!!.join()
        val (zatvaranje, konacni) = poslatiRacuni().takeLast(2)
        assertEquals("Advance", zatvaranje.tekst("invoiceType"))
        assertEquals("Refund", zatvaranje.tekst("transactionType"))
        assertEquals("TEST1234-TEST1234-2", zatvaranje.tekst("referentDocumentNumber"), "poziv na poslednji avans")
        assertEquals("1500", zatvaranje["items"]!!.jsonArray.single().jsonObject["totalAmount"].toString())
        assertEquals(listOf("WireTransfer" to "1000", "Cash" to "500"), (0..1).map { zatvaranje.nacin(it) to zatvaranje.iznos(it) })
        assertEquals("Normal", konacni.tekst("invoiceType"))
        assertEquals("TEST1234-TEST1234-3", konacni.tekst("referentDocumentNumber"), "konačni se poziva na zatvaranje")
        assertEquals("1500", konacni.iznos(), "PFR-u ide samo ostatak: 3000 − 1500")
        assertEquals("Card", konacni.nacin())
        assertEquals(2, konacni["items"]!!.jsonArray.size)
        assertTrue(m.stavke.isEmpty())

        assertEquals("TEST1234-TEST1234-4", m.avansniPosao(ap2)?.konacni?.racun?.invoiceNumber)
        assertEquals("Konačni račun je već izdat: TEST1234-TEST1234-4", m.izdajKonacniRacun(ap1, NacinPlacanja.PLATNA_KARTICA))
        assertEquals("Poništava se samo poslednji avans posla koji nije zatvoren", m.ponisti(ap2))
        val konacniRacun = assertNotNull(okruzenje.zurnal.nadji("TEST1234-TEST1234-4"))
        assertTrue(m.jeKonacniPosleAvansa(konacniRacun))
        assertEquals("Konačni račun posle avansa se za sada ne refundira iz aplikacije", m.refundiraj(konacniRacun, "20", "1"))
        assertEquals("Konačni račun posle avansa se za sada ne poništava iz aplikacije", m.ponisti(konacniRacun))
        assertEquals(4, poslatiRacuni().size, "ništa više nije poslato")
    }

    @Test
    fun konacniRacunKadaJeZatvaranjeVecIzdato() = runTest {
        val m = EsirModel(okruzenje, this)
        m.osvezi()
        m.poslednjiPosao!!.join()
        m.vrstaRacuna = VrstaRacuna.AVANS
        m.avansIznos = "1000"
        m.avansOznaka = "A"
        m.nacinPlacanja = NacinPlacanja.PRENOS_NA_RACUN
        m.izdaj()
        m.poslednjiPosao!!.join()
        val ap = assertNotNull(okruzenje.zurnal.nadji("TEST1234-TEST1234-1"))
        assertEquals("Unesi kupca (levo, „Kupac“): zatvaranje avansa traži ID kupca", m.run {
            assertNull(dodajStavku("Najam", "1", "1000", "A", ""))
            izdajKonacniRacun(ap, NacinPlacanja.GOTOVINA)
        })

        // Zatvaranje izdato ranije (npr. konačni račun nije prošao): sada se izdaje samo konačni.
        val ar = rs.omniit.hospes.esir.IzdatRacun(
            "x",
            Avans.zahtevZaZatvaranje(listOf(ap), idKupca = "20:1"),
            rs.omniit.hospes.esir.FiskalizovanRacun("TEST1234-TEST1234-50", "2026-11-18T14:00:00+01:00"),
        )
        okruzenje.zurnal.sacuvaj(ar)
        val posao = assertNotNull(m.avansniPosao(ar))
        assertTrue(posao.mozeKonacni && !posao.mozeSledeciAvans)
        assertNull(m.izdajKonacniRacun(ar, NacinPlacanja.GOTOVINA, primljeno = "500"))
        m.poslednjiPosao!!.join()
        assertEquals(2, poslatiRacuni().size, "samo konačni račun")
        val konacni = poslatiRacuni().last()
        assertEquals("TEST1234-TEST1234-50", konacni.tekst("referentDocumentNumber"))
        assertEquals("0", konacni.iznos(), "avans pokriva ceo račun: plaćanje 0")
    }

    @Test
    fun konacniRacunSeIzdajeLevoJednimDugmetom() = runTest {
        val m = EsirModel(okruzenje, this)
        m.osvezi()
        m.poslednjiPosao!!.join()
        m.vrstaRacuna = VrstaRacuna.AVANS
        m.avansIznos = "500"
        m.avansOznaka = "A"
        m.avansDatumUplate = "2026-11-18" // danas: ESIR vreme se ne šalje
        m.nacinPlacanja = NacinPlacanja.GOTOVINA
        m.izdaj()
        m.poslednjiPosao!!.join()
        val ap = assertNotNull(okruzenje.zurnal.nadji("TEST1234-TEST1234-1"))

        m.zapocniKonacni(assertNotNull(m.avansniPosao(ap)))
        assertEquals(VrstaRacuna.PROMET, m.vrstaRacuna)
        assertNull(m.kupacSaAvansa, "avans je bez kupca")
        assertNull(m.dodajStavku("najam vozila", "1", "2000", "A", ""))
        assertEquals("1500", m.ostatakPosleAvansa.toString())
        assertNull(poslatiRacuni().single()["dateAndTimeOfIssue"]?.takeUnless { it is kotlinx.serialization.json.JsonNull })

        m.sifraKupca = "10"
        m.brojKupca = "888877"
        m.izdaj()
        assertEquals("PIB ima tačno 9 cifara", m.poruka?.tekst)
        assertEquals(1, poslatiRacuni().size, "ništa nije poslato")

        m.sifraKupca = "20"
        m.brojKupca = "123456789"
        m.primljeno = "2000"
        m.izdaj()
        m.poslednjiPosao!!.join()
        val (zatvaranje, konacni) = poslatiRacuni().takeLast(2)
        assertEquals("Refund", zatvaranje.tekst("transactionType"))
        assertEquals("20:123456789", zatvaranje.tekst("buyerId"))
        assertEquals("1500", konacni.iznos(), "u kasi ostaje ostatak, kusur 500")
        assertEquals("Cash", konacni.nacin())
        assertNull(m.konacniPosao, "posle izdavanja se vraća običan račun")
        assertTrue(m.stavke.isEmpty())
        assertEquals("", m.brojKupca)
    }

    @Test
    fun podeljenoPlacanjeSaKusurom() = runTest {
        val m = EsirModel(okruzenje, this)
        m.spremi("Kafa", "675")
        m.vrstaRacuna = VrstaRacuna.PROMET
        m.dodajDeoPlacanja()
        assertEquals(NacinPlacanja.PLATNA_KARTICA, m.dodatnaPlacanja.single().nacin)
        m.izdaj()
        assertEquals("Upiši iznos za „Platna kartica“", m.poruka?.tekst)

        m.promeniDeoPlacanja(0, DeoPlacanja(NacinPlacanja.PLATNA_KARTICA, "500"))
        m.nacinPlacanja = NacinPlacanja.GOTOVINA
        assertEquals("175", m.ostatakNaplate(m.ukupno).toString())
        m.primljeno = "200"
        assertEquals("25", m.kusur.toString())
        m.izdaj()
        m.poslednjiPosao!!.join()
        val r = poslatiRacuni().single()
        assertEquals(listOf("Card" to "500", "Cash" to "175"), (0..1).map { r.nacin(it) to r.iznos(it) })
        assertEquals(listOf("Повраћај:                          25,00"), dodaci["TEST1234-TEST1234-1"])
        assertTrue(m.dodatnaPlacanja.isEmpty(), "posle izdavanja: jedno plaćanje")
    }

    @Test
    fun sviNaciniPlacanjaIOgranicenje() = runTest {
        val m = EsirModel(okruzenje, this)
        assertEquals(7, m.dozvoljeniNacini.size)
        m.spremi()
        m.vrstaRacuna = VrstaRacuna.PROMET
        m.nacinPlacanja = NacinPlacanja.CEK
        m.izdaj()
        m.poslednjiPosao!!.join()
        assertEquals("Check", poslatiRacuni().last().nacin())

        m.nacinPlacanja = NacinPlacanja.PLATNA_KARTICA
        m.postaviOgranicenje(true)
        assertEquals(OgranicenjePlacanja.BEZ_KARTICE_CEKA_I_INSTANTA, sacuvano.ogranicenjePlacanja)
        assertEquals(
            listOf(NacinPlacanja.GOTOVINA, NacinPlacanja.PRENOS_NA_RACUN, NacinPlacanja.VAUCER, NacinPlacanja.DRUGO_BEZGOTOVINSKO),
            m.dozvoljeniNacini,
        )
        assertEquals(NacinPlacanja.GOTOVINA, m.nacinPlacanja, "kartica nije dozvoljena: gotovina")
        m.postaviOgranicenje(false)
        assertNull(sacuvano.ogranicenjePlacanja)
    }

    @Test
    fun opcionoPoljeKupca() = runTest {
        val m = EsirModel(okruzenje, this)
        m.spremi()
        m.sifraOpcionog = "20"
        m.brojOpcionog = "12345"
        m.izdaj()
        assertEquals("Opciono polje kupca traži i ID kupca", m.poruka?.tekst)
        m.sifraKupca = "10"
        m.brojKupca = "123456789"
        m.izdaj()
        m.poslednjiPosao!!.join()
        assertEquals("20:12345", poslatiRacuni().single().tekst("buyerCostCenterId"))
        assertEquals("", m.brojOpcionog)
    }

    @Test
    fun tekstIspodRacunaISerijskiBroj() = runTest {
        val m = EsirModel(okruzenje, this)
        val serijski = assertNotNull(sacuvano.serijskiBroj, "dodeljen pri prvom pokretanju")
        assertEquals(serijski, m.serijskiBroj)
        assertEquals(serijski, EsirModel(okruzenje, this).serijskiBroj, "ne menja se")

        assertEquals("Red može da ima najviše 40 znakova (koliko staje na papir)", m.sacuvajTekstIspodRacuna("X".repeat(41)))
        assertNull(m.sacuvajTekstIspodRacuna("Hvala na poseti!\nwww.hospes.rs\n"))
        assertEquals("Hvala na poseti!\nwww.hospes.rs", sacuvano.tekstIspodRacuna)

        m.spremi()
        m.primljeno = "500"
        m.izdaj()
        m.poslednjiPosao!!.join()
        assertEquals(
            listOf("Повраћај:                         200,00", "", "Hvala na poseti!", "www.hospes.rs"),
            dodaci["TEST1234-TEST1234-1"],
        )
    }
}
