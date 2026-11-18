package rs.omniit.hospes.esir

import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Podešavanja kase iz `config/local.json` (komandna linija i aplikacija koriste isti fajl).
 *
 * Fajl sadrži tajne (token u adresi, PIN, lozinku, PAC), zato `toString()` nikad ne ispisuje te vrednosti.
 * Fajl nije u Git-u.
 */
@Serializable
data class Podesavanja(
    val lpfr: LpfrPodesavanja? = null,
    val vpfr: VpfrPodesavanja? = null,
    /** PFR sa kojim kasa radi; bez ovoga: L-PFR ako je podešen, inače V-PFR. */
    val pfr: VrstaPfra? = null,
    /** Kasiri ove kase; ime izabranog kasira štampa se na računu („Касир“). */
    val kasiri: List<String> = emptyList(),
    /** Papir u štampaču. */
    val papir: SirinaPapira? = null,
    /** Član 6, stav 2 Pravilnika: kartica, ček i instant plaćanje se ne nude (unose se kao gotovina). */
    val ogranicenjePlacanja: OgranicenjePlacanja? = null,
    /** Tekst koji se štampa ispod „КРАЈ ФИСКАЛНОГ РАЧУНА“ (polje za reklamu; obavezno za Napredni ESIR). */
    val tekstIspodRacuna: String? = null,
    /** Serijski broj ove instalacije (dodeljuje se pri prvom pokretanju). */
    val serijskiBroj: String? = null,
) {
    /** PFR koji se koristi kada nije izričito izabran. */
    val izabraniPfr: VrstaPfra
        get() = pfr ?: if (lpfr == null && vpfr != null) VrstaPfra.VPFR else VrstaPfra.LPFR

    /** JSON za čuvanje (bez praznih polja). */
    fun uJson(): String = jsonZaCuvanje.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val jsonZaCuvanje = Json {
            prettyPrint = true
            explicitNulls = false
        }

        fun izJsona(tekst: String): Podesavanja = json.decodeFromString(serializer(), tekst)
    }
}

/** Serijski broj instalacije, npr. `HP-7KQ2-M9XD` (bez slova i cifara koje se lako pobrkaju: 0/O, 1/I). */
fun noviSerijskiBroj(slucajno: kotlin.random.Random = kotlin.random.Random.Default): String {
    val znaci = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    fun deo() = (1..4).map { znaci[slucajno.nextInt(znaci.length)] }.joinToString("")
    return "HP-${deo()}-${deo()}"
}

/** Pravila za ime kasira. */
object Kasiri {
    /** Najduže ime kasira koje se šalje PFR-u. */
    const val MAKS_DUZINA = 50

    /** Problem sa imenom kasira, ili `null` ako je ispravno. */
    fun proveri(ime: String): String? = when {
        ime.isBlank() -> "Unesi ime kasira"
        ime.trim().length > MAKS_DUZINA -> "Ime kasira može da ima najviše $MAKS_DUZINA znakova"
        else -> null
    }
}

@Serializable
data class LpfrPodesavanja(
    /** Adresa L-PFR-a koja se završava sa `/api`, npr. adresa razvojnog L-PFR-a sa portala. */
    val baseUrl: String,
    val pin: String? = null,
) {
    /** Spisak problema u podešavanjima; prazan spisak znači da je sve u redu. */
    fun proveri(): List<String> = buildList {
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            add("lpfr.baseUrl mora da počinje sa http:// ili https://")
        }
        if ('<' in baseUrl) add("lpfr.baseUrl još sadrži primer vrednosti iz example.json")
        if (!baseUrl.trimEnd('/').endsWith("/api")) add("lpfr.baseUrl mora da se završava sa /api")
        if (pin != null && !Regex("""\d{4}""").matches(pin)) add("lpfr.pin mora da ima tačno 4 cifre")
    }

    override fun toString(): String =
        "LpfrPodesavanja(adresa=${bezbedanPrikazAdrese(baseUrl)}, pin=${if (pin == null) "nije upisan" else "****"})"
}

@Serializable
data class VpfrPodesavanja(
    val baseUrl: String? = null,
    val pfxPath: String? = null,
    val pfxPassword: String? = null,
    val pac: String? = null,
) {
    /** Spisak problema u podešavanjima; prazan spisak znači da je sve u redu. Adresa nije obavezna (čita se iz sertifikata). */
    fun proveri(): List<String> = buildList {
        if (baseUrl != null && !baseUrl.startsWith("https://")) add("vpfr.baseUrl mora da počinje sa https://")
        if (pfxPath.isNullOrBlank()) add("vpfr.pfxPath nije upisan (putanja do .p12/.pfx fajla)")
        if (pfxPassword.isNullOrBlank()) add("vpfr.pfxPassword nije upisan")
        if (pac.isNullOrBlank()) add("vpfr.pac nije upisan")
        listOf(baseUrl, pfxPath, pfxPassword, pac).filterNotNull().forEach {
            if ('<' in it) add("vpfr još sadrži primer vrednosti iz example.json")
        }
    }.distinct()

    override fun toString(): String =
        "VpfrPodesavanja(adresa=${baseUrl?.let(::bezbedanPrikazAdrese)}, pfxPath=$pfxPath, lozinka=****, pac=****)"
}

/**
 * Prikaz adrese bez putanje, jer putanja razvojnog L-PFR-a sadrži tajni token.
 * Primer: `devesdc.sandbox.suf.purs.gov.rs:8888`.
 */
fun bezbedanPrikazAdrese(adresa: String): String = runCatching {
    val url = Url(adresa)
    "${url.host}:${url.port}"
}.getOrDefault("(neispravna adresa)")
