package rs.omniit.hospes.esir

import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Podešavanja ESIR-a iz `config/local.json`.
 *
 * Fajl sadrži tajne (token u adresi, PIN, lozinku, PAC), zato `toString()` nikad ne ispisuje te vrednosti.
 */
@Serializable
data class Podesavanja(
    val lpfr: LpfrPodesavanja? = null,
    val vpfr: VpfrPodesavanja? = null,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun izJsona(tekst: String): Podesavanja = json.decodeFromString(serializer(), tekst)
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
