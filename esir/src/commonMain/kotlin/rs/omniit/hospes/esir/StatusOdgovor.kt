package rs.omniit.hospes.esir

import kotlinx.serialization.Serializable

/**
 * Odgovor na `GET /api/v3/status` (TaxCore v3, „Get Status“).
 *
 * Imena polja su ista kao u API-ju. Neka polja vraća samo L-PFR (E-SDC), pa su sva opciona.
 */
@Serializable
data class StatusOdgovor(
    /** `true` ako PIN nije unet ili je poslednji uneti PIN bio pogrešan. Samo L-PFR. */
    val isPinRequired: Boolean? = null,
    /** `true` ako je zbir računa dostigao 75% limita kartice. Samo L-PFR. */
    val auditRequired: Boolean? = null,
    val sdcDateTime: String? = null,
    val lastInvoiceNumber: String? = null,
    val protocolVersion: String? = null,
    val secureElementVersion: String? = null,
    val hardwareVersion: String? = null,
    val softwareVersion: String? = null,
    val deviceSerialNumber: String? = null,
    val make: String? = null,
    val model: String? = null,
    /** Šifre stanja proizvođača L-PFR-a. */
    val mssc: List<String> = emptyList(),
    /** Opšte šifre stanja, vidi [SifreStanja]. */
    val gsc: List<String> = emptyList(),
    val supportedLanguages: List<String> = emptyList(),
    /** UID kartice (L-PFR) ili sertifikata (V-PFR). */
    val uid: String? = null,
    val taxCoreApi: String? = null,
    /** Trenutno važeće poreske stope. ESIR sme da koristi samo ove stope. */
    val currentTaxRates: GrupaPoreskihStopa? = null,
    val allTaxRates: List<GrupaPoreskihStopa> = emptyList(),
)

@Serializable
data class GrupaPoreskihStopa(
    val validFrom: String? = null,
    val groupId: Int? = null,
    val taxCategories: List<PoreskaKategorija> = emptyList(),
)

@Serializable
data class PoreskaKategorija(
    val name: String,
    /** 0 = porez na neto, 1 = porez na ukupno, 2 = iznos po količini. */
    val categoryType: Int? = null,
    val taxRates: List<PoreskaStopa> = emptyList(),
    val orderId: Int? = null,
)

@Serializable
data class PoreskaStopa(
    /** Stopa u procentima, npr. `10` za 10%. */
    val rate: Decimal4,
    /** Poreska oznaka koja se šalje u stavci računa, npr. `A`. */
    val label: String,
)
