package rs.omniit.hospes.esir

/** Crno-bela slika u tačkama termalnog štampača; širina mora biti deljiva sa 8 (jedan bajt = 8 tačaka). */
class Raster(val sirina: Int, val visina: Int) {
    init {
        require(sirina > 0 && sirina % 8 == 0) { "Širina mora biti deljiva sa 8" }
        require(visina >= 0)
    }

    private val tacke = BooleanArray(sirina * visina)

    operator fun get(x: Int, y: Int): Boolean = tacke[y * sirina + x]

    operator fun set(x: Int, y: Int, crna: Boolean) {
        tacke[y * sirina + x] = crna
    }
}

/**
 * ESC/POS komande za termalne štampače (Epson i kompatibilni: Xprinter, SUNMI, Bixolon…).
 *
 * Račun se šalje **kao slika** (GS v 0), a ne kao tekst: ćirilica i latinica izgledaju isto na svakom štampaču,
 * bez tabela znakova koje se razlikuju od modela do modela, i otisak je tačno onakav kakav je pregled na ekranu.
 */
object EscPos {
    /** ESC @: vraća štampač na početna podešavanja. */
    val POCETAK = byteArrayOf(0x1B, 0x40)

    /** GS V 66 0: pomera papir do noža i delimično seče (štampači bez noža ovo preskaču). */
    val ODSECANJE = byteArrayOf(0x1D, 0x56, 66, 0)

    /** Ceo račun: početak, slika, pomeranje papira i sečenje. */
    fun racun(slika: Raster, redovaPoTraci: Int = 128): ByteArray =
        POCETAK + slika(slika, redovaPoTraci) + ODSECANJE

    /**
     * Slika u trakama od najviše [redovaPoTraci] redova (jeftini štampači imaju mali prijemni bafer).
     * GS v 0 m xL xH yL yH, pa po red bajtova; najviši bit je tačka najviše levo, 1 = crna.
     */
    fun slika(slika: Raster, redovaPoTraci: Int = 128): ByteArray {
        require(redovaPoTraci in 1..2047)
        val bajtovaURedu = slika.sirina / 8
        val izlaz = ArrayList<Byte>(slika.visina * (bajtovaURedu + 1) + 16)
        var y0 = 0
        while (y0 < slika.visina) {
            val redova = minOf(redovaPoTraci, slika.visina - y0)
            izlaz += listOf(0x1D, 0x76, 0x30, 0, bajtovaURedu and 0xFF, bajtovaURedu shr 8, redova and 0xFF, redova shr 8)
                .map { it.toByte() }
            for (y in y0 until y0 + redova) {
                for (b in 0 until bajtovaURedu) {
                    var bajt = 0
                    for (bit in 0 until 8) if (slika[b * 8 + bit, y]) bajt = bajt or (0x80 shr bit)
                    izlaz += bajt.toByte()
                }
            }
            y0 += redova
        }
        return izlaz.toByteArray()
    }
}
