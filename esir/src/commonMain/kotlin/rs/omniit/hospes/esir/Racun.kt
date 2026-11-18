package rs.omniit.hospes.esir

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Vrsta računa (TaxCore `invoiceType`). */
@Serializable
enum class VrstaRacuna {
    @SerialName("Normal") PROMET,
    @SerialName("Proforma") PREDRACUN,
    @SerialName("Copy") KOPIJA,
    @SerialName("Training") OBUKA,
    @SerialName("Advance") AVANS,
}

/** Tip transakcije (TaxCore `transactionType`). */
@Serializable
enum class TipTransakcije {
    @SerialName("Sale") PRODAJA,
    @SerialName("Refund") REFUNDACIJA,
}

/** Način plaćanja (TaxCore `paymentType`). ESIR mora da podrži svih sedam. */
@Serializable
enum class NacinPlacanja {
    @SerialName("Other") DRUGO_BEZGOTOVINSKO,
    @SerialName("Cash") GOTOVINA,
    @SerialName("Card") PLATNA_KARTICA,
    @SerialName("Check") CEK,
    @SerialName("WireTransfer") PRENOS_NA_RACUN,
    @SerialName("Voucher") VAUCER,
    @SerialName("MobileMoney") INSTANT_PLACANJE,
}

/**
 * Zahtev za fiskalizaciju računa (`POST /api/v3/invoices`, „Create Invoice“).
 * Imena polja su ista kao u API-ju; prazna (`null`) polja se ne šalju.
 */
@Serializable
data class ZahtevZaRacun(
    val invoiceType: VrstaRacuna,
    val transactionType: TipTransakcije,
    val payment: List<Placanje>,
    val items: List<Stavka>,
    /** „ESIR vreme“. */
    val dateAndTimeOfIssue: String? = null,
    val cashier: String? = null,
    /** „ID kupca“, po PURS šifarniku, npr. `10:123456789` za PIB. */
    val buyerId: String? = null,
    /** „Opciono polje kupca“. Ako se šalje, mora da postoji i [buyerId]. */
    val buyerCostCenterId: String? = null,
    /** „ESIR broj“. */
    val invoiceNumber: String? = null,
    /** Broj računa na koji se poziva (obavezno za kopije i refundacije). */
    val referentDocumentNumber: String? = null,
    val referentDocumentDT: String? = null,
    val options: OpcijeRacuna? = null,
) {
    /** Provera pre slanja. Prazan spisak znači da zahtev može da se pošalje. */
    fun proveri(): List<String> = buildList {
        if (items.isEmpty()) add("Račun mora da ima bar jednu stavku")
        if (payment.isEmpty()) add("Račun mora da ima bar jedno plaćanje")
        if (buyerCostCenterId != null && buyerId == null) add("Opciono polje kupca traži i ID kupca")
        if ((buyerId?.length ?: 0) > 20) add("ID kupca može da ima najviše 20 znakova")
        if ((invoiceNumber?.length ?: 0) > 60) add("ESIR broj može da ima najviše 60 znakova")
        if (invoiceType == VrstaRacuna.KOPIJA || transactionType == TipTransakcije.REFUNDACIJA) {
            if (referentDocumentNumber == null) add("Kopija i refundacija traže broj računa na koji se pozivaju")
        }
        items.forEachIndexed { i, s -> s.proveri().forEach { add("Stavka ${i + 1}: $it") } }
    }
}

@Serializable
data class Placanje(
    /** Iznos koji ostaje u kasi (bez kusura koji je vraćen kupcu). */
    val amount: Decimal4,
    val paymentType: NacinPlacanja,
)

@Serializable
data class Stavka(
    /** Naziv sa jedinicom mere, npr. `Test kafa /kom`. */
    val name: String,
    val quantity: Decimal4,
    val unitPrice: Decimal4,
    /** Jedinična cena × količina, posle popusta. */
    val totalAmount: Decimal4,
    /** Poreske oznake; dozvoljene su samo oznake koje vrati PFR u statusu. */
    val labels: List<String>,
    val gtin: String? = null,
) {
    fun proveri(): List<String> = buildList {
        if (name.isBlank()) add("naziv je obavezan")
        if (name.length > 2048) add("naziv može da ima najviše 2048 znakova")
        if (quantity <= Decimal4.NULA) add("količina mora da bude veća od nule")
        if (quantity.unscaled % 10 != 0L) add("količina može da ima najviše 3 decimale")
        if (labels.isEmpty()) add("mora da ima bar jednu poresku oznaku")
        if (labels.size != labels.toSet().size) add("ista poreska oznaka ne sme da se ponovi")
        if (gtin != null && gtin.length !in 8..14) add("GTIN mora da ima od 8 do 14 znakova")
    }
}

@Serializable
data class OpcijeRacuna(
    /** `"1"` = PFR ne pravi QR kod. */
    val omitQRCodeGen: String? = null,
    /** `"1"` = PFR ne pravi tekst računa. */
    val omitTextualRepresentation: String? = null,
)

/** Odgovor PFR-a na fiskalizaciju računa. */
@Serializable
data class FiskalizovanRacun(
    /** „PFR broj računa“: `requestedBy-signedBy-brojač`. */
    val invoiceNumber: String,
    val requestedBy: String? = null,
    val signedBy: String? = null,
    /** „PFR vreme“. */
    val sdcDateTime: String? = null,
    /** „Brojač računa“, npr. `14/17ОП`. */
    val invoiceCounter: String? = null,
    val invoiceCounterExtension: String? = null,
    val totalCounter: Long? = null,
    val transactionTypeCounter: Long? = null,
    val totalAmount: Decimal4? = null,
    val taxItems: List<PoreskaStavka> = emptyList(),
    val verificationUrl: String? = null,
    /** QR kod kao GIF slika, kodirana u Base64. */
    val verificationQRCode: String? = null,
    /** Tekst računa koji ESIR štampa bez izmena. */
    val journal: String? = null,
    val messages: String? = null,
    val encryptedInternalData: String? = null,
    val signature: String? = null,
    val taxGroupRevision: Int? = null,
    val businessName: String? = null,
    val tin: String? = null,
    val locationName: String? = null,
    val address: String? = null,
    val district: String? = null,
    val mrc: String? = null,
)

@Serializable
data class PoreskaStavka(
    val label: String,
    val amount: Decimal4,
    val rate: Decimal4? = null,
    val categoryName: String? = null,
    val categoryType: Int? = null,
)

/** Format greške koji PFR vraća kada odbije zahtev („Error Messages Format“). */
@Serializable
data class GreskaPfra(
    val message: String? = null,
    val modelState: List<GreskaPolja> = emptyList(),
)

@Serializable
data class GreskaPolja(
    /** Putanja do polja, npr. `items[0].labels[0]`; prazno ako greška nije vezana za polje. */
    val property: String = "",
    val errors: List<String> = emptyList(),
)
