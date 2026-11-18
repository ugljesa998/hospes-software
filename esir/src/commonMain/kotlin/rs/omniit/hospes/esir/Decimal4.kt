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

    /** Zapis bez nepotrebnih nula na kraju: `10`, `0.5`, `27.2727`. */
    override fun toString(): String {
        val negativan = unscaled < 0
        val apsolutna = if (negativan) -unscaled else unscaled
        val celi = apsolutna / FAKTOR
        val decimale = (apsolutna % FAKTOR).toString().padStart(SKALA, '0').trimEnd('0')
        val znak = if (negativan) "-" else ""
        return if (decimale.isEmpty()) "$znak$celi" else "$znak$celi.$decimale"
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
