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

    /**
     * Zahtev za refundaciju celog ovog računa (PURS uputstvo, 9.6): iste stavke i plaćanja,
     * uz poziv na ovaj račun i ID kupca koji prima novac.
     *
     * Za avans važe posebna pravila (9.1) i on ovde nije podržan.
     */
    fun zahtevZaRefundaciju(idKupca: String, opcionoPoljeKupca: String? = null): ZahtevZaRacun {
        require(zahtev.transactionType == TipTransakcije.PRODAJA) { "Refundira se samo račun prodaje, a ovo je $oznaka" }
        require(zahtev.invoiceType in VRSTE_ZA_REFUNDACIJU) {
            "Refundacija je podržana za Promet i Obuku, a ovo je $oznaka"
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
     * Zahtev za kopiju ovog računa: iste stavke, plaćanja i kupac, vrsta „Kopija“,
     * isti tip transakcije, uz poziv na ovaj račun. Kopija se izdaje za račun vrste Promet.
     */
    fun zahtevZaKopiju(): ZahtevZaRacun {
        require(zahtev.invoiceType == VrstaRacuna.PROMET) { "Kopija se izdaje za račun vrste Promet, a ovo je $oznaka" }
        return zahtev.copy(
            invoiceType = VrstaRacuna.KOPIJA,
            referentDocumentNumber = racun.invoiceNumber,
            referentDocumentDT = racun.sdcDateTime,
            dateAndTimeOfIssue = null,
        )
    }
}

private val VRSTE_ZA_REFUNDACIJU = setOf(VrstaRacuna.PROMET, VrstaRacuna.OBUKA)

/** Naziv vrste računa kako ga koristi PURS, npr. „Promet“. */
val VrstaRacuna.naziv: String
    get() = when (this) {
        VrstaRacuna.PROMET -> "Promet"
        VrstaRacuna.PREDRACUN -> "Predračun"
        VrstaRacuna.KOPIJA -> "Kopija"
        VrstaRacuna.OBUKA -> "Obuka"
        VrstaRacuna.AVANS -> "Avans"
    }

/** Naziv tipa transakcije kako ga koristi PURS, npr. „Prodaja“. */
val TipTransakcije.naziv: String
    get() = when (this) {
        TipTransakcije.PRODAJA -> "Prodaja"
        TipTransakcije.REFUNDACIJA -> "Refundacija"
    }
