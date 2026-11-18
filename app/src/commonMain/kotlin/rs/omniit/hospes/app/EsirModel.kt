package rs.omniit.hospes.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rs.omniit.hospes.esir.Artikal
import rs.omniit.hospes.esir.Avans
import rs.omniit.hospes.esir.AvansnaUplata
import rs.omniit.hospes.esir.AvansniPosao
import rs.omniit.hospes.esir.SifraAvansa
import rs.omniit.hospes.esir.Decimal4
import rs.omniit.hospes.esir.Esir
import rs.omniit.hospes.esir.EsirGreska
import rs.omniit.hospes.esir.FilterZurnala
import rs.omniit.hospes.esir.IzdatRacun
import rs.omniit.hospes.esir.PrometBezNaknade
import rs.omniit.hospes.esir.KorporativnaKartica
import rs.omniit.hospes.esir.JednonamenskiVaucer
import rs.omniit.hospes.esir.VpfrPodesavanja
import rs.omniit.hospes.esir.Podesavanja
import rs.omniit.hospes.esir.LpfrPodesavanja
import rs.omniit.hospes.esir.Kasiri
import rs.omniit.hospes.esir.KatalogCsv
import rs.omniit.hospes.esir.LpfrVeza
import rs.omniit.hospes.esir.NacinPlacanja
import rs.omniit.hospes.esir.Obracun
import rs.omniit.hospes.esir.naziv
import rs.omniit.hospes.esir.noviSerijskiBroj
import rs.omniit.hospes.esir.Otisak
import rs.omniit.hospes.esir.OgranicenjePlacanja
import rs.omniit.hospes.esir.Naplata
import rs.omniit.hospes.esir.OtvorenPfr
import rs.omniit.hospes.esir.PfrVeza
import rs.omniit.hospes.esir.PoreskaStopa
import rs.omniit.hospes.esir.RezultatPina
import rs.omniit.hospes.esir.RezultatRacuna
import rs.omniit.hospes.esir.SirinaPapira
import rs.omniit.hospes.esir.SifarnikKupca
import rs.omniit.hospes.esir.SifreStanja
import rs.omniit.hospes.esir.StatusOdgovor
import rs.omniit.hospes.esir.Stavka
import rs.omniit.hospes.esir.TipTransakcije
import rs.omniit.hospes.esir.VrstaPfra
import rs.omniit.hospes.esir.VrstaRacuna
import rs.omniit.hospes.esir.ZahtevZaRacun
import rs.omniit.hospes.esir.noviRequestId
import rs.omniit.hospes.esir.poSifriIliGtinu
import rs.omniit.hospes.esir.redZaPotpisKupca
import rs.omniit.hospes.esir.prometIzPredracuna
import rs.omniit.hospes.esir.zahtevZaPonistavanje
import rs.omniit.hospes.esir.avansniPosao
import rs.omniit.hospes.esir.esirVremeZaDatumUplate
import rs.omniit.hospes.esir.datum
import rs.omniit.hospes.esir.mesecnaRefundacijaKartica
import rs.omniit.hospes.esir.refundacijaZa
import rs.omniit.hospes.esir.refundacijeZa
import rs.omniit.hospes.esir.uvezi

/** Posebni slučajevi prodaje (PURS uputstvo, poglavlje 9); uvek se izdaje Promet Prodaja. */
enum class PosebanSlucaj(val naziv: String) {
    NE("Ne"),

    /** 9.7: stavke sa „00: “, plaćanje drugo bezgotovinsko, napomena na računu. */
    BEZ_NAKNADE("Bez naknade"),

    /** 9.4: kupac (PIB) i broj korporacijske kartice, plaćanje uvek vaučer. */
    KORPORATIVNA("Korporativna kartica"),
}

/** Šta bi ušlo u zbirnu mesečnu refundaciju korporacijskih kartica (9.4). */
data class MesecnaRefundacija(val od: String, val doDana: String, val racuni: List<IzdatRacun>, val iznos: Decimal4)

/** Jedan deo podeljenog plaćanja sa zadatim iznosom (npr. 1.000 karticom); ostatak ide na glavni način plaćanja. */
data class DeoPlacanja(val nacin: NacinPlacanja, val iznos: String)

/** Redosled načina plaćanja na ekranu (svih 7 iz „Tehničkog vodiča“). */
val SVI_NACINI_PLACANJA = listOf(
    NacinPlacanja.GOTOVINA,
    NacinPlacanja.PLATNA_KARTICA,
    NacinPlacanja.PRENOS_NA_RACUN,
    NacinPlacanja.INSTANT_PLACANJE,
    NacinPlacanja.CEK,
    NacinPlacanja.VAUCER,
    NacinPlacanja.DRUGO_BEZGOTOVINSKO,
)

/** Šifre opcionog polja kupca koje se unose na običnom računu (50 i 60 su za korporacijske kartice). */
val SIFRE_OPCIONOG_POLJA = listOf("20", "21", "30", "31", "32", "33")

/** Poruka korisniku ispod obračuna. */
data class Poruka(val tekst: String, val greska: Boolean)

/** Račun prikazan desno: izdat račun iz žurnala, tekst (PFR + ESIR dodatak) i QR kod. */
data class PrikazRacuna(val izdat: IzdatRacun, val naslov: String, val tekst: String, val qr: ByteArray?) {
    val broj: String get() = izdat.racun.invoiceNumber
}

/**
 * Stanje i radnje ESIR test ekrana. Sva fiskalna pravila su u ESIR modulu; ovde je samo povezivanje sa ekranom.
 */
class EsirModel(private val okruzenje: Okruzenje, private val scope: CoroutineScope) {

    /** Podešavanja kase; menjaju se samo kroz radnje ispod, koje ih odmah i čuvaju. */
    var podesavanja by mutableStateOf(okruzenje.ucitajPodesavanja())
        private set
    var porukaPodesavanja by mutableStateOf<Poruka?>(null)
        private set

    val folderPodataka: String get() = okruzenje.folderPodataka

    init {
        // Serijski broj instalacije (upitnik: operativne funkcije, pitanje 6) dodeljuje se pri prvom pokretanju.
        if (podesavanja.serijskiBroj == null) sacuvaj(podesavanja.copy(serijskiBroj = noviSerijskiBroj()))
    }

    val serijskiBroj: String get() = podesavanja.serijskiBroj ?: "-"

    /** Kasir koji izdaje račune; ime se štampa na računu. Ako je kasir jedan, izabran je odmah. */
    var kasir by mutableStateOf(podesavanja.kasiri.singleOrNull())
        private set

    var vrstaPfra by mutableStateOf(podesavanja.izabraniPfr)
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
    /** Glavni način plaćanja: dobija ceo iznos, ili ostatak posle [dodatnaPlacanja]. */
    var nacinPlacanja by mutableStateOf(NacinPlacanja.GOTOVINA)
    var primljeno by mutableStateOf("")

    /** Podeljeno plaćanje (upitnik: operativne funkcije, pitanje 8): delovi sa zadatim iznosom. */
    val dodatnaPlacanja = mutableStateListOf<DeoPlacanja>()

    /** Poseban slučaj za račun koji se pravi (9.4, 9.7); posle izdavanja se vraća na „Ne“. */
    var posebanSlucaj by mutableStateOf(PosebanSlucaj.NE)
    var korporativniPib by mutableStateOf("")
    var korporativnaKartica by mutableStateOf("")

    /**
     * Avans Prodaja (vrsta „Avans“, PURS 9.1): iznos, poreska oznaka, šifra avansa (`null` = po stopi),
     * datum uplate kada je novac stigao ranije (9.2) i prethodni avans istog posla.
     */
    var avansIznos by mutableStateOf("")
    var avansOznaka by mutableStateOf<String?>(null)
    var avansSifra by mutableStateOf<SifraAvansa?>(null)
    var avansDatumUplate by mutableStateOf("")
    var prethodniAvans by mutableStateOf<IzdatRacun?>(null)
        private set

    /**
     * Konačni račun posle avansa koji se upravo pravi levo (stavke po punoj ceni), ili `null`.
     * Dok je postavljen, „Izdaj“ izdaje zatvaranje avansa i konačni račun, a plaćanje je samo ostatak.
     */
    var konacniPosao by mutableStateOf<AvansniPosao?>(null)
        private set

    /** Kupac na računu (npr. „na firmu“: šifra 10 = PIB); prazan broj = račun bez kupca. */
    var sifraKupca by mutableStateOf("10")
    var brojKupca by mutableStateOf("")

    /** Opciono polje kupca (šifarnik 9.8, npr. 20 = broj SNPDV); prazan broj = bez njega. Traži i ID kupca. */
    var sifraOpcionog by mutableStateOf(SIFRE_OPCIONOG_POLJA.first())
    var brojOpcionog by mutableStateOf("")

    /** Papir u štampaču (podešavanje kase); račun se štampa jednom, odmah posle izdavanja. */
    var papir by mutableStateOf(podesavanja.papir ?: SirinaPapira.MM80)
        private set

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
        vrstaPfra = vrsta
        sacuvaj(podesavanja.copy(pfr = vrsta))
        poveziPonovo()
    }

    /** Zatvara vezu i ponovo proverava PFR (posle promene podešavanja). */
    private fun poveziPonovo() {
        zatvori()
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

    /**
     * PIN kartice koji je kasir ukucao. Šalje se **jednom** i samo ako ga L-PFR traži; posle odbijanja nema
     * automatskog ponovnog slanja (posle 5 pogrešnih PIN-ova kartica se zaključava).
     * Vraća grešku unosa (ništa nije poslato) ili `null` ako je rad sa PFR-om započet.
     */
    fun posaljiPin(pin: String): String? {
        if (!Regex("""\d{4}""").matches(pin)) return "PIN ima tačno 4 cifre"
        posao { veza ->
            val lpfr = veza as? LpfrVeza ?: return@posao run { poruka = Poruka("V-PFR nema PIN", greska = true) }
            poruka = when (val r = lpfr.unesiPin(pin)) {
                RezultatPina.Prihvacen -> Poruka("PIN je prihvaćen (0100)", greska = false)
                RezultatPina.NijePotreban -> Poruka("L-PFR već ima ispravan PIN; PIN nije poslat", greska = false)
                is RezultatPina.NijePoslat -> Poruka("PIN nije poslat: ${r.razlog}", greska = true)
                is RezultatPina.Odbijen -> Poruka(
                    "PIN nije prihvaćen (${r.sifra.kod} ${r.sifra.opis}). Proveri PIN pre novog pokušaja: " +
                        "posle 5 pogrešnih kartica se zaključava.",
                    greska = true,
                )
            }
            status = veza.status()
        }
        return null
    }

    fun izaberiKasira(ime: String) {
        if (ime in podesavanja.kasiri) kasir = ime
    }

    /** Dodaje kasira u podešavanja; vraća grešku ili `null`. */
    fun dodajKasira(ime: String): String? {
        val i = ime.trim()
        Kasiri.proveri(i)?.let { return it }
        if (i in podesavanja.kasiri) return "Kasir $i već postoji"
        sacuvaj(podesavanja.copy(kasiri = podesavanja.kasiri + i))?.let { return it }
        if (kasir == null) kasir = i
        return null
    }

    fun ukloniKasira(ime: String) {
        sacuvaj(podesavanja.copy(kasiri = podesavanja.kasiri - ime))
        if (kasir == ime) kasir = podesavanja.kasiri.singleOrNull()
    }

    fun izaberiPapir(p: SirinaPapira) {
        papir = p
        sacuvaj(podesavanja.copy(papir = p))
    }

    /** Čuva adresu L-PFR-a (PIN iz komandne linije, ako je upisan, ostaje); vraća grešku ili `null`. */
    fun sacuvajLpfr(adresa: String): String? {
        val lpfr = LpfrPodesavanja(adresa.trim(), podesavanja.lpfr?.pin)
        lpfr.proveri().filterNot { it.startsWith("lpfr.pin") }.takeIf { it.isNotEmpty() }?.let { return razumljivo(it) }
        sacuvaj(podesavanja.copy(lpfr = lpfr))?.let { return it }
        porukaPodesavanja = Poruka("L-PFR je sačuvan.", greska = false)
        if (vrstaPfra == VrstaPfra.LPFR) poveziPonovo()
        return null
    }

    fun izaberiSertifikat(): String? = okruzenje.izaberiSertifikat()

    fun opisSertifikata(putanja: String?, lozinka: String): Result<String> = runCatching {
        require(!putanja.isNullOrBlank()) { "Izaberi sertifikat" }
        okruzenje.opisSertifikata(putanja, lozinka)
    }

    /** Čuva V-PFR: sertifikat, lozinku i PAC; sertifikat se pre čuvanja proverava. Vraća grešku ili `null`. */
    fun sacuvajVpfr(putanja: String?, lozinka: String, pac: String): String? {
        val vpfr = VpfrPodesavanja(podesavanja.vpfr?.baseUrl, putanja, lozinka.ifBlank { null }, pac.trim().ifBlank { null })
        vpfr.proveri().takeIf { it.isNotEmpty() }?.let { return razumljivo(it) }
        val opis = opisSertifikata(putanja, lozinka).getOrElse { return it.message ?: "Sertifikat nije ispravan" }
        sacuvaj(podesavanja.copy(vpfr = vpfr))?.let { return it }
        porukaPodesavanja = Poruka("V-PFR je sačuvan: $opis", greska = false)
        if (vrstaPfra == VrstaPfra.VPFR) poveziPonovo()
        return null
    }

    private fun sacuvaj(novo: Podesavanja): String? = try {
        okruzenje.sacuvajPodesavanja(novo)
        podesavanja = novo
        null
    } catch (e: Exception) {
        "Podešavanja nisu sačuvana: ${e.message ?: e}".also { porukaPodesavanja = Poruka(it, greska = true) }
    }

    /** Poruke iz provere podešavanja, bez imena polja iz JSON-a. */
    private fun razumljivo(problemi: List<String>): String = problemi.joinToString("; ") {
        it.replace("lpfr.baseUrl", "Adresa").replace("vpfr.baseUrl", "Adresa")
            .replace("vpfr.pfxPath", "Sertifikat").replace("vpfr.pfxPassword", "Lozinka").replace("vpfr.pac", "PAC")
            .replace(" (putanja do .p12/.pfx fajla)", "")
    }

    /** Greška ako kasir nije izabran (račun bez kasira se ne izdaje). */
    private fun bezKasira(): String? = when {
        kasir != null -> null
        podesavanja.kasiri.isEmpty() -> "Dodaj kasira u Podešavanjima"
        else -> "Izaberi kasira (gore desno)"
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

    /** Ograničenje plaćanja ove kase (član 6, stav 2 Pravilnika), iz podešavanja. */
    val ogranicenje: OgranicenjePlacanja get() = podesavanja.ogranicenjePlacanja ?: OgranicenjePlacanja.NEMA

    /** Načini plaćanja koje kasa nudi (svih 7, ili bez kartice, čeka i instanta). */
    val dozvoljeniNacini: List<NacinPlacanja> get() = SVI_NACINI_PLACANJA.filter { it in ogranicenje.dozvoljeni }

    fun postaviOgranicenje(bezKarticeCekaIInstanta: Boolean) {
        val novo = if (bezKarticeCekaIInstanta) OgranicenjePlacanja.BEZ_KARTICE_CEKA_I_INSTANTA else OgranicenjePlacanja.NEMA
        sacuvaj(podesavanja.copy(ogranicenjePlacanja = novo.takeIf { it != OgranicenjePlacanja.NEMA }))
        if (nacinPlacanja !in novo.dozvoljeni) nacinPlacanja = NacinPlacanja.GOTOVINA
        dodatnaPlacanja.removeAll { it.nacin !in novo.dozvoljeni }
    }

    /** Dodaje deo podeljenog plaćanja (npr. karticom), sa iznosom koji kasir upisuje. */
    fun dodajDeoPlacanja() {
        val nacin = dozvoljeniNacini.firstOrNull { it != nacinPlacanja && dodatnaPlacanja.none { d -> d.nacin == it } }
            ?: dozvoljeniNacini.first()
        dodatnaPlacanja += DeoPlacanja(nacin, "")
    }

    fun promeniDeoPlacanja(indeks: Int, deo: DeoPlacanja) {
        if (indeks in dodatnaPlacanja.indices) dodatnaPlacanja[indeks] = deo
    }

    fun ukloniDeoPlacanja(indeks: Int) {
        if (indeks in dodatnaPlacanja.indices) dodatnaPlacanja.removeAt(indeks)
    }

    /** Delovi plaćanja za obračun: dodatni sa iznosom, glavni dobija ostatak. */
    private fun deloviPlacanja(): List<Pair<NacinPlacanja, Decimal4?>> = dodatnaPlacanja.map { d ->
        require(d.iznos.isNotBlank()) { "Upiši iznos za „${d.nacin.naziv}“" }
        d.nacin to broj(d.iznos)
    } + (nacinPlacanja to null)

    /** Naplata [zaNaplatu] po izabranim načinima plaćanja; gotovina koju je kupac dao određuje kusur. */
    private fun naplataZa(zaNaplatu: Decimal4): Naplata {
        val delovi = deloviPlacanja()
        val dato = primljeno.takeIf { it.isNotBlank() && delovi.any { d -> d.first == NacinPlacanja.GOTOVINA } }?.let(::broj)
        return Obracun.naplata(zaNaplatu, delovi, dato, ogranicenje)
    }

    /** Ostatak koji ide na glavni način plaćanja, ili `null` ako iznosi nisu ispravni. */
    fun ostatakNaplate(zaNaplatu: Decimal4): Decimal4? = runCatching {
        zaNaplatu - dodatnaPlacanja.fold(Decimal4.NULA) { a, d -> a + broj(d.iznos.ifBlank { "0" }) }
    }.getOrNull()

    /** Kusur pri naplati [zaNaplatu], ili `null` ako nema gotovine, kupac nije dao više ili unos nije ispravan. */
    fun kusurNaplate(zaNaplatu: Decimal4): Decimal4? = runCatching {
        if (primljeno.isBlank()) return null
        naplataZa(zaNaplatu).kusur.takeIf { it > Decimal4.NULA }
    }.getOrNull()

    /** Opciono polje kupca za račun, ili `null`; baca [IllegalArgumentException] ako nije ispravno ili nema kupca. */
    private fun opcionoPolje(idKupca: String?): String? {
        val broj = brojOpcionog.trim().takeIf { it.isNotEmpty() } ?: return null
        val vrednost = "$sifraOpcionog:$broj"
        SifarnikKupca.proveriOpcionoPolje(vrednost)?.let { throw IllegalArgumentException(it) }
        require(idKupca != null) { "Opciono polje kupca traži i ID kupca" }
        return vrednost
    }

    private fun ocistiNaplatuIKupca() {
        primljeno = ""
        dodatnaPlacanja.clear()
        brojKupca = ""
        brojOpcionog = ""
    }

    /** Tekst ispod računa (polje za reklamu), red po red. */
    private fun redoviIspodRacuna(): List<String> =
        podesavanja.tekstIspodRacuna?.lines()?.map { it.trimEnd() }?.dropWhile { it.isBlank() }?.dropLastWhile { it.isBlank() }.orEmpty()

    /** Čuva tekst koji se štampa ispod „КРАЈ ФИСКАЛНОГ РАЧУНА“ (najviše 10 redova po 40 znakova). */
    fun sacuvajTekstIspodRacuna(tekst: String): String? {
        val redovi = tekst.trim().lines()
        if (redovi.size > 10) return "Najviše 10 redova"
        if (redovi.any { it.trimEnd().length > Otisak.KOLONA }) return "Red može da ima najviše ${Otisak.KOLONA} znakova (koliko staje na papir)"
        sacuvaj(podesavanja.copy(tekstIspodRacuna = tekst.trim().ifEmpty { null }))?.let { return it }
        porukaPodesavanja = Poruka("Tekst ispod računa je sačuvan.", greska = false)
        return null
    }

    /** Kusur ako je plaćanje gotovinom i kupac je dao više, inače `null`. */
    val kusur: Decimal4? get() = kusurNaplate(ukupno)

    fun izdaj() {
        bezKasira()?.let {
            poruka = Poruka(it, greska = true)
            return
        }
        konacniPosao?.let { p ->
            izdajKonacniRacun(p.poslednji, nacinPlacanja, primljeno, dodatniDelovi = true)?.let { poruka = Poruka(it, greska = true) }
            return
        }
        if (vrstaRacuna == VrstaRacuna.AVANS) {
            izdajAvans()
            return
        }
        if (stavke.isEmpty()) {
            poruka = Poruka("Račun nema stavki", greska = true)
            return
        }
        val kupac = brojKupca.trim().takeIf { it.isNotEmpty() }?.let { "$sifraKupca:$it" }
        kupac?.let { id ->
            SifarnikKupca.proveriIdKupca(id)?.let {
                poruka = Poruka(it, greska = true)
                return
            }
        }
        val (zahtev, dodatak) = try {
            when (posebanSlucaj) {
                PosebanSlucaj.NE -> {
                    val naplata = naplataZa(ukupno)
                    ZahtevZaRacun(
                        invoiceType = vrstaRacuna,
                        transactionType = TipTransakcije.PRODAJA,
                        payment = naplata.placanja,
                        items = stavke.toList(),
                        cashier = kasir,
                        buyerId = kupac,
                        buyerCostCenterId = opcionoPolje(kupac),
                    ) to listOfNotNull(redKusura(naplata.kusur))
                }
                // 9.7: napomena ide u „reklamno polje“, deo računa koji štampa ESIR.
                PosebanSlucaj.BEZ_NAKNADE -> PrometBezNaknade.zahtev(stavke.toList(), kasir, kupac)
                    .copy(buyerCostCenterId = opcionoPolje(kupac)) to listOf(PrometBezNaknade.NAPOMENA)
                PosebanSlucaj.KORPORATIVNA ->
                    KorporativnaKartica.zahtevZaProdaju(stavke.toList(), korporativniPib.trim(), korporativnaKartica.trim(), kasir) to emptyList()
            }
        } catch (e: IllegalArgumentException) {
            poruka = Poruka(e.message ?: "Neispravna naplata", greska = true)
            return
        }

        posao { veza ->
            val rezultat = izdajZahtev(veza, zahtev, dodatak)
            poruka = rezultat.poruka
            if (rezultat.izdat != null) {
                stavke.clear()
                ocistiNaplatuIKupca()
                posebanSlucaj = PosebanSlucaj.NE
                korporativnaKartica = ""
            }
        }
    }

    /**
     * Jednonamenski vaučer (9.5) kao stavka računa: „20: Једнонаменски ваучер (oznaka)“; na računu najviše jedan
     * vaučer po poreskoj oznaci. Vraća grešku ili `null`.
     */
    fun dodajVaucer(iznos: String, oznaka: String?): String? {
        if (oznaka == null) return "Izaberi poresku oznaku"
        if (stavke.any { JednonamenskiVaucer.jeVaucer(it) && it.labels == listOf(oznaka) }) {
            return "Na računu već postoji vaučer sa oznakom $oznaka (najviše jedan po poreskoj stopi)"
        }
        return try {
            val vrednost = broj(iznos)
            require(vrednost.brojDecimala <= 2) { "Iznos vaučera može da ima najviše 2 decimale" }
            stavke += JednonamenskiVaucer.stavka(oznaka, vrednost)
            null
        } catch (e: IllegalArgumentException) {
            e.message ?: "Neispravan iznos"
        }
    }

    /**
     * Poništavanje računa sa greškom (9.3): refundacija celog računa na **sopstveni PIB**, bez povraćaja novca kupcu.
     * Posle toga kasir izdaje novi, ispravan račun. Delimično refundiran račun se ne poništava.
     */
    fun ponisti(original: IzdatRacun): String? {
        bezKasira()?.let { return it }
        if (jeKonacniPosleAvansa(original)) return "Konačni račun posle avansa se za sada ne poništava iz aplikacije"
        avansniPosao(original)?.let { p ->
            if (!p.mozeSledeciAvans || p.poslednji.racun.invoiceNumber != original.racun.invoiceNumber) {
                return "Poništava se samo poslednji avans posla koji nije zatvoren"
            }
        }
        zbirnaRefundacijaZa(original)?.let { return "Račun je obuhvaćen zbirnom mesečnom refundacijom ${it.racun.invoiceNumber}" }
        refundacijeZa(original).takeIf { it.isNotEmpty() }?.let { r ->
            return "Račun je već refundiran (${r.joinToString { it.racun.invoiceNumber }}); poništavanje nije moguće"
        }
        val zahtev = try {
            original.zahtevZaPonistavanje()
        } catch (e: IllegalArgumentException) {
            return e.message ?: "Račun nije moguće poništiti"
        }
        posao { veza ->
            val r = izdajZahtev(veza, zahtev)
            poruka = if (r.izdat != null) Poruka("${r.poruka.tekst} Račun ${original.racun.invoiceNumber} je poništen; izdaj novi, ispravan račun.", r.poruka.greska) else r.poruka
        }
        return null
    }

    /**
     * Zbirna mesečna refundacija (9.4) koja već obuhvata ovaj račun korporacijskom karticom, ili `null`.
     * Takav račun se više ne refundira ni ne poništava pojedinačno (novac bi se vratio dvaput).
     */
    fun zbirnaRefundacijaZa(izdat: IzdatRacun): IzdatRacun? {
        val kupac = izdat.zahtev.buyerId ?: return null
        if (!kupac.startsWith("10:")) return null
        val pib = kupac.removePrefix("10:")
        if (!KorporativnaKartica.jeProdajaKarticom(izdat, pib)) return null
        val mesec = izdat.datum?.take(7) ?: return null
        val (od, doDana) = runCatching { KorporativnaKartica.periodMeseca(mesec) }.getOrNull() ?: return null
        return okruzenje.zurnal.mesecnaRefundacijaKartica(pib, od, doDana)
    }

    /** Šta bi ušlo u zbirnu mesečnu refundaciju korporacijskih kartica za kupca [pib] i mesec `gggg-mm`. */
    fun pregledMesecneRefundacije(pib: String, mesec: String): Result<MesecnaRefundacija> = runCatching {
        val (od, doDana) = KorporativnaKartica.periodMeseca(mesec)
        val racuni = okruzenje.zurnal.pretrazi(FilterZurnala(od = od, doDana = doDana, kupac = "10:${pib.trim()}"))
            .filter { KorporativnaKartica.jeProdajaKarticom(it, pib.trim()) }
        MesecnaRefundacija(od, doDana, racuni, racuni.fold(Decimal4.NULA) { zbir, r -> zbir + Obracun.ukupno(r.zahtev.items) })
    }

    /** Zbirna mesečna Promet Refundacija korporacijskih kartica (9.4), jednom po kupcu i mesecu. Vraća grešku ili `null`. */
    fun izdajMesecnuRefundaciju(pib: String, mesec: String): String? {
        bezKasira()?.let { return it }
        val pregled = pregledMesecneRefundacije(pib, mesec).getOrElse { return it.message }
        okruzenje.zurnal.mesecnaRefundacijaKartica(pib.trim(), pregled.od, pregled.doDana)?.let {
            return "Zbirna refundacija za ${pregled.od} – ${pregled.doDana} je već izdata: ${it.racun.invoiceNumber}"
        }
        val zahtev = try {
            KorporativnaKartica.zahtevZaMesecnuRefundaciju(pregled.racuni, pib.trim(), pregled.od, pregled.doDana, kasir)
        } catch (e: IllegalArgumentException) {
            return e.message
        }
        posao { veza -> poruka = izdajZahtev(veza, zahtev).poruka }
        return null
    }

    /** Današnji mesec `gggg-mm` (za mesečnu refundaciju). */
    fun ovajMesec(): String = okruzenje.danas().take(7)

    /**
     * Konačni račun posle avansa (Promet Prodaja uz poziv na Avans Refundaciju): stavke su po punoj ceni, a plaćanje
     * samo ostatak. Njegova refundacija i poništavanje nisu jasno opisani u uputstvu, pa ih aplikacija za sada ne nudi.
     */
    fun jeKonacniPosleAvansa(izdat: IzdatRacun): Boolean {
        if (izdat.zahtev.invoiceType != VrstaRacuna.PROMET || izdat.zahtev.transactionType != TipTransakcije.PRODAJA) return false
        val referenca = izdat.zahtev.referentDocumentNumber?.let(okruzenje.zurnal::nadji) ?: return false
        return referenca.zahtev.invoiceType == VrstaRacuna.AVANS && referenca.zahtev.transactionType == TipTransakcije.REFUNDACIJA
    }

    /** Avansni posao kome pripada račun (avans ili njegovo zatvaranje), ili `null`. */
    fun avansniPosao(izdat: IzdatRacun): AvansniPosao? = okruzenje.zurnal.avansniPosao(izdat)

    /** Šifra avansa: izabrana, ili po stopi izabrane oznake (20% → 10, 10% → 11, 0% → 12). */
    fun sifraAvansa(): SifraAvansa? =
        avansSifra ?: stope.firstOrNull { it.label == avansOznaka }?.let { SifraAvansa.zaStopu(it.rate) }

    /** Sledeći avans istog posla: poziva se na poslednji avans; kupac i oznaka kao u poslu. */
    fun zapocniSledeciAvans(posao: AvansniPosao) {
        konacniPosao = null
        vrstaRacuna = VrstaRacuna.AVANS
        posebanSlucaj = PosebanSlucaj.NE
        prethodniAvans = posao.poslednji
        posao.lanac.firstNotNullOfOrNull { it.zahtev.buyerId }?.split(":", limit = 2)?.takeIf { it.size == 2 }?.let {
            sifraKupca = it[0]
            brojKupca = it[1]
        }
        avansOznaka = posao.poslednji.zahtev.items.firstOrNull()?.labels?.firstOrNull()
        posao.lanac.firstNotNullOfOrNull { it.zahtev.buyerCostCenterId }?.split(":", limit = 2)?.takeIf { it.size == 2 }?.let {
            if (it[0] in SIFRE_OPCIONOG_POLJA) {
                sifraOpcionog = it[0]
                brojOpcionog = it[1]
            }
        }
    }

    /** Konačni račun za ovaj posao se pravi levo: vrsta Promet, stavke po punoj ceni, plaća se ostatak. */
    fun zapocniKonacni(posao: AvansniPosao) {
        konacniPosao = posao
        vrstaRacuna = VrstaRacuna.PROMET
        posebanSlucaj = PosebanSlucaj.NE
        prethodniAvans = null
    }

    fun otkaziKonacni() {
        konacniPosao = null
    }

    /** Kupac sa avansa (ako je upisan na nekom avansu ili zatvaranju), ili `null`. */
    val kupacSaAvansa: String?
        get() = konacniPosao?.let { p -> p.zatvaranje?.zahtev?.buyerId ?: p.lanac.firstNotNullOfOrNull { it.zahtev.buyerId } }

    /** Ostatak za plaćanje na konačnom računu: stavke levo minus avansi. */
    val ostatakPosleAvansa: Decimal4?
        get() = konacniPosao?.let { ukupno - it.ukupno }

    /** Avans više nije nastavak posla, nego prvi avans novog posla. */
    fun noviAvansniPosao() {
        prethodniAvans = null
    }

    private fun izdajAvans() {
        val kupac = brojKupca.trim().takeIf { it.isNotEmpty() }?.let { "$sifraKupca:$it" }
        val (zahtev, dodatak) = try {
            kupac?.let { id -> SifarnikKupca.proveriIdKupca(id)?.let { throw IllegalArgumentException(it) } }
            require(avansIznos.isNotBlank()) { "Unesi iznos avansa" }
            val iznos = broj(avansIznos)
            require(iznos > Decimal4.NULA) { "Iznos avansa mora biti veći od nule" }
            require(iznos.brojDecimala <= 2) { "Iznos avansa može da ima najviše 2 decimale" }
            val oznaka = avansOznaka ?: throw IllegalArgumentException("Izaberi poresku oznaku avansa")
            val sifra = sifraAvansa() ?: throw IllegalArgumentException("Za ovu stopu izaberi šifru avansa (10–13)")
            prethodniAvans?.let { p ->
                val posao = avansniPosao(p)
                require(posao != null && posao.mozeSledeciAvans && posao.poslednji.racun.invoiceNumber == p.racun.invoiceNumber) {
                    "Avansni posao se u međuvremenu promenio; izaberi ga ponovo u žurnalu"
                }
            }
            // 9.2: „ESIR vreme“ samo kada je uplata starija od današnjeg dana; za današnju se ne šalje.
            val esirVreme = avansDatumUplate.trim().takeIf { it.isNotEmpty() }?.let { datum ->
                val vreme = esirVremeZaDatumUplate(datum)
                require(datum <= okruzenje.danas()) { "Datum uplate ne može biti posle današnjeg dana" }
                vreme.takeIf { datum < okruzenje.danas() }
            }
            val naplata = naplataZa(iznos)
            val zahtev = Avans.zahtevZaAvans(
                uplate = listOf(AvansnaUplata(sifra, oznaka, iznos)),
                placanje = naplata.placanja,
                idKupca = kupac,
                prethodni = prethodniAvans,
                kasir = kasir,
                datumUplate = esirVreme,
            )
            val opciono = opcionoPolje(zahtev.buyerId) ?: prethodniAvans?.zahtev?.buyerCostCenterId?.takeIf { zahtev.buyerId == prethodniAvans?.zahtev?.buyerId }
            zahtev.copy(buyerCostCenterId = opciono) to listOfNotNull(redKusura(naplata.kusur))
        } catch (e: IllegalArgumentException) {
            poruka = Poruka(e.message ?: "Neispravan avans", greska = true)
            return
        }
        posao { veza ->
            val r = izdajZahtev(veza, zahtev, dodatak)
            poruka = r.poruka
            if (r.izdat != null) {
                avansIznos = ""
                avansDatumUplate = ""
                ocistiNaplatuIKupca()
                prethodniAvans = null
            }
        }
    }

    /**
     * Konačni račun posle avansa (PURS 9.1): stavke su na računu levo, po punoj ceni. Ako posao nije zatvoren,
     * prvo se izdaje Avans Refundacija za sve avanse (ostaje u radnji), pa Promet Prodaja na kojoj PFR-u ide samo ostatak.
     * Vraća grešku (ništa nije poslato PFR-u) ili `null` ako je rad sa PFR-om započet.
     */
    fun izdajKonacniRacun(avans: IzdatRacun, nacin: NacinPlacanja, primljeno: String = "", dodatniDelovi: Boolean = false): String? {
        bezKasira()?.let { return it }
        val posao = avansniPosao(avans) ?: return "Račun ${avans.racun.invoiceNumber} nije avans"
        if (posao.ponisten) return "Avansni posao je poništen"
        posao.konacni?.let { return "Konačni račun je već izdat: ${it.racun.invoiceNumber}" }
        if (stavke.isEmpty()) return "Dodaj stavke konačnog računa (levo), po punoj ceni"
        val preostalo = ukupno - posao.ukupno
        if (preostalo < Decimal4.NULA) {
            return "Avansi (${posao.ukupno.zaStampu()}) su veći od računa (${ukupno.zaStampu()}); delimična realizacija avansa još nije podržana"
        }
        val kupac = posao.zatvaranje?.zahtev?.buyerId
            ?: posao.lanac.firstNotNullOfOrNull { it.zahtev.buyerId }
            ?: brojKupca.trim().takeIf { it.isNotEmpty() }?.let { "$sifraKupca:$it" }
            ?: return "Unesi kupca (levo, „Kupac“): zatvaranje avansa traži ID kupca"
        SifarnikKupca.proveriIdKupca(kupac)?.let { return it }
        val (naplata, opciono) = try {
            val delovi = (if (dodatniDelovi) deloviPlacanja().dropLast(1) else emptyList()) + (nacin to null)
            val dato = primljeno.takeIf { it.isNotBlank() && preostalo > Decimal4.NULA && delovi.any { d -> d.first == NacinPlacanja.GOTOVINA } }?.let(::broj)
            Obracun.naplata(preostalo, delovi, dato, ogranicenje) to
                (posao.lanac.firstNotNullOfOrNull { it.zahtev.buyerCostCenterId } ?: opcionoPolje(kupac))
        } catch (e: IllegalArgumentException) {
            return e.message ?: "Neispravna naplata"
        }
        val kusur = naplata.kusur
        val zahtevZatvaranja = if (posao.zatvaranje == null) {
            try {
                Avans.zahtevZaZatvaranje(posao.lanac, kupac, kasir).copy(buyerCostCenterId = opciono)
            } catch (e: IllegalArgumentException) {
                return e.message
            }
        } else {
            null
        }
        val stavkeKonacnog = stavke.toList()

        posao { veza ->
            var porukaZatvaranja = ""
            val zatvaranje = posao.zatvaranje ?: run {
                val ar = izdajZahtev(veza, zahtevZatvaranja!!)
                porukaZatvaranja = ar.poruka.tekst + " "
                ar.izdat ?: run {
                    poruka = ar.poruka
                    return@posao
                }
            }
            val konacni = try {
                Avans.konacniRacun(zatvaranje, stavkeKonacnog, nacin, kasir)
            } catch (e: IllegalArgumentException) {
                poruka = Poruka(porukaZatvaranja + (e.message ?: "Konačni račun nije moguće sastaviti"), greska = true)
                return@posao
            }
            val dodatak = konacni.dodatniRedovi + listOfNotNull(redKusura(kusur)) + konacni.reklamnoPolje
            val zahtevKonacnog = konacni.zahtev.copy(
                payment = naplata.placanja,
                buyerCostCenterId = zatvaranje.zahtev.buyerCostCenterId ?: opciono,
            )
            val r = try {
                izdajZahtev(veza, zahtevKonacnog, dodatak)
            } catch (e: EsirGreska) {
                Izdavanje(null, Poruka(e.message ?: e.toString(), greska = true))
            }
            poruka = if (r.izdat != null) {
                Poruka(porukaZatvaranja + r.poruka.tekst, greska = r.poruka.greska)
            } else {
                Poruka(
                    porukaZatvaranja + "Konačni račun NIJE izdat: ${r.poruka.tekst} Avansi su zatvoreni; izdaj konačni račun ponovo " +
                        "(na računu ${zatvaranje.racun.invoiceNumber}).",
                    greska = true,
                )
            }
            if (r.izdat != null) {
                stavke.clear()
                ocistiNaplatuIKupca()
                konacniPosao = null
            }
        }
        return null
    }

    /** Kusur za [iznos] kada je kupac dao [primljeno] u gotovini, ili `null`. */
    fun kusurZa(iznos: Decimal4, primljeno: String): Decimal4? = runCatching {
        val dato = primljeno.takeIf { it.isNotBlank() }?.let(::broj) ?: return null
        if (iznos <= Decimal4.NULA) return null
        Obracun.naplata(iznos, listOf(NacinPlacanja.GOTOVINA to null), dato).kusur
    }.getOrNull()

    private fun redKusura(kusur: Decimal4): String? =
        if (kusur > Decimal4.NULA) "Повраћај:" + kusur.zaStampu().padStart(31) else null

    /** Refundacija koja je već izdata za ovaj račun, ili `null`. */
    fun refundacijaZa(izdat: IzdatRacun): IzdatRacun? = okruzenje.zurnal.refundacijaZa(izdat.racun.invoiceNumber)

    /** Sve refundacije ovog računa (cele i delimične), od najstarije. */
    fun refundacijeZa(izdat: IzdatRacun): List<IzdatRacun> = okruzenje.zurnal.refundacijeZa(izdat.racun.invoiceNumber)

    /** Koliko je od svake stavke još ostalo za refundaciju (redom kao stavke računa). */
    fun preostaloZaRefundaciju(izdat: IzdatRacun): List<Decimal4> = izdat.preostaloZaRefundaciju(refundacijeZa(izdat))

    /** Unete količine (prazno = 0), ili `null` ako neka nije broj. */
    fun kolicineIzTeksta(kolicine: List<String>): List<Decimal4>? = runCatching {
        kolicine.map { q -> q.takeIf { it.isNotBlank() }?.let(::broj) ?: Decimal4.NULA }
    }.getOrNull()

    /** Iznos koji bi se vratio za unete količine, ili `null` ako unos nije ispravan. */
    fun iznosRefundacije(izdat: IzdatRacun, kolicine: List<String>): Decimal4? = runCatching {
        izdat.zahtev.items.zip(kolicine).fold(Decimal4.NULA) { zbir, (s, q) ->
            zbir + (s.unitPrice * (q.takeIf { it.isNotBlank() }?.let(::broj) ?: Decimal4.NULA)).zaokruzi(2)
        }
    }.getOrNull()

    /**
     * Refundacija računa (PURS uputstvo 9.6), uz ID kupca koji prima novac (šifra iz šifarnika 9.8 i broj).
     *
     * - Bez [kolicine], ili kada se prvi put vraća sve: refundacija celog računa, istim plaćanjima kao na računu.
     * - Inače refundacija dela računa: [kolicine] po stavkama (prazno = 0), novac se vraća načinom [nacin].
     *   Ne može se vratiti više nego što je ostalo posle ranijih refundacija.
     *
     * Posle refundacije **gotovine** (vrsta Promet) odmah se izdaje i Kopija Refundacije sa redom za potpis kupca.
     * Vraća grešku unosa (ništa nije poslato PFR-u) ili `null` ako je rad sa PFR-om započet.
     */
    fun refundiraj(
        original: IzdatRacun,
        sifraKupca: String,
        brojKupca: String,
        kolicine: List<String>? = null,
        nacin: NacinPlacanja? = null,
    ): String? {
        bezKasira()?.let { return it }
        if (brojKupca.isBlank()) return "Unesi broj (${SifarnikKupca.idKupca[sifraKupca] ?: "dokument kupca"})"
        val id = "$sifraKupca:${brojKupca.trim()}"
        SifarnikKupca.proveriIdKupca(id)?.let { return it }
        zbirnaRefundacijaZa(original)?.let { return "Račun je obuhvaćen zbirnom mesečnom refundacijom ${it.racun.invoiceNumber}" }
        if (jeKonacniPosleAvansa(original)) return "Konačni račun posle avansa se za sada ne refundira iz aplikacije"
        val ranije = refundacijeZa(original)
        val preostalo = original.preostaloZaRefundaciju(ranije)
        if (preostalo.all { it == Decimal4.NULA }) {
            val brojevi = ranije.map { it.racun.invoiceNumber }
            return "Račun ${original.racun.invoiceNumber} je već refundiran ${if (brojevi.size == 1) "računom" else "računima"} ${brojevi.joinToString()}"
        }
        val opciono = original.zahtev.buyerCostCenterId.takeIf { id == original.zahtev.buyerId }
        val zahtev = try {
            val trazeno = kolicine?.map { q -> q.takeIf { it.isNotBlank() }?.let(::broj) ?: Decimal4.NULA } ?: preostalo
            if (ranije.isEmpty() && trazeno == original.zahtev.items.map { it.quantity }) {
                original.zahtevZaRefundaciju(id, opciono)
            } else {
                original.zahtevZaDelimicnuRefundaciju(id, trazeno, nacin ?: original.zahtev.payment.first().paymentType, preostalo, opciono)
            }
        } catch (e: IllegalArgumentException) {
            return e.message ?: "Račun nije moguće refundirati"
        }

        posao { veza ->
            val refundacija = izdajZahtev(veza, zahtev)
            poruka = refundacija.poruka
            val izdata = refundacija.izdat ?: return@posao
            val kopija = izdata.takeIf { it.mozeKopija }?.zahtevZaKopiju() ?: return@posao
            val potpis = kopija.redZaPotpisKupca() ?: return@posao

            val zaPotpis = try {
                izdajZahtev(veza, kopija, listOf(potpis))
            } catch (e: EsirGreska) {
                Izdavanje(null, Poruka(e.message ?: e.toString(), greska = true))
            }
            poruka = if (zaPotpis.izdat != null) {
                Poruka(
                    "${refundacija.poruka.tekst} ${zaPotpis.poruka.tekst} Kupac potpisuje kopiju; ona ostaje u radnji.",
                    greska = refundacija.poruka.greska || zaPotpis.poruka.greska,
                )
            } else {
                Poruka(
                    "${refundacija.poruka.tekst} Kopija za potpis kupca NIJE izdata: ${zaPotpis.poruka.tekst} " +
                        "Izdaj je dugmetom „Kopija“ na računu ${izdata.racun.invoiceNumber}.",
                    greska = true,
                )
            }
        }
        return null
    }

    /** Promet Prodaja koja je već izdata iz ovog predračuna, ili `null`. */
    fun prometIzPredracuna(predracun: IzdatRacun): IzdatRacun? = okruzenje.zurnal.prometIzPredracuna(predracun.racun.invoiceNumber)

    /**
     * Naplata po predračunu: Promet Prodaja sa istim stavkama i kupcem, uz poziv na predračun (ESIR upitnik deo 10, P13).
     * [primljeno] je koliko je kupac dao u gotovini (za kusur). Predračun se naplaćuje samo jednom.
     * Vraća grešku (ništa nije poslato PFR-u) ili `null` ako je rad sa PFR-om započet.
     */
    fun naplatiPredracun(predracun: IzdatRacun, nacin: NacinPlacanja, primljeno: String = ""): String? {
        bezKasira()?.let { return it }
        prometIzPredracuna(predracun)?.let {
            return "Predračun ${predracun.racun.invoiceNumber} je već naplaćen računom ${it.racun.invoiceNumber}"
        }
        val zaNaplatu = Obracun.ukupno(predracun.zahtev.items)
        val (zahtev, naplata) = try {
            val dato = primljeno.takeIf { it.isNotBlank() && nacin == NacinPlacanja.GOTOVINA }?.let(::broj)
            val naplata = Obracun.naplata(zaNaplatu, listOf(nacin to null), dato, ogranicenje)
            predracun.zahtevZaPrometIzPredracuna(naplata.placanja) to naplata
        } catch (e: IllegalArgumentException) {
            return e.message ?: "Predračun nije moguće naplatiti"
        }
        val dodatak = if (naplata.kusur > Decimal4.NULA) listOf("Повраћај:" + naplata.kusur.zaStampu().padStart(31)) else emptyList()
        posao { veza -> poruka = izdajZahtev(veza, zahtev, dodatak).poruka }
        return null
    }

    /** Kusur pri naplati predračuna gotovinom, ili `null`. */
    fun kusurZaPredracun(predracun: IzdatRacun, primljeno: String): Decimal4? = runCatching {
        val dato = primljeno.takeIf { it.isNotBlank() }?.let(::broj) ?: return null
        Obracun.naplata(Obracun.ukupno(predracun.zahtev.items), listOf(NacinPlacanja.GOTOVINA to null), dato).kusur
    }.getOrNull()

    /**
     * Kopija računa vrste Promet (prodaje ili refundacije); kod gotovinske refundacije sa redom za potpis kupca (9.6).
     * Vraća grešku (ništa nije poslato PFR-u) ili `null` ako je rad sa PFR-om započet.
     */
    fun izdajKopiju(original: IzdatRacun): String? {
        bezKasira()?.let { return it }
        val zahtev = try {
            original.zahtevZaKopiju()
        } catch (e: IllegalArgumentException) {
            return e.message ?: "Za ovaj račun se ne izdaje kopija"
        }
        posao { veza -> poruka = izdajZahtev(veza, zahtev, listOfNotNull(zahtev.redZaPotpisKupca())).poruka }
        return null
    }

    /** Ishod slanja jednog zahteva PFR-u: izdat račun (ili `null`) i poruka za kasira. */
    private class Izdavanje(val izdat: IzdatRacun?, val poruka: Poruka)

    /**
     * Šalje zahtev PFR-u; izdat račun upisuje u žurnal, prikazuje i štampa (jednom).
     * Kasir na računu je uvek kasir koji ga sada izdaje (i kod refundacije, kopije i naplate predračuna).
     */
    private suspend fun izdajZahtev(veza: PfrVeza, zahtevBezKasira: ZahtevZaRacun, dodatak: List<String> = emptyList()): Izdavanje {
        val zahtev = zahtevBezKasira.copy(cashier = kasir, invoiceNumber = Esir.BROJ)
        val reklama = redoviIspodRacuna()
        val ceoDodatak = dodatak + (if (dodatak.isNotEmpty() && reklama.isNotEmpty()) listOf("") else emptyList()) + reklama
        val jezik = PfrVeza.JEZIK_SRPSKI_CIRILICA.takeIf { it in status?.supportedLanguages.orEmpty() }
            ?: status?.supportedLanguages?.firstOrNull()
        val requestId = noviRequestId()
        return when (val r = veza.izdajRacun(zahtev, requestId, jezik)) {
            is RezultatRacuna.Izdat -> {
                val izdat = IzdatRacun(requestId, zahtev, r.racun)
                okruzenje.zurnal.sacuvaj(izdat, ceoDodatak)
                prikazi(izdat)
                ucitajZurnal()
                val naknadno = if (r.pronadjenNaknadno) " (pronađen po RequestId posle prekida veze)" else ""
                val opis = "Račun ${r.racun.invoiceNumber} (${izdat.oznaka})"
                val poruka = try {
                    val stampa = withContext(Dispatchers.IO) { okruzenje.stampaj(r.racun.invoiceNumber, papir) }
                    Poruka("$opis je izdat$naknadno. $stampa", greska = false)
                } catch (e: Exception) {
                    Poruka("$opis JE izdat$naknadno (ne izdaji ga ponovo), ali štampa nije uspela: ${e.message ?: e}", greska = true)
                }
                Izdavanje(izdat, poruka)
            }
            is RezultatRacuna.Odbijen -> {
                val sifre = r.greska?.modelState.orEmpty().flatMap { p -> p.errors.map { "${p.property}: $it ${SifreStanja.za(it).opis}" } }
                Izdavanje(null, Poruka("${veza.naziv} je odbio račun (HTTP ${r.httpStatus}). ${sifre.joinToString("; ")}", greska = true))
            }
            is RezultatRacuna.NijeIzdat -> Izdavanje(null, Poruka("Račun NIJE izdat: ${r.razlog}", greska = true))
            is RezultatRacuna.NepoznatoStanje -> Izdavanje(
                null,
                Poruka("NE ZNA SE da li je račun izdat (RequestId ${r.requestId}). Ne izdaji ponovo pre provere.", greska = true),
            )
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
            izdat = izdat,
            naslov = "$broj · ${izdat.oznaka}",
            tekst = okruzenje.tekstRacuna(broj) ?: izdat.racun.journal ?: "(nema teksta računa)",
            qr = izdat.racun.verificationQRCode?.let(::dekodirajBase64),
        )
    }

    /** Kako račun izgleda na papiru (samo prikaz; ne štampa). */
    fun pregledOtiska(broj: String, papir: SirinaPapira): Result<ImageBitmap?> =
        runCatching { okruzenje.pregledOtiska(broj, papir)?.uSliku() }

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
