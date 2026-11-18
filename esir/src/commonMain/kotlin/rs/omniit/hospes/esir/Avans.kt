package rs.omniit.hospes.esir

/**
 * Šifra avansne stavke iz PURS „Техничко упутство…“ v1.17, poglavlje 9.1.
 * Naziv stavke je `šifra: Аванс (oznaka)`, npr. `10: Аванс (Ђ)`; količina je uvek 1.
 */
enum class SifraAvansa(val kod: Int, val opis: String) {
    OPSTA_STOPA(10, "bruto iznos avansa po opštoj stopi PDV (20%)"),
    POSEBNA_STOPA(11, "bruto iznos avansa po posebnoj stopi PDV (10%)"),
    OSLOBODJEN_PDV(12, "avans za promet oslobođen PDV (0%)"),
    VAN_SISTEMA_PDV(13, "avans obveznika koji nije u sistemu PDV (0%)"),
    ;

    companion object {
        fun zaKod(kod: Int): SifraAvansa? = entries.firstOrNull { it.kod == kod }

        /**
         * Šifra po poreskoj stopi: 20% → 10, 10% → 11, 0% → 12 (oslobođen PDV). Šifra 13 (van sistema PDV) i
         * druge stope se biraju ručno. U sandbox-u su stope izmišljene, pa i šifra zavisi od izmišljene stope.
         */
        fun zaStopu(stopa: Decimal4): SifraAvansa? = when (stopa.toString()) {
            "20" -> OPSTA_STOPA
            "10" -> POSEBNA_STOPA
            "0" -> OSLOBODJEN_PDV
            else -> null
        }
    }
}

/** Jedna avansna uplata po poreskoj oznaci, npr. 1 500 000 RSD po oznaci Е (posebna stopa). */
data class AvansnaUplata(val sifra: SifraAvansa, val oznaka: String, val iznos: Decimal4) {
    fun kaoStavka(): Stavka = Stavka(
        name = "${sifra.kod}: Аванс ($oznaka)",
        quantity = Decimal4.parse("1"),
        unitPrice = iznos,
        totalAmount = iznos,
        labels = listOf(oznaka),
    )
}

/**
 * Konačni račun posle avansa i podaci koje ESIR **samo štampa**, a ne šalje PFR-u
 * (u PURS primeru označeni crveno): plaćeno avansom, PDV na avans, preostalo za plaćanje
 * i, u reklamnom polju, broj i datum poslednjeg avansnog računa.
 */
data class KonacniRacun(
    val zahtev: ZahtevZaRacun,
    val placenoAvansom: Decimal4,
    val pdvNaAvans: Decimal4,
    val preostaloZaPlacanje: Decimal4,
    val poslednjiAvans: String,
    val vremePoslednjegAvansa: String?,
) {
    /** Redovi koje ESIR dodaje na odštampan račun. */
    val dodatniRedovi: List<String>
        get() = listOf(
            "Плаћено авансом:" to placenoAvansom,
            "ПДВ на аванс:" to pdvNaAvans,
            "Преостало за плаћање:" to preostaloZaPlacanje,
        ).map { (naziv, iznos) -> naziv + iznos.zaStampu().padStart(SIRINA_RACUNA - naziv.length) }

    /** Obavezan tekst u reklamnom polju ispod „KRAJ FISKALNOG RAČUNA“ (PURS 9.1), npr. `… M97W88P4-M97W88P4-90 16.02.2022`. */
    val reklamnoPolje: String
        get() {
            val datum = vremePoslednjegAvansa?.take(10)?.split("-")
                ?.takeIf { it.size == 3 }?.let { (g, m, d) -> "$d.$m.$g" }
            return "Последњи авансни рачун: $poslednjiAvans ${datum ?: ""}".trim()
        }
}

/**
 * Avansni tok iz PURS uputstva, poglavlje 9.1:
 *
 * 1. **Avans Prodaja** (AP) za svaku uplatu; svaka sledeća se poziva na prethodnu.
 * 2. Pre konačnog računa: **Avans Refundacija** (AR) za zbir svih avansa, uz poziv na poslednju AP.
 *    AR se ne daje kupcu.
 * 3. **Promet Prodaja** (konačni račun) uz poziv na AR: sve stavke po punoj ceni,
 *    a plaćanje je samo ostatak posle avansa.
 */
object Avans {

    /**
     * Zahtev za Avans Prodaju.
     *
     * @param prethodni prethodna Avans Prodaja istog posla; ako postoji, novi račun se poziva na nju.
     * @param datumUplate „ESIR vreme“: obavezno kada je uplata (npr. virmanom) starija od dana izdavanja (PURS 9.2).
     */
    fun zahtevZaAvans(
        uplate: List<AvansnaUplata>,
        placanje: List<Placanje>,
        idKupca: String? = null,
        prethodni: IzdatRacun? = null,
        kasir: String? = null,
        datumUplate: String? = null,
    ): ZahtevZaRacun {
        require(uplate.isNotEmpty()) { "Avans mora da ima bar jednu uplatu" }
        require(uplate.all { it.iznos > Decimal4.NULA }) { "Iznos avansa mora biti veći od nule" }
        require(zbir(uplate.map { it.iznos }) == zbir(placanje.map { it.amount })) {
            "Plaćanje (${zbir(placanje.map { it.amount })}) mora biti jednako iznosu avansa (${zbir(uplate.map { it.iznos })})"
        }
        if (prethodni != null) require(prethodni.jeAvansProdaja()) {
            "Avans može da se poziva samo na prethodnu Avans Prodaju, a ${prethodni.racun.invoiceNumber} je ${prethodni.oznaka}"
        }
        return ZahtevZaRacun(
            invoiceType = VrstaRacuna.AVANS,
            transactionType = TipTransakcije.PRODAJA,
            payment = placanje,
            items = uplate.map { it.kaoStavka() },
            cashier = kasir,
            buyerId = idKupca ?: prethodni?.zahtev?.buyerId,
            referentDocumentNumber = prethodni?.racun?.invoiceNumber,
            referentDocumentDT = prethodni?.racun?.sdcDateTime,
            dateAndTimeOfIssue = datumUplate,
        )
    }

    /**
     * Zahtev za Avans Refundaciju kojom se zatvara ceo lanac avansa pre konačnog računa.
     * Stavke i plaćanja su zbir svih avansa iz [lanac], a poziva se na poslednji avans.
     *
     * @param lanac sve Avans Prodaje istog posla, od prve do poslednje (vidi [lanacAvansa]).
     * @param idKupca obavezan za refundaciju; ako se ne navede, uzima se iz avansa.
     */
    fun zahtevZaZatvaranje(lanac: List<IzdatRacun>, idKupca: String? = null, kasir: String? = null): ZahtevZaRacun {
        require(lanac.isNotEmpty()) { "Nema avansa za zatvaranje" }
        require(lanac.all { it.jeAvansProdaja() }) { "Zatvaraju se samo računi Avans Prodaja" }
        val kupac = idKupca ?: lanac.firstNotNullOfOrNull { it.zahtev.buyerId }
            ?: throw IllegalArgumentException("Avans Refundacija traži ID kupca (npr. 10:PIB ili 20:broj lične karte)")
        val poslednji = lanac.last()

        val stavke = lanac.flatMap { it.zahtev.items }
            .groupBy { it.name to it.labels }
            .map { (kljuc, grupa) ->
                val iznos = zbir(grupa.map { it.totalAmount })
                Stavka(kljuc.first, Decimal4.parse("1"), iznos, iznos, kljuc.second)
            }
        val placanja = lanac.flatMap { it.zahtev.payment }
            .groupBy { it.paymentType }
            .map { (nacin, grupa) -> Placanje(zbir(grupa.map { it.amount }), nacin) }

        return ZahtevZaRacun(
            invoiceType = VrstaRacuna.AVANS,
            transactionType = TipTransakcije.REFUNDACIJA,
            payment = placanja,
            items = stavke,
            cashier = kasir ?: poslednji.zahtev.cashier,
            buyerId = kupac,
            referentDocumentNumber = poslednji.racun.invoiceNumber,
            referentDocumentDT = poslednji.racun.sdcDateTime,
        )
    }

    /**
     * Konačni račun (Promet Prodaja) posle zatvaranja avansa.
     *
     * @param zatvaranje izdata Avans Refundacija koja je zatvorila lanac avansa.
     * @param stavke sve stavke prodaje po punoj ceni.
     * @param nacinPlacanja kako je plaćen ostatak; ako je ostatak 0, plaćanje se šalje sa iznosom 0.
     */
    fun konacniRacun(
        zatvaranje: IzdatRacun,
        stavke: List<Stavka>,
        nacinPlacanja: NacinPlacanja,
        kasir: String? = null,
    ): KonacniRacun {
        require(
            zatvaranje.zahtev.invoiceType == VrstaRacuna.AVANS &&
                zatvaranje.zahtev.transactionType == TipTransakcije.REFUNDACIJA,
        ) { "Konačni račun se poziva na Avans Refundaciju, a ${zatvaranje.racun.invoiceNumber} je ${zatvaranje.oznaka}" }

        val ukupno = zbir(stavke.map { it.totalAmount })
        val placenoAvansom = zbir(zatvaranje.zahtev.items.map { it.totalAmount })
        val preostalo = ukupno - placenoAvansom
        require(preostalo >= Decimal4.NULA) {
            "Avans ($placenoAvansom) je veći od iznosa računa ($ukupno). Delimična realizacija avansa još nije podržana."
        }

        val zahtev = ZahtevZaRacun(
            invoiceType = VrstaRacuna.PROMET,
            transactionType = TipTransakcije.PRODAJA,
            payment = listOf(Placanje(preostalo, nacinPlacanja)),
            items = stavke,
            cashier = kasir ?: zatvaranje.zahtev.cashier,
            buyerId = zatvaranje.zahtev.buyerId,
            referentDocumentNumber = zatvaranje.racun.invoiceNumber,
            referentDocumentDT = zatvaranje.racun.sdcDateTime,
        )
        return KonacniRacun(
            zahtev = zahtev,
            placenoAvansom = placenoAvansom,
            pdvNaAvans = zbir(zatvaranje.racun.taxItems.map { it.amount }),
            preostaloZaPlacanje = preostalo,
            poslednjiAvans = zatvaranje.zahtev.referentDocumentNumber ?: "?",
            vremePoslednjegAvansa = zatvaranje.zahtev.referentDocumentDT,
        )
    }

    /**
     * Ceo lanac avansa koji se završava sa [poslednji], od prve do poslednje Avans Prodaje.
     * Prethodni računi se traže preko [nadji] (npr. u lokalnom žurnalu) po broju na koji se poziva.
     */
    fun lanacAvansa(poslednji: IzdatRacun, nadji: (String) -> IzdatRacun?): List<IzdatRacun> {
        require(poslednji.jeAvansProdaja()) { "${poslednji.racun.invoiceNumber} nije Avans Prodaja, nego ${poslednji.oznaka}" }
        val lanac = ArrayDeque<IzdatRacun>()
        var trenutni: IzdatRacun? = poslednji
        while (trenutni != null) {
            lanac.addFirst(trenutni)
            val referenca = trenutni.zahtev.referentDocumentNumber ?: break
            val prethodni = nadji(referenca)?.takeIf { it.jeAvansProdaja() } ?: break
            require(lanac.none { it.racun.invoiceNumber == prethodni.racun.invoiceNumber }) { "Lanac avansa se vraća u krug" }
            trenutni = prethodni
        }
        return lanac.toList()
    }

    private fun zbir(iznosi: List<Decimal4>): Decimal4 = iznosi.fold(Decimal4.NULA) { a, b -> a + b }
}

/**
 * Jedan avansni posao (npr. rezervacija vozila): avansi od prvog do poslednjeg, zatvaranje (Avans Refundacija)
 * i konačni račun, kako su u žurnalu.
 */
data class AvansniPosao(val lanac: List<IzdatRacun>, val zatvaranje: IzdatRacun?, val konacni: IzdatRacun?) {
    val poslednji: IzdatRacun get() = lanac.last()

    /** Zbir svih avansa (sa porezom). */
    val ukupno: Decimal4 get() = lanac.flatMap { it.zahtev.items }.fold(Decimal4.NULA) { a, s -> a + s.totalAmount }

    /** Posao je otkazan poništavanjem (9.3): Avans Refundacija na sopstveni PIB prodavca. */
    val ponisten: Boolean
        get() = zatvaranje?.let { z ->
            val pib = z.racun.tin?.filter { it.isDigit() }
            pib != null && z.zahtev.buyerId == "10:$pib"
        } == true

    /** Može li se dodati avans ili izdati konačni račun: nije zatvoren, ili je zatvoren a konačni još nije izdat. */
    val mozeSledeciAvans: Boolean get() = zatvaranje == null
    val mozeKonacni: Boolean get() = konacni == null && !ponisten
}

/** Širina teksta računa koji vraća PFR (40 znakova), da bi dodati redovi bili poravnati sa njim. */
private const val SIRINA_RACUNA = 40

fun IzdatRacun.jeAvansProdaja(): Boolean =
    zahtev.invoiceType == VrstaRacuna.AVANS && zahtev.transactionType == TipTransakcije.PRODAJA
