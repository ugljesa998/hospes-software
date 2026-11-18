package rs.omniit.hospes.esir

import kotlin.random.Random

/**
 * Ishod izdavanja računa preko [LpfrVeza.izdajRacun].
 *
 * Svaki ishod nosi [requestId], jedinstvenu oznaku zahteva. Po njoj se kasnije proverava
 * da li je račun izdat, ako veza pukne usred slanja.
 */
sealed interface RezultatRacuna {
    val requestId: String

    /** Račun je fiskalizovan. [pronadjenNaknadno] = odgovor je izgubljen, pa je račun pronađen po [requestId]. */
    data class Izdat(
        override val requestId: String,
        val racun: FiskalizovanRacun,
        val pronadjenNaknadno: Boolean = false,
    ) : RezultatRacuna

    /** PFR je odbio zahtev; račun NIJE izdat. [greska] sadrži šifre po poljima, ako ih je PFR poslao. */
    data class Odbijen(
        override val requestId: String,
        val httpStatus: Int,
        val greska: GreskaPfra?,
        val telo: String,
    ) : RezultatRacuna

    /** Račun sigurno NIJE izdat (PFR se ne javlja ili je potvrdio da ga nema). Bezbedno je pokušati ponovo. */
    data class NijeIzdat(override val requestId: String, val razlog: String) : RezultatRacuna

    /**
     * Ne zna se da li je račun izdat: veza je pukla, a ni provera po [requestId] nije uspela.
     * Račun se NE izdaje ponovo dok se ne proveri [requestId], inače može nastati dupli račun.
     */
    data class NepoznatoStanje(override val requestId: String, val razlog: String) : RezultatRacuna
}

/** Nova oznaka zahteva: 32 heksadecimalna znaka (TaxCore dozvoljava najviše 32). */
fun noviRequestId(): String =
    Random.nextBytes(16).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
