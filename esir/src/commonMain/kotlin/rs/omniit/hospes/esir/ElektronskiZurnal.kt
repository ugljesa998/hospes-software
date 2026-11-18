package rs.omniit.hospes.esir

/**
 * Elektronski žurnal: svi računi koje je ESIR izdao, sa pretragom.
 *
 * ESIR upitnik, deo 10, P16: „ESIR mora pružiti listu svih izdatih računa na uređaju sa mogućnošću
 * pretraživanja određenog računa (elektronski žurnal)“, obavezno za poslednjih 30 dana.
 * Žurnal se ne briše; čuva se bar 30 dana, a ovde se čuva sve.
 *
 * Prikaz računa iz žurnala služi za pregled. Kupcu se ponovo daje samo **Kopija** (nova fiskalizacija),
 * nikad ponovo odštampan original.
 */
interface ElektronskiZurnal {
    /** Čuva izdat račun; [dodatak] su redovi koje je ESIR dodao ispod teksta PFR-a (npr. kusur, avans). */
    fun sacuvaj(izdat: IzdatRacun, dodatak: List<String> = emptyList())

    /** Račun po PFR broju, ili `null` ako ga nema. */
    fun nadji(brojRacuna: String): IzdatRacun?

    /** Svi sačuvani računi, od najnovijeg. */
    fun svi(): List<IzdatRacun>

    /** Računi koji odgovaraju [filter]-u, od najnovijeg. */
    fun pretrazi(filter: FilterZurnala): List<IzdatRacun> = svi().filter(filter::odgovara)
}

/**
 * Uslovi pretrage žurnala; prazan uslov (`null`) znači „bilo koji“.
 *
 * @param od prvi dan, format `gggg-mm-dd` (po PFR vremenu računa).
 * @param doDana poslednji dan, format `gggg-mm-dd`, uključujući taj dan.
 * @param tekst deo broja računa, naziva stavke, kasira ili ID kupca (bez obzira na velika i mala slova).
 */
data class FilterZurnala(
    val od: String? = null,
    val doDana: String? = null,
    val vrste: Set<VrstaRacuna>? = null,
    val tip: TipTransakcije? = null,
    val kupac: String? = null,
    val nacinPlacanja: NacinPlacanja? = null,
    val tekst: String? = null,
) {
    init {
        listOfNotNull(od, doDana).forEach { require(FORMAT_DATUMA.matches(it)) { "Datum mora biti u formatu gggg-mm-dd: $it" } }
    }

    fun odgovara(z: IzdatRacun): Boolean {
        val dan = z.datum
        if (od != null && (dan == null || dan < od)) return false
        if (doDana != null && (dan == null || dan > doDana)) return false
        if (vrste != null && z.zahtev.invoiceType !in vrste) return false
        if (tip != null && z.zahtev.transactionType != tip) return false
        if (kupac != null && z.zahtev.buyerId != kupac) return false
        if (nacinPlacanja != null && z.zahtev.payment.none { it.paymentType == nacinPlacanja }) return false
        if (tekst != null) {
            val trazi = tekst.lowercase()
            val polja = listOfNotNull(z.racun.invoiceNumber, z.zahtev.cashier, z.zahtev.buyerId) + z.zahtev.items.map { it.name }
            if (polja.none { trazi in it.lowercase() }) return false
        }
        return true
    }

    private companion object {
        val FORMAT_DATUMA = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}

/** Dan izdavanja po PFR vremenu (`gggg-mm-dd`), ili `null` ako PFR nije vratio vreme. */
/**
 * Refundacija koja je već izdata za račun [brojRacuna] (ne računa se kopija refundacije), ili `null`.
 * Služi da se isti račun ne refundira dvaput.
 */
fun ElektronskiZurnal.refundacijaZa(brojRacuna: String): IzdatRacun? = refundacijeZa(brojRacuna).firstOrNull()

/** Sve refundacije (cele i delimične, bez kopija) izdate za račun [brojRacuna], od najstarije. */
fun ElektronskiZurnal.refundacijeZa(brojRacuna: String): List<IzdatRacun> = svi().filter {
    it.zahtev.transactionType == TipTransakcije.REFUNDACIJA &&
        it.zahtev.invoiceType != VrstaRacuna.KOPIJA &&
        it.zahtev.referentDocumentNumber == brojRacuna
}.reversed()

/** Promet Prodaja koja je već izdata iz predračuna [brojPredracuna], ili `null` (predračun se naplaćuje jednom). */
fun ElektronskiZurnal.prometIzPredracuna(brojPredracuna: String): IzdatRacun? = svi().firstOrNull {
    it.zahtev.invoiceType == VrstaRacuna.PROMET &&
        it.zahtev.transactionType == TipTransakcije.PRODAJA &&
        it.zahtev.referentDocumentNumber == brojPredracuna
}

val IzdatRacun.datum: String? get() = racun.sdcDateTime?.take(10)?.takeIf { it.length == 10 }

/** Ukupan iznos računa: iz odgovora PFR-a, a ako ga nema, zbir stavki. */
val IzdatRacun.ukupno: Decimal4 get() = racun.totalAmount ?: Obracun.ukupno(zahtev.items)

/** Zbir po vrsti računa (npr. „Promet Prodaja“): broj računa i ukupan iznos. */
data class ZbirVrste(val oznaka: String, val broj: Int, val iznos: Decimal4)

/** Zbirovi za izabrane račune, po vrsti, redom kako se vrste prvi put pojavljuju. */
fun zbiroviZurnala(racuni: List<IzdatRacun>): List<ZbirVrste> =
    racuni.groupBy { it.oznaka }.map { (oznaka, grupa) ->
        ZbirVrste(oznaka, grupa.size, grupa.fold(Decimal4.NULA) { a, r -> a + r.ukupno })
    }

/** Žurnal u memoriji: za testove i za uređaje koji sami čuvaju podatke na drugi način. */
class ZurnalUMemoriji : ElektronskiZurnal {
    private val racuni = linkedMapOf<String, IzdatRacun>()

    override fun sacuvaj(izdat: IzdatRacun, dodatak: List<String>) {
        racuni[izdat.racun.invoiceNumber] = izdat
    }

    override fun nadji(brojRacuna: String): IzdatRacun? = racuni[brojRacuna]

    override fun svi(): List<IzdatRacun> = racuni.values.sortedWith(NAJNOVIJI_PRVO)
}

/** Redosled od najnovijeg: po PFR vremenu, pa po ukupnom brojaču. */
val NAJNOVIJI_PRVO: Comparator<IzdatRacun> =
    compareByDescending<IzdatRacun> { it.racun.sdcDateTime ?: "" }.thenByDescending { it.racun.totalCounter ?: 0 }
