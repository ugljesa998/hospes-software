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
import rs.omniit.hospes.esir.Decimal4
import rs.omniit.hospes.esir.EsirGreska
import rs.omniit.hospes.esir.FilterZurnala
import rs.omniit.hospes.esir.IzdatRacun
import rs.omniit.hospes.esir.VpfrPodesavanja
import rs.omniit.hospes.esir.Podesavanja
import rs.omniit.hospes.esir.LpfrPodesavanja
import rs.omniit.hospes.esir.Kasiri
import rs.omniit.hospes.esir.KatalogCsv
import rs.omniit.hospes.esir.LpfrVeza
import rs.omniit.hospes.esir.NacinPlacanja
import rs.omniit.hospes.esir.Obracun
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
import rs.omniit.hospes.esir.refundacijaZa
import rs.omniit.hospes.esir.refundacijeZa
import rs.omniit.hospes.esir.uvezi

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
    var nacinPlacanja by mutableStateOf(NacinPlacanja.GOTOVINA)
    var primljeno by mutableStateOf("")

    /** Kupac na računu (npr. „na firmu“: šifra 10 = PIB); prazan broj = račun bez kupca. */
    var sifraKupca by mutableStateOf("10")
    var brojKupca by mutableStateOf("")

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

    /** Kusur ako je plaćanje gotovinom i kupac je dao više, inače `null`. */
    val kusur: Decimal4?
        get() = runCatching {
            val dato = primljeno.takeIf { it.isNotBlank() && nacinPlacanja == NacinPlacanja.GOTOVINA }?.let(::broj)
                ?: return null
            Obracun.naplata(ukupno, listOf(nacinPlacanja to null), dato).kusur
        }.getOrNull()

    fun izdaj() {
        bezKasira()?.let {
            poruka = Poruka(it, greska = true)
            return
        }
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
        val kupac = brojKupca.trim().takeIf { it.isNotEmpty() }?.let { "$sifraKupca:$it" }
        kupac?.let { id ->
            SifarnikKupca.proveriIdKupca(id)?.let {
                poruka = Poruka(it, greska = true)
                return
            }
        }
        val zahtev = ZahtevZaRacun(
            invoiceType = vrstaRacuna,
            transactionType = TipTransakcije.PRODAJA,
            payment = naplata.placanja,
            items = stavke.toList(),
            cashier = kasir,
            buyerId = kupac,
        )
        val dodatak = if (naplata.kusur > Decimal4.NULA) listOf("Повраћај:" + naplata.kusur.zaStampu().padStart(31)) else emptyList()

        posao { veza ->
            val rezultat = izdajZahtev(veza, zahtev, dodatak)
            poruka = rezultat.poruka
            if (rezultat.izdat != null) {
                stavke.clear()
                primljeno = ""
                brojKupca = ""
            }
        }
    }

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
            val naplata = Obracun.naplata(zaNaplatu, listOf(nacin to null), dato)
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
        val zahtev = zahtevBezKasira.copy(cashier = kasir)
        val jezik = PfrVeza.JEZIK_SRPSKI_CIRILICA.takeIf { it in status?.supportedLanguages.orEmpty() }
            ?: status?.supportedLanguages?.firstOrNull()
        val requestId = noviRequestId()
        return when (val r = veza.izdajRacun(zahtev, requestId, jezik)) {
            is RezultatRacuna.Izdat -> {
                val izdat = IzdatRacun(requestId, zahtev, r.racun)
                okruzenje.zurnal.sacuvaj(izdat, dodatak)
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
