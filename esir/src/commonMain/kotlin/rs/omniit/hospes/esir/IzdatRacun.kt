package rs.omniit.hospes.esir

import kotlinx.serialization.Serializable

/**
 * Izdati račun onako kako ga ESIR čuva: šta je poslato (zahtev) i šta je PFR vratio (fiskalizovan račun).
 *
 * Odgovor PFR-a ne sadrži stavke i plaćanja, zato ESIR mora da čuva i zahtev.
 * Bez njega ne može da napravi refundaciju ni kopiju.
 */
@Serializable
data class IzdatRacun(
    val requestId: String,
    val zahtev: ZahtevZaRacun,
    val racun: FiskalizovanRacun,
) {
    val oznaka: String get() = "${zahtev.invoiceType.naziv} ${zahtev.transactionType.naziv}"

    /** Da li se ovaj račun može refundirati kroz [zahtevZaRefundaciju] (prodaja vrste Promet, Obuka ili Predračun). */
    val mozeRefundacija: Boolean
        get() = zahtev.transactionType == TipTransakcije.PRODAJA && zahtev.invoiceType in VRSTE_ZA_REFUNDACIJU

    /** Da li se za ovaj račun izdaje kopija kroz [zahtevZaKopiju] (vrsta Promet, prodaja ili refundacija). */
    val mozeKopija: Boolean get() = zahtev.invoiceType == VrstaRacuna.PROMET

    /**
     * Zahtev za refundaciju celog ovog računa (PURS uputstvo, 9.6): iste stavke i plaćanja,
     * uz poziv na ovaj račun i ID kupca koji prima novac.
     *
     * Za avans važe posebna pravila (9.1) i on ovde nije podržan.
     */
    fun zahtevZaRefundaciju(idKupca: String, opcionoPoljeKupca: String? = null): ZahtevZaRacun {
        require(zahtev.transactionType == TipTransakcije.PRODAJA) { "Refundira se samo račun prodaje, a ovo je $oznaka" }
        require(mozeRefundacija) {
            "Refundacija je podržana za Promet, Obuku i Predračun, a ovo je $oznaka"
        }
        return zahtev.copy(
            transactionType = TipTransakcije.REFUNDACIJA,
            buyerId = idKupca,
            buyerCostCenterId = opcionoPoljeKupca,
            referentDocumentNumber = racun.invoiceNumber,
            referentDocumentDT = racun.sdcDateTime,
            dateAndTimeOfIssue = null,
        )
    }

    /**
     * Koliko je od svake stavke ovog računa još ostalo za refundaciju posle već izdatih [refundacije]
     * (redom kao stavke računa). Refundirana stavka se vezuje za stavku računa po nazivu, ceni i oznakama;
     * ako je ista stavka na računu dvaput, vraćena količina se troši redom.
     */
    fun preostaloZaRefundaciju(refundacije: List<IzdatRacun>): List<Decimal4> {
        val vraceno = mutableMapOf<Triple<String, Decimal4, List<String>>, Decimal4>()
        refundacije.flatMap { it.zahtev.items }.forEach { s ->
            vraceno[s.kljuc] = (vraceno[s.kljuc] ?: Decimal4.NULA) + s.quantity
        }
        return zahtev.items.map { s ->
            val ostatakVracenog = vraceno[s.kljuc] ?: Decimal4.NULA
            val uzeto = if (ostatakVracenog < s.quantity) ostatakVracenog else s.quantity
            vraceno[s.kljuc] = ostatakVracenog - uzeto
            s.quantity - uzeto
        }
    }

    /**
     * Zahtev za refundaciju **dela** ovog računa (gost vrati jedno piće od pet): [kolicine] po stavkama, redom kao
     * stavke računa (0 = ta stavka se ne vraća). Jedinična cena ostaje kao na računu, a novac se vraća načinom [nacin].
     *
     * @param preostalo koliko je od svake stavke još ostalo ([preostaloZaRefundaciju]); više od toga se ne vraća.
     */
    fun zahtevZaDelimicnuRefundaciju(
        idKupca: String,
        kolicine: List<Decimal4>,
        nacin: NacinPlacanja,
        preostalo: List<Decimal4> = zahtev.items.map { it.quantity },
        opcionoPoljeKupca: String? = null,
    ): ZahtevZaRacun {
        val osnova = zahtevZaRefundaciju(idKupca, opcionoPoljeKupca)
        require(kolicine.size == zahtev.items.size && preostalo.size == zahtev.items.size) { "Količine moraju da prate stavke računa" }
        val stavke = zahtev.items.indices.mapNotNull { i ->
            val s = zahtev.items[i]
            val q = kolicine[i]
            require(q >= Decimal4.NULA) { "Količina ne sme biti negativna" }
            require(q.brojDecimala <= 3) { "Količina može da ima najviše 3 decimale" }
            require(q <= preostalo[i]) { "„${s.name}“: može da se vrati najviše ${preostalo[i]}, a traži se $q" }
            if (q == Decimal4.NULA) null else s.copy(quantity = q, totalAmount = (s.unitPrice * q).zaokruzi(2))
        }
        require(stavke.isNotEmpty()) { "Izaberi bar jednu stavku i količinu za refundaciju" }
        return osnova.copy(items = stavke, payment = listOf(Placanje(Obracun.ukupno(stavke), nacin)))
    }

    /**
     * Zahtev za kopiju ovog računa: iste stavke, plaćanja i kupac, vrsta „Kopija“,
     * isti tip transakcije, uz poziv na ovaj račun. Kopija se izdaje za račun vrste Promet.
     */
    fun zahtevZaKopiju(): ZahtevZaRacun {
        require(mozeKopija) { "Kopija se izdaje za račun vrste Promet, a ovo je $oznaka" }
        return zahtev.copy(
            invoiceType = VrstaRacuna.KOPIJA,
            referentDocumentNumber = racun.invoiceNumber,
            referentDocumentDT = racun.sdcDateTime,
            dateAndTimeOfIssue = null,
        )
    }

    /**
     * Zahtev za Promet Prodaju koja proizilazi iz ovog predračuna: iste stavke,
     * uz poziv na predračun (ESIR upitnik, deo 10, P13).
     *
     * @param placanje stvarno plaćanje pri prodaji; ako se ne navede, isto kao na predračunu.
     */
    fun zahtevZaPrometIzPredracuna(placanje: List<Placanje>? = null): ZahtevZaRacun {
        require(zahtev.invoiceType == VrstaRacuna.PREDRACUN && zahtev.transactionType == TipTransakcije.PRODAJA) {
            "Promet iz predračuna se pravi samo od računa Predračun Prodaja, a ovo je $oznaka"
        }
        return zahtev.copy(
            invoiceType = VrstaRacuna.PROMET,
            payment = placanje ?: zahtev.payment,
            referentDocumentNumber = racun.invoiceNumber,
            referentDocumentDT = racun.sdcDateTime,
            dateAndTimeOfIssue = null,
        )
    }
}

/** Po ovome se refundirana stavka vezuje za stavku originalnog računa. */
private val Stavka.kljuc get() = Triple(name, unitPrice, labels)

private val VRSTE_ZA_REFUNDACIJU = setOf(VrstaRacuna.PROMET, VrstaRacuna.OBUKA, VrstaRacuna.PREDRACUN)

/** Naziv vrste računa kako ga koristi PURS, npr. „Promet“. */
val VrstaRacuna.naziv: String
    get() = when (this) {
        VrstaRacuna.PROMET -> "Promet"
        VrstaRacuna.PREDRACUN -> "Predračun"
        VrstaRacuna.KOPIJA -> "Kopija"
        VrstaRacuna.OBUKA -> "Obuka"
        VrstaRacuna.AVANS -> "Avans"
    }

/** Naziv načina plaćanja kako se piše na računu, npr. „Gotovina“. */
val NacinPlacanja.naziv: String
    get() = when (this) {
        NacinPlacanja.DRUGO_BEZGOTOVINSKO -> "Drugo bezgotovinsko plaćanje"
        NacinPlacanja.GOTOVINA -> "Gotovina"
        NacinPlacanja.PLATNA_KARTICA -> "Platna kartica"
        NacinPlacanja.CEK -> "Ček"
        NacinPlacanja.PRENOS_NA_RACUN -> "Prenos na račun"
        NacinPlacanja.VAUCER -> "Vaučer"
        NacinPlacanja.INSTANT_PLACANJE -> "Instant plaćanje"
    }

/** Naziv tipa transakcije kako ga koristi PURS, npr. „Prodaja“. */
val TipTransakcije.naziv: String
    get() = when (this) {
        TipTransakcije.PRODAJA -> "Prodaja"
        TipTransakcije.REFUNDACIJA -> "Refundacija"
    }
