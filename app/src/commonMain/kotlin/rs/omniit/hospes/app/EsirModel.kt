package rs.omniit.hospes.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rs.omniit.hospes.esir.Artikal
import rs.omniit.hospes.esir.Decimal4
import rs.omniit.hospes.esir.EsirGreska
import rs.omniit.hospes.esir.FilterZurnala
import rs.omniit.hospes.esir.IzdatRacun
import rs.omniit.hospes.esir.KatalogCsv
import rs.omniit.hospes.esir.LpfrVeza
import rs.omniit.hospes.esir.NacinPlacanja
import rs.omniit.hospes.esir.Obracun
import rs.omniit.hospes.esir.OtvorenPfr
import rs.omniit.hospes.esir.PfrVeza
import rs.omniit.hospes.esir.PoreskaStopa
import rs.omniit.hospes.esir.RezultatPina
import rs.omniit.hospes.esir.RezultatRacuna
import rs.omniit.hospes.esir.SifreStanja
import rs.omniit.hospes.esir.StatusOdgovor
import rs.omniit.hospes.esir.Stavka
import rs.omniit.hospes.esir.TipTransakcije
import rs.omniit.hospes.esir.VrstaPfra
import rs.omniit.hospes.esir.VrstaRacuna
import rs.omniit.hospes.esir.ZahtevZaRacun
import rs.omniit.hospes.esir.noviRequestId
import rs.omniit.hospes.esir.poSifriIliGtinu
import rs.omniit.hospes.esir.uvezi

/** Poruka korisniku ispod obračuna. */
data class Poruka(val tekst: String, val greska: Boolean)

/** Račun prikazan desno: tekst (PFR + ESIR dodatak) i QR kod. */
data class PrikazRacuna(val naslov: String, val tekst: String, val qr: ByteArray?)

/**
 * Stanje i radnje ESIR test ekrana. Sva fiskalna pravila su u ESIR modulu; ovde je samo povezivanje sa ekranom.
 */
class EsirModel(private val okruzenje: Okruzenje, private val scope: CoroutineScope) {

    var vrstaPfra by mutableStateOf(VrstaPfra.LPFR)
        private set
    var opisPfra by mutableStateOf<String?>(null)
        private set
    var dostupan by mutableStateOf<Boolean?>(null)
        private set
    var status by mutableStateOf<StatusOdgovor?>(null)
        private set
    var radi by mutableStateOf(false)
        private set
    var poruka by mutableStateOf<Poruka?>(null)
        private set

    val stavke = mutableStateListOf<Stavka>()
    var vrstaRacuna by mutableStateOf(VrstaRacuna.OBUKA)
    var nacinPlacanja by mutableStateOf(NacinPlacanja.GOTOVINA)
    var primljeno by mutableStateOf("")

    var prikazan by mutableStateOf<PrikazRacuna?>(null)
        private set
    var zurnal by mutableStateOf<List<IzdatRacun>>(emptyList())
        private set

    /** Katalog artikala (kopija za ekran; menja se samo kroz radnje ispod). */
    var artikli by mutableStateOf<List<Artikal>>(emptyList())
        private set
    var porukaKataloga by mutableStateOf<Poruka?>(null)
        private set

    private var otvoren: OtvorenPfr? = null

    /** Poslednji započeti rad sa PFR-om (za testove: sačekati da se završi). */
    internal var poslednjiPosao: Job? = null
        private set

    val ukupno: Decimal4 get() = Obracun.ukupno(stavke)

    /** Poreske oznake koje je PFR vratio; ESIR sme da koristi samo njih. */
    val stope: List<PoreskaStopa> get() = status?.currentTaxRates?.taxCategories.orEmpty().flatMap { it.taxRates }

    val sifreStanja: List<String>
        get() = status?.gsc.orEmpty().map { "$it ${SifreStanja.za(it).opis}" }

    fun izaberiPfr(vrsta: VrstaPfra) {
        if (vrsta == vrstaPfra || radi) return
        zatvori()
        vrstaPfra = vrsta
        status = null
        dostupan = null
        osvezi()
    }

    fun osvezi() = posao { veza ->
        val javlja = veza.attention()
        dostupan = javlja
        status = if (javlja) veza.status() else null
        if (!javlja) poruka = Poruka("${veza.naziv} se ne javlja", greska = true)
    }

    fun posaljiPin() = posao { veza ->
        val lpfr = veza as? LpfrVeza ?: return@posao run { poruka = Poruka("V-PFR nema PIN", greska = true) }
        val pin = okruzenje.pin ?: return@posao run { poruka = Poruka("PIN nije upisan u config/local.json", greska = true) }
        poruka = when (val r = lpfr.unesiPin(pin)) {
            RezultatPina.Prihvacen -> Poruka("PIN je prihvaćen (0100)", greska = false)
            RezultatPina.NijePotreban -> Poruka("L-PFR već ima ispravan PIN", greska = false)
            is RezultatPina.NijePoslat -> Poruka("PIN nije poslat: ${r.razlog}", greska = true)
            is RezultatPina.Odbijen -> Poruka("PIN nije prihvaćen: ${r.sifra.kod} ${r.sifra.opis}. Ne pokušava se ponovo.", greska = true)
        }
        status = veza.status()
    }

    /** Dodaje stavku iz unetih polja; vraća poruku greške ili `null` ako je stavka dodata. */
    fun dodajStavku(naziv: String, kolicina: String, cena: String, oznaka: String?, popust: String): String? {
        if (naziv.isBlank()) return "Unesi naziv"
        if (oznaka == null) return "Izaberi poresku oznaku"
        return try {
            stavke += Obracun.stavka(
                naziv = naziv.trim(),
                kolicina = broj(kolicina.ifBlank { "1" }),
                jedinicnaCena = broj(cena),
                oznake = listOf(oznaka),
                popustProcenat = if (popust.isBlank()) Decimal4.NULA else broj(popust),
            )
            null
        } catch (e: IllegalArgumentException) {
            e.message
        }
    }

    /**
     * Dodaje artikal iz kataloga na račun; isti artikal po istoj ceni samo povećava količinu.
     * Poreska oznaka artikla mora postojati među stopama koje je vratio PFR. Vraća grešku ili `null`.
     */
    fun dodajArtikal(artikal: Artikal, kolicina: String = "1"): String? {
        val oznake = stope.map { it.label }
        if (oznake.isEmpty()) return "Stanje PFR-a još nije učitano, pa se ne znaju poreske oznake"
        if (artikal.oznaka !in oznake) {
            return "Artikal ${artikal.sifra} ima oznaku ${artikal.oznaka}, a PFR je ne vraća (dostupne: ${oznake.joinToString()}). Promeni oznaku u katalogu."
        }
        return try {
            val kol = broj(kolicina.ifBlank { "1" })
            val i = stavke.indexOfFirst {
                it.name == artikal.nazivNaRacunu && it.unitPrice == artikal.cena && it.labels == listOf(artikal.oznaka) && it.gtin == artikal.gtin
            }
            if (i >= 0) stavke[i] = artikal.uStavku(stavke[i].quantity + kol) else stavke += artikal.uStavku(kol)
            null
        } catch (e: IllegalArgumentException) {
            e.message
        }
    }

    /** Šifra ili bar-kod (GTIN) iz čitača ili ukucan: dodaje tačno taj artikal. */
    fun dodajPoKodu(kod: String): String? {
        val artikal = okruzenje.katalog.poSifriIliGtinu(kod.trim())
            ?: return "U katalogu nema artikla sa šifrom ili bar-kodom „${kod.trim()}“"
        return dodajArtikal(artikal)
    }

    fun ucitajKatalog() {
        artikli = okruzenje.katalog.svi()
    }

    /**
     * Čuva novi artikal ili izmenu postojećeg (tu se menja i cena, ESIR upitnik deo 11, P1 i P3).
     * [novi] sprečava da se novim artiklom slučajno pregazi postojeći sa istom šifrom. Vraća grešku ili `null`.
     */
    fun sacuvajArtikal(
        novi: Boolean,
        sifra: String,
        naziv: String,
        jedinicaMere: String,
        cena: String,
        oznaka: String?,
        gtin: String,
        kategorija: String,
    ): String? {
        if (oznaka == null) return "Izaberi poresku oznaku"
        if (novi && okruzenje.katalog.nadji(sifra.trim()) != null) return "Šifra ${sifra.trim()} već postoji; izmeni taj artikal u listi"
        return try {
            okruzenje.katalog.sacuvaj(
                Artikal(
                    sifra = sifra.trim(),
                    naziv = naziv.trim(),
                    cena = broj(cena),
                    oznaka = oznaka,
                    jedinicaMere = jedinicaMere.trim().ifEmpty { "kom" },
                    gtin = gtin.trim().ifEmpty { null },
                    kategorija = kategorija.trim().ifEmpty { null },
                ),
            )
            ucitajKatalog()
            porukaKataloga = Poruka("Artikal ${sifra.trim()} je sačuvan", greska = false)
            null
        } catch (e: IllegalArgumentException) {
            e.message ?: "Neispravan artikal"
        }
    }

    fun ukloniArtikal(sifra: String) {
        if (okruzenje.katalog.ukloni(sifra)) porukaKataloga = Poruka("Artikal $sifra je uklonjen iz kataloga", greska = false)
        ucitajKatalog()
    }

    /** Uvoz kataloga iz CSV teksta (ESIR upitnik deo 11, P6): novi artikli se dodaju, postojeće šifre menjaju. */
    fun uveziKatalog(tekst: String) {
        val uvoz = KatalogCsv.uvoz(tekst)
        porukaKataloga = try {
            val broj = okruzenje.katalog.uvezi(uvoz)
            val preskoceno = if (uvoz.greske.isEmpty()) "" else " Preskočeno ${uvoz.greske.size}: ${uvoz.greske.joinToString("; ")}"
            Poruka("Uvezeno $broj artikala.$preskoceno", greska = uvoz.greske.isNotEmpty())
        } catch (e: IllegalArgumentException) {
            Poruka(e.message ?: "Uvoz nije uspeo", greska = true)
        }
        ucitajKatalog()
    }

    fun uveziIzFajla() {
        okruzenje.procitajCsv()?.let(::uveziKatalog)
    }

    fun izveziUFajl() {
        val artikli = okruzenje.katalog.svi()
        val putanja = okruzenje.sacuvajCsv("katalog.csv", KatalogCsv.izvoz(artikli)) ?: return
        porukaKataloga = Poruka("Izvezeno ${artikli.size} artikala u $putanja", greska = false)
    }

    fun ukloniStavku(indeks: Int) {
        if (indeks in stavke.indices) stavke.removeAt(indeks)
    }

    /** Kusur ako je plaćanje gotovinom i kupac je dao više, inače `null`. */
    val kusur: Decimal4?
        get() = runCatching {
            val dato = primljeno.takeIf { it.isNotBlank() && nacinPlacanja == NacinPlacanja.GOTOVINA }?.let(::broj)
                ?: return null
            Obracun.naplata(ukupno, listOf(nacinPlacanja to null), dato).kusur
        }.getOrNull()

    fun izdaj() {
        if (stavke.isEmpty()) {
            poruka = Poruka("Račun nema stavki", greska = true)
            return
        }
        val naplata = try {
            val dato = primljeno.takeIf { it.isNotBlank() && nacinPlacanja == NacinPlacanja.GOTOVINA }?.let(::broj)
            Obracun.naplata(ukupno, listOf(nacinPlacanja to null), dato)
        } catch (e: IllegalArgumentException) {
            poruka = Poruka(e.message ?: "Neispravna naplata", greska = true)
            return
        }
        val zahtev = ZahtevZaRacun(
            invoiceType = vrstaRacuna,
            transactionType = TipTransakcije.PRODAJA,
            payment = naplata.placanja,
            items = stavke.toList(),
            cashier = "Hospes test",
        )
        val dodatak = if (naplata.kusur > Decimal4.NULA) listOf("Повраћај:" + naplata.kusur.zaStampu().padStart(31)) else emptyList()

        posao { veza ->
            val jezik = PfrVeza.JEZIK_SRPSKI_CIRILICA.takeIf { it in status?.supportedLanguages.orEmpty() }
                ?: status?.supportedLanguages?.firstOrNull()
            val requestId = noviRequestId()
            poruka = when (val r = veza.izdajRacun(zahtev, requestId, jezik)) {
                is RezultatRacuna.Izdat -> {
                    val izdat = IzdatRacun(requestId, zahtev, r.racun)
                    okruzenje.zurnal.sacuvaj(izdat, dodatak)
                    prikazi(izdat)
                    stavke.clear()
                    primljeno = ""
                    ucitajZurnal()
                    val naknadno = if (r.pronadjenNaknadno) " (pronađen po RequestId posle prekida veze)" else ""
                    Poruka("Račun ${r.racun.invoiceNumber} je izdat$naknadno", greska = false)
                }
                is RezultatRacuna.Odbijen -> {
                    val sifre = r.greska?.modelState.orEmpty().flatMap { p -> p.errors.map { "${p.property}: $it ${SifreStanja.za(it).opis}" } }
                    Poruka("${veza.naziv} je odbio račun (HTTP ${r.httpStatus}). ${sifre.joinToString("; ")}", greska = true)
                }
                is RezultatRacuna.NijeIzdat -> Poruka("Račun NIJE izdat: ${r.razlog}", greska = true)
                is RezultatRacuna.NepoznatoStanje ->
                    Poruka("NE ZNA SE da li je račun izdat (RequestId ${r.requestId}). Ne izdaji ponovo pre provere.", greska = true)
            }
        }
    }

    /** Učitava žurnal za poslednjih 30 dana; ako nijedan račun nije prikazan, prikazuje poslednji izdati. */
    fun ucitajZurnal() {
        zurnal = okruzenje.zurnal.pretrazi(FilterZurnala(od = okruzenje.pre30Dana()))
        if (prikazan == null) zurnal.firstOrNull()?.let(::prikazi)
    }

    fun prikazi(izdat: IzdatRacun) {
        val broj = izdat.racun.invoiceNumber
        prikazan = PrikazRacuna(
            naslov = "$broj · ${izdat.oznaka}",
            tekst = okruzenje.tekstRacuna(broj) ?: izdat.racun.journal ?: "(nema teksta računa)",
            qr = izdat.racun.verificationQRCode?.let(::dekodirajBase64),
        )
    }

    fun zatvori() {
        otvoren?.close()
        otvoren = null
        opisPfra = null
    }

    /** Izvršava rad sa PFR-om jedan po jedan; greške pretvara u poruku. */
    private fun posao(blok: suspend (PfrVeza) -> Unit) {
        if (radi) return
        radi = true
        poruka = null
        poslednjiPosao = scope.launch {
            try {
                val o = otvoren ?: withContext(Dispatchers.IO) { okruzenje.otvoriPfr(vrstaPfra) }.also {
                    otvoren = it
                    opisPfra = it.opis
                }
                blok(o.veza)
            } catch (e: EsirGreska) {
                poruka = Poruka(e.message ?: e.toString(), greska = true)
            } catch (e: IllegalArgumentException) {
                poruka = Poruka(e.message ?: e.toString(), greska = true)
            } finally {
                radi = false
            }
        }
    }

    private fun broj(tekst: String): Decimal4 = Decimal4.parse(tekst.trim().replace(',', '.'))
}

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private fun dekodirajBase64(tekst: String): ByteArray? = runCatching { kotlin.io.encoding.Base64.decode(tekst) }.getOrNull()
