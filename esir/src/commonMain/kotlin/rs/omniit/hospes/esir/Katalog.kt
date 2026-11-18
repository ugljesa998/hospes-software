package rs.omniit.hospes.esir

import kotlinx.serialization.Serializable

/**
 * Artikal (proizvod ili usluga) u katalogu ESIR-a.
 *
 * ESIR upitnik, deo 11 („Upit i upravljanje cenama“): korisnik unosi nove artikle (P1), menja cenu (P3),
 * cena se zaokružuje na 2 decimale (P4), artikal se bira po nazivu ili skeniranjem GTIN-a (P5),
 * a lista artikala može da se uveze i izveze (P6). Deo 10, P11: ESIR mora da podrži GTIN.
 *
 * @property sifra interna šifra (PLU), jedinstvena u katalogu.
 * @property cena cena sa porezom za jednu jedinicu mere.
 * @property oznaka poreska oznaka; na prodaji mora postojati među stopama koje vrati PFR.
 */
@Serializable
data class Artikal(
    val sifra: String,
    val naziv: String,
    val cena: Decimal4,
    val oznaka: String,
    val jedinicaMere: String = "kom",
    val gtin: String? = null,
    val kategorija: String? = null,
) {
    /**
     * Naziv na računu: jedinica mere je deo naziva, odvojena kosom crtom
     * (PURS uputstvo: „naziv artikla odvojen znakom deljenja od jedinice mere“), npr. `Sir gauda /kg`.
     */
    val nazivNaRacunu: String get() = "$naziv /$jedinicaMere"

    fun proveri(): List<String> = buildList {
        if (!FORMAT_SIFRE.matches(sifra)) add("Šifra mora imati 1–20 slova, cifara, crtica ili tačaka")
        if (naziv.isBlank()) add("Naziv je obavezan")
        if (nazivNaRacunu.length > 2048) add("Naziv je predugačak")
        if (jedinicaMere.isBlank() || jedinicaMere.length > 10) add("Jedinica mere mora imati 1–10 znakova")
        if (cena < Decimal4.NULA) add("Cena ne sme biti negativna")
        if (cena.brojDecimala > 2) add("Cena može da ima najviše 2 decimale")
        if (oznaka.isBlank() || oznaka.length > 2) add("Poreska oznaka mora biti jedno slovo, npr. Ђ ili A")
        gtin?.let { Gtin.proveri(it)?.let(::add) }
    }

    /** Stavka računa za ovaj artikal; [cena] se može promeniti na samoj prodaji (upitnik, deo 11, P3). */
    fun uStavku(
        kolicina: Decimal4 = Decimal4.parse("1"),
        cena: Decimal4 = this.cena,
        popustProcenat: Decimal4 = Decimal4.NULA,
    ): Stavka = Obracun.stavka(nazivNaRacunu, kolicina, cena, listOf(oznaka), popustProcenat, gtin)
}

private val FORMAT_SIFRE = Regex("""[\p{L}\p{N}.\-]{1,20}""")

/** GTIN (EAN/UPC barkod): 8, 12, 13 ili 14 cifara sa kontrolnom cifrom po GS1 pravilu. */
object Gtin {
    /** Problem sa GTIN-om, ili `null` ako je ispravan. */
    fun proveri(gtin: String): String? {
        if (!gtin.all { it.isDigit() } || gtin.length !in setOf(8, 12, 13, 14)) {
            return "GTIN mora imati 8, 12, 13 ili 14 cifara"
        }
        val cifre = gtin.map { it - '0' }
        val zbir = cifre.dropLast(1).reversed().mapIndexed { i, c -> if (i % 2 == 0) c * 3 else c }.sum()
        val kontrolna = (10 - zbir % 10) % 10
        return if (kontrolna == cifre.last()) null else "GTIN $gtin nema ispravnu kontrolnu cifru"
    }
}

/** Katalog artikala; implementacija odlučuje gde se čuva (memorija, fajl, baza). */
interface Katalog {
    /** Svi artikli, po šifri. */
    fun svi(): List<Artikal>

    fun nadji(sifra: String): Artikal?

    /** Dodaje novi ili menja postojeći artikal (po šifri). Baca grešku ako artikal nije ispravan. */
    fun sacuvaj(artikal: Artikal)

    /** Uklanja artikal; vraća `false` ako nije postojao. */
    fun ukloni(sifra: String): Boolean
}

/** Artikal po GTIN-u (skeniranje barkoda). */
fun Katalog.poGtin(gtin: String): Artikal? = svi().firstOrNull { it.gtin == gtin }

/** Artikal po šifri ili GTIN-u, npr. iz čitača barkoda ili unosa kasira. */
fun Katalog.poSifriIliGtinu(kod: String): Artikal? = nadji(kod) ?: poGtin(kod)

/** Pretraga po delu naziva, šifre, GTIN-a ili kategorije, bez obzira na velika i mala slova. */
fun Katalog.pretrazi(tekst: String): List<Artikal> = svi().pretrazi(tekst)

/** Ista pretraga nad već učitanim spiskom artikala (npr. na ekranu). */
fun List<Artikal>.pretrazi(tekst: String): List<Artikal> {
    val t = tekst.trim().lowercase()
    if (t.isEmpty()) return this
    return filter { a ->
        listOfNotNull(a.naziv, a.sifra, a.gtin, a.kategorija).any { t in it.lowercase() }
    }
}

/** Promena cene artikla (upitnik, deo 11, P3); vraća izmenjeni artikal. */
fun Katalog.promeniCenu(sifra: String, novaCena: Decimal4): Artikal {
    val artikal = nadji(sifra) ?: throw IllegalArgumentException("Nema artikla sa šifrom $sifra")
    return artikal.copy(cena = novaCena).also(::sacuvaj)
}

/** Katalog u memoriji; osnova i za druge implementacije. */
open class KatalogUMemoriji(pocetni: List<Artikal> = emptyList()) : Katalog {
    protected val artikli = linkedMapOf<String, Artikal>()

    init {
        pocetni.forEach(::sacuvaj)
    }

    override fun svi(): List<Artikal> = artikli.values.sortedWith(compareBy({ it.sifra.length }, { it.sifra }))

    override fun nadji(sifra: String): Artikal? = artikli[sifra]

    override fun sacuvaj(artikal: Artikal) {
        val problemi = artikal.proveri()
        require(problemi.isEmpty()) { "Artikal ${artikal.sifra}: ${problemi.joinToString("; ")}" }
        artikal.gtin?.let { gtin ->
            val drugi = artikli.values.firstOrNull { it.gtin == gtin && it.sifra != artikal.sifra }
            require(drugi == null) { "GTIN $gtin već ima artikal ${drugi?.sifra}" }
        }
        artikli[artikal.sifra] = artikal
        posleIzmene()
    }

    override fun ukloni(sifra: String): Boolean = (artikli.remove(sifra) != null).also { if (it) posleIzmene() }

    /** Poziva se posle svake izmene (npr. da se katalog upiše u fajl). */
    protected open fun posleIzmene() {}
}
