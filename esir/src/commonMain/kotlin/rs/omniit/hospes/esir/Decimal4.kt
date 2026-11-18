package rs.omniit.hospes.esir

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.jsonPrimitive
import kotlin.jvm.JvmInline
import kotlin.math.abs

/**
 * Tačan decimalni broj sa najviše 4 decimale, kao `decimal(28,4)` u TaxCore API-ju.
 *
 * Interno se čuva kao ceo broj (vrednost × 10 000), pa nema grešaka zaokruživanja
 * kao kod `Double`. Opseg je ±99 999 999 999 999,9999, što je dovoljno za iznose u RSD.
 */
@JvmInline
@Serializable(with = Decimal4Serializer::class)
value class Decimal4 private constructor(val unscaled: Long) : Comparable<Decimal4> {

    override fun compareTo(other: Decimal4): Int = unscaled.compareTo(other.unscaled)

    operator fun plus(other: Decimal4): Decimal4 = uOpsegu(unscaled + other.unscaled)

    operator fun minus(other: Decimal4): Decimal4 = uOpsegu(unscaled - other.unscaled)

    /**
     * Proizvod, zaokružen na 4 decimale (polovina se zaokružuje naviše, od nule).
     * Baca grešku ako bi rezultat izašao iz opsega, umesto da tiho pogreši.
     */
    operator fun times(other: Decimal4): Decimal4 {
        val a = unscaled
        val b = other.unscaled
        if (a == 0L || b == 0L) return NULA
        require(abs(a) <= (Long.MAX_VALUE - FAKTOR) / abs(b)) { "Iznos je van dozvoljenog opsega" }
        val proizvod = a * b
        return uOpsegu(podeliSaZaokruzivanjem(proizvod, FAKTOR))
    }

    /** Stoti deo vrednosti (npr. za procenat), zaokružen na 4 decimale. */
    fun stotiDeo(): Decimal4 = uOpsegu(podeliSaZaokruzivanjem(unscaled, 100))

    /** Zaokruživanje na [decimale] (0–4); polovina se zaokružuje naviše (od nule), kao u PURS uputstvu. */
    fun zaokruzi(decimale: Int): Decimal4 {
        require(decimale in 0..SKALA) { "Broj decimala mora biti od 0 do $SKALA" }
        var korak = 1L
        repeat(SKALA - decimale) { korak *= 10 }
        return uOpsegu(podeliSaZaokruzivanjem(unscaled, korak) * korak)
    }

    /** Broj decimala koje stvarno postoje (bez nula na kraju), npr. 2 za `27.27`. */
    val brojDecimala: Int
        get() {
            var ostatak = abs(unscaled) % FAKTOR
            if (ostatak == 0L) return 0
            var decimala = SKALA
            while (ostatak % 10 == 0L) {
                ostatak /= 10
                decimala--
            }
            return decimala
        }

    /** Zapis bez nepotrebnih nula na kraju: `10`, `0.5`, `27.2727`. */
    override fun toString(): String {
        val negativan = unscaled < 0
        val apsolutna = if (negativan) -unscaled else unscaled
        val celi = apsolutna / FAKTOR
        val decimale = (apsolutna % FAKTOR).toString().padStart(SKALA, '0').trimEnd('0')
        val znak = if (negativan) "-" else ""
        return if (decimale.isEmpty()) "$znak$celi" else "$znak$celi.$decimale"
    }

    /**
     * Zapis za štampu na računu: dve decimale (polovina se zaokružuje naviše), tačka za hiljade,
     * zarez za decimale, kao u tekstu računa koji vraća PFR. Primer: `1.500,00`, `136,36`.
     */
    fun zaStampu(): String {
        val negativan = unscaled < 0
        val apsolutna = if (negativan) -unscaled else unscaled
        val centi = (apsolutna + 50) / 100
        val celi = (centi / 100).toString().reversed().chunked(3).joinToString(".").reversed()
        val decimale = (centi % 100).toString().padStart(2, '0')
        return (if (negativan && centi != 0L) "-" else "") + "$celi,$decimale"
    }

    companion object {
        const val SKALA = 4
        private const val FAKTOR = 10_000L
        private const val MAKS_CIFARA_CELOG_DELA = 14
        private val FORMAT = Regex("""-?\d+(\.\d+)?""")

        val NULA = Decimal4(0)

        /** Najveća vrednost: 14 cifara celog dela i 4 decimale. Zbir dve takve vrednosti i dalje staje u Long. */
        private const val MAKS_UNSCALED = 999_999_999_999_999_999L

        fun izUnscaled(unscaled: Long): Decimal4 = uOpsegu(unscaled)

        /** Deljenje celih brojeva sa zaokruživanjem polovine od nule (1.5 → 2, -1.5 → -2). */
        private fun podeliSaZaokruzivanjem(deljenik: Long, delilac: Long): Long {
            val pola = delilac / 2
            return if (deljenik >= 0) (deljenik + pola) / delilac else -((-deljenik + pola) / delilac)
        }

        private fun uOpsegu(unscaled: Long): Decimal4 {
            require(unscaled in -MAKS_UNSCALED..MAKS_UNSCALED) { "Iznos je van dozvoljenog opsega" }
            return Decimal4(unscaled)
        }

        /**
         * Čita broj iz teksta, npr. `"300"`, `"27.27"` ili `"0.5"`.
         * Ne zaokružuje: ako ima više od 4 decimale koje nisu nule, baca grešku.
         */
        fun parse(tekst: String): Decimal4 {
            val t = tekst.trim()
            require(FORMAT.matches(t)) { "Neispravan decimalni broj: '$tekst'" }
            val negativan = t.startsWith("-")
            val bezZnaka = t.removePrefix("-")
            val celiDeo = bezZnaka.substringBefore('.').trimStart('0').ifEmpty { "0" }
            val decimalniDeo = bezZnaka.substringAfter('.', "").trimEnd('0')
            require(celiDeo.length <= MAKS_CIFARA_CELOG_DELA) { "Broj je prevelik: '$tekst'" }
            require(decimalniDeo.length <= SKALA) { "Više od $SKALA decimale: '$tekst'" }
            val unscaled = celiDeo.toLong() * FAKTOR + decimalniDeo.padEnd(SKALA, '0').toLong()
            return Decimal4(if (negativan) -unscaled else unscaled)
        }
    }
}

/** Upisuje i čita [Decimal4] kao JSON broj, bez prolaska kroz `Double`. */
object Decimal4Serializer : KSerializer<Decimal4> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("rs.omniit.hospes.esir.Decimal4", PrimitiveKind.DOUBLE)

    override fun deserialize(decoder: Decoder): Decimal4 {
        val tekst = if (decoder is JsonDecoder) {
            decoder.decodeJsonElement().jsonPrimitive.content
        } else {
            decoder.decodeString()
        }
        return Decimal4.parse(tekst)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Decimal4) {
        if (encoder is JsonEncoder) {
            encoder.encodeJsonElement(JsonUnquotedLiteral(value.toString()))
        } else {
            encoder.encodeString(value.toString())
        }
    }
}
