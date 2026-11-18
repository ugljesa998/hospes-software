package rs.omniit.hospes.esir

/**
 * Obračun računa pre slanja PFR-u: stavke (količina, cena, popust) i naplata (više načina, kusur).
 *
 * Pravila:
 * - Jedinična cena (posle popusta) i ukupno po stavci zaokružuju se na **2 decimale**, polovina naviše
 *   (PURS ESIR upitnik, deo 11, P4). Tako se ono što PFR odštampa slaže sa onim što kupac plaća.
 * - Popust se **ne šalje** PFR-u kao posebna vrednost: jedinična cena i ukupno su već umanjeni (TaxCore „Create Invoice“).
 * - Količina može da ima najviše 3 decimale (npr. 0,250 kg).
 * - U plaćanju gotovinom šalje se iznos koji **ostaje u kasi**, a ne iznos koji je kupac dao; razlika je kusur.
 */
object Obracun {

    /**
     * Stavka računa sa izračunatim ukupnim iznosom.
     *
     * @param popustProcenat popust u procentima (0–100); jedinična cena se umanjuje pre množenja količinom.
     */
    fun stavka(
        naziv: String,
        kolicina: Decimal4,
        jedinicnaCena: Decimal4,
        oznake: List<String>,
        popustProcenat: Decimal4 = Decimal4.NULA,
        gtin: String? = null,
    ): Stavka {
        require(kolicina > Decimal4.NULA) { "Količina mora biti veća od nule" }
        require(kolicina.brojDecimala <= 3) { "Količina može da ima najviše 3 decimale" }
        require(jedinicnaCena >= Decimal4.NULA) { "Cena ne sme biti negativna" }
        require(popustProcenat >= Decimal4.NULA && popustProcenat <= STO) { "Popust mora biti od 0 do 100%" }

        val popust = (jedinicnaCena * popustProcenat).stotiDeo()
        val cena = (jedinicnaCena - popust).zaokruzi(2)
        val ukupno = (cena * kolicina).zaokruzi(2)
        return Stavka(
            name = naziv,
            quantity = kolicina,
            unitPrice = cena,
            totalAmount = ukupno,
            labels = oznake,
            gtin = gtin,
        )
    }

    /** Zbir svih stavki. */
    fun ukupno(stavke: List<Stavka>): Decimal4 = stavke.fold(Decimal4.NULA) { zbir, s -> zbir + s.totalAmount }

    /**
     * Raspodela naplate na načine plaćanja (podeljeno plaćanje je dozvoljeno, ESIR upitnik deo 10, P8).
     *
     * @param delovi način plaćanja i iznos; **jedan** deo sme biti bez iznosa i tada dobija ostatak.
     * @param primljenoGotovine koliko je kupac dao u gotovini; ako je više od gotovinskog dela, razlika je kusur.
     * @param ogranicenje ESIR podešavanje za obveznike kojima je zabranjen unos kartice, čeka i instant plaćanja.
     */
    fun naplata(
        zaNaplatu: Decimal4,
        delovi: List<Pair<NacinPlacanja, Decimal4?>>,
        primljenoGotovine: Decimal4? = null,
        ogranicenje: OgranicenjePlacanja = OgranicenjePlacanja.NEMA,
    ): Naplata {
        require(delovi.isNotEmpty()) { "Navedi bar jedan način plaćanja" }
        require(delovi.count { it.second == null } <= 1) { "Samo jedan način plaćanja može biti bez iznosa (ostatak)" }
        delovi.forEach { (nacin, _) ->
            require(nacin in ogranicenje.dozvoljeni) {
                "${nacin.naziv} je onemogućen ESIR podešavanjem (${ogranicenje.opis}); unosi se kao gotovina"
            }
        }

        val zadato = delovi.mapNotNull { it.second }.fold(Decimal4.NULA) { a, b -> a + b }
        val ostatak = zaNaplatu - zadato
        val placanja = delovi.map { (nacin, iznos) -> Placanje(iznos ?: ostatak, nacin) }
        require(placanja.all { it.amount >= Decimal4.NULA }) { "Zbir zadatih iznosa ($zadato) je veći od iznosa računa ($zaNaplatu)" }
        val zbir = placanja.fold(Decimal4.NULA) { a, p -> a + p.amount }
        require(zbir == zaNaplatu) { "Plaćanja ($zbir) se ne slažu sa iznosom računa ($zaNaplatu)" }

        val gotovina = placanja.filter { it.paymentType == NacinPlacanja.GOTOVINA }.fold(Decimal4.NULA) { a, p -> a + p.amount }
        val kusur = if (primljenoGotovine == null) {
            Decimal4.NULA
        } else {
            require(gotovina > Decimal4.NULA) { "Primljena gotovina je navedena, a nema plaćanja gotovinom" }
            require(primljenoGotovine >= gotovina) { "Kupac je dao $primljenoGotovine, a gotovinom treba da plati $gotovina" }
            primljenoGotovine - gotovina
        }
        return Naplata(placanja, kusur)
    }

    private val STO = Decimal4.parse("100")
}

/** Plaćanja koja idu PFR-u i kusur koji se vraća kupcu (kusur se ne šalje PFR-u). */
data class Naplata(val placanja: List<Placanje>, val kusur: Decimal4)

/**
 * ESIR podešavanje iz upitnika (deo 10, P7): po Pravilniku (član 6, stav 2) ESIR mora da ima funkciju koja
 * onemogućava unos platne kartice, čeka i instant plaćanja; kasir ih tada unosi kao gotovinu.
 */
@kotlinx.serialization.Serializable
enum class OgranicenjePlacanja(val opis: String, val dozvoljeni: Set<NacinPlacanja>) {
    NEMA("bez ograničenja", NacinPlacanja.entries.toSet()),
    BEZ_KARTICE_CEKA_I_INSTANTA(
        "Pravilnik, član 6, stav 2",
        setOf(
            NacinPlacanja.DRUGO_BEZGOTOVINSKO,
            NacinPlacanja.GOTOVINA,
            NacinPlacanja.PRENOS_NA_RACUN,
            NacinPlacanja.VAUCER,
        ),
    ),
}
