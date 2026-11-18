package rs.omniit.hospes.esir

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Širina termalnog papira. Termalni štampači imaju 8 tačaka po milimetru (203 dpi),
 * a štampaju uže od papira: 58 mm papir → 48 mm (384 tačke), 80 mm papir → 72 mm (576 tačaka).
 */
@kotlinx.serialization.Serializable
enum class SirinaPapira(val mm: Int, val tacaka: Int) {
    MM58(58, 384),
    MM80(80, 576),
    ;

    val naziv: String get() = "$mm mm"

    companion object {
        const val TACAKA_PO_MM = 8
    }
}

/** Deo štampanog računa, odozgo nadole. */
sealed interface DeoOtiska {
    /** Običan red teksta, kako ga je složio PFR (40 znakova u redu). */
    data class Red(val tekst: String) : DeoOtiska

    /** „ОВО НИЈЕ ФИСКАЛНИ РАЧУН“: najmanje duplo većim slovima (ESIR upitnik, deo 16, P10). */
    data class Upozorenje(val tekst: String) : DeoOtiska

    /** QR kod za proveru računa (PFR ga vraća kao sliku), štampa se 40–50 mm. */
    class QrKod(val matrica: QrMatrica) : DeoOtiska
}

/**
 * Raspored štampanog računa: tekst od PFR-a, QR kod pre završne linije, pa redovi koje dodaje ESIR
 * (kusur, potpis kupca…). Isti raspored važi za svaki štampač i svaku platformu; crtanje u tačke je posebno.
 */
object Otisak {
    /** Širina teksta računa u znakovima, kao kod PFR-a. */
    const val KOLONA = 40

    /** Dozvoljena veličina QR koda na papiru (PURS uputstvo): 40–50 mm. */
    const val QR_MIN_MM = 40
    const val QR_MAX_MM = 50

    /** Poželjna veličina kada nema jednakih modula (sredina dozvoljenog). */
    const val QR_CILJ_MM = 44

    const val UPOZORENJE = "ОВО НИЈЕ ФИСКАЛНИ РАЧУН"

    fun delovi(tekstPfra: String, qr: QrMatrica?, dodatak: List<String> = emptyList()): List<DeoOtiska> {
        val redovi = tekstPfra.trimEnd().lines()
        val kraj = redovi.indexOfLast { it.startsWith("=====") && ("КРАЈ" in it || UPOZORENJE in it) }
            .takeIf { it >= 0 } ?: redovi.size
        return buildList {
            redovi.forEachIndexed { i, red ->
                if (i == kraj && qr != null) add(DeoOtiska.QrKod(qr))
                add(if (UPOZORENJE in red) DeoOtiska.Upozorenje(UPOZORENJE) else DeoOtiska.Red(red.trimEnd()))
            }
            if (kraj == redovi.size && qr != null) add(DeoOtiska.QrKod(qr))
            if (dodatak.isNotEmpty()) add(DeoOtiska.Red("")) // razmak, npr. za potpis kupca
            // Redovi ESIR-a duži od 40 znakova se prelamaju, da se zbog njih ne bi smanjila slova celog računa.
            dodatak.map { it.trimEnd() }.forEach { red ->
                if (red.length <= KOLONA) add(DeoOtiska.Red(red)) else prelomi(red, KOLONA).forEach { add(DeoOtiska.Red(it)) }
            }
        }
    }

    /**
     * Prelama tekst po rečima tako da nijedan red nije duži od [kolona] znakova.
     * Kada izađu dva reda, deli ih ravnomernije („ОВО НИЈЕ / ФИСКАЛНИ РАЧУН“).
     */
    fun prelomi(tekst: String, kolona: Int): List<String> {
        val redovi = prelomiRedom(tekst, kolona)
        if (redovi.size != 2) return redovi
        val reci = redovi.joinToString(" ").split(' ')
        return (1 until reci.size)
            .map { i -> listOf(reci.take(i).joinToString(" "), reci.drop(i).joinToString(" ")) }
            .filter { par -> par.all { it.length <= kolona } }
            .minByOrNull { par -> par.maxOf { it.length } } ?: redovi
    }

    /**
     * Veličina QR koda u tačkama za kod od [moduli] modula, na širini štampe [sirina].
     *
     * Čitači (telefon, aplikacija Poreske uprave) mere veličinu modula na tri „oka“ QR koda i iz nje računaju
     * koliko modula kod ima; ako su oka malo uža ili šira od proseka, kod se ne pročita. Zato se bira:
     * 1. veličina sa jednakim modulima (npr. 97 modula × 4 tačke = 48,5 mm), ako je u 40–50 mm i staje u širinu;
     * 2. inače, najbliža [QR_CILJ_MM] mm na kojoj oka imaju tačnu prosečnu veličinu modula.
     */
    fun stranaQr(moduli: Int, sirina: Int): Int {
        val tpm = SirinaPapira.TACAKA_PO_MM
        val od = QR_MIN_MM * tpm
        val doo = minOf(QR_MAX_MM * tpm, sirina)
        require(od <= doo) { "QR kod od $QR_MIN_MM mm ne staje u širinu od $sirina tačaka" }
        val kandidati = (od..doo).toList()
        kandidati.filter { it % moduli == 0 }.maxOrNull()?.let { return it }
        val cilj = QR_CILJ_MM * tpm
        val pouzdani = kandidati.filter { greskaOciju(moduli, it) < 0.2 }
        return pouzdani.minByOrNull { kotlin.math.abs(it - cilj) } ?: kandidati.minBy { greskaOciju(moduli, it) }
    }

    /** Ivice modula u tačkama (zaokružene), kada se [moduli] modula crta na [strana] tačaka. */
    fun iviceModula(moduli: Int, strana: Int): IntArray =
        IntArray(moduli + 1) { (it.toDouble() * strana / moduli).roundToInt() }

    /** Koliko modula bi čitač pogrešio kada broj modula računa iz veličine očiju i razmaka između njih. */
    private fun greskaOciju(moduli: Int, strana: Int): Double {
        val iv = iviceModula(moduli, strana)
        val levoOko = iv[7] - iv[0]
        val desnoOko = iv[moduli] - iv[moduli - 7]
        val modul = (levoOko + desnoOko) / 14.0
        val razmak = (iv[moduli - 7] + iv[moduli]) / 2.0 - (iv[0] + iv[7]) / 2.0
        return abs(razmak / modul + 7 - moduli)
    }

    private fun prelomiRedom(tekst: String, kolona: Int): List<String> {
        require(kolona > 0)
        val redovi = mutableListOf<String>()
        var red = ""
        tekst.split(' ').filter { it.isNotEmpty() }.forEach { rec ->
            var r = rec
            while (r.length > kolona) {
                if (red.isNotEmpty()) redovi += red.also { red = "" }
                redovi += r.take(kolona)
                r = r.drop(kolona)
            }
            red = when {
                red.isEmpty() -> r
                red.length + 1 + r.length <= kolona -> "$red $r"
                else -> { redovi += red; r }
            }
        }
        if (red.isNotEmpty()) redovi += red
        return redovi
    }
}

/** QR kod kao matrica modula (`true` = crni modul). */
class QrMatrica(val velicina: Int, private val moduli: BooleanArray) {
    init {
        require(moduli.size == velicina * velicina)
    }

    operator fun get(x: Int, y: Int): Boolean = moduli[y * velicina + x]

    companion object {
        /**
         * Čita module iz slike QR koda. PFR vraća GIF sa 4 piksela po modulu, bez belog ruba; ovde se ništa od toga
         * ne pretpostavlja: veličina modula se meri na gornjem levom „oku“ (7 modula), a broj modula mora biti
         * ispravan za QR kod (21, 25, … 177). Vraća `null` ako slika ne liči na QR kod.
         */
        fun izSlike(sirina: Int, visina: Int, tamno: (x: Int, y: Int) -> Boolean): QrMatrica? {
            var minX = sirina
            var minY = visina
            var maxX = -1
            var maxY = -1
            for (y in 0 until visina) for (x in 0 until sirina) {
                if (tamno(x, y)) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            if (maxX < 0) return null
            val strana = maxX - minX + 1
            if (abs(strana - (maxY - minY + 1)) > 1) return null

            var oko = 0
            while (minX + oko <= maxX && tamno(minX + oko, minY)) oko++
            val modul = oko / 7.0
            if (modul < 1.0) return null
            val n = (strana / modul).roundToInt()
            if (n < 21 || n > 177 || (n - 17) % 4 != 0) return null

            val korak = strana.toDouble() / n
            val moduli = BooleanArray(n * n) { i ->
                tamno(minX + ((i % n + 0.5) * korak).toInt(), minY + ((i / n + 0.5) * korak).toInt())
            }
            return QrMatrica(n, moduli)
        }
    }
}
