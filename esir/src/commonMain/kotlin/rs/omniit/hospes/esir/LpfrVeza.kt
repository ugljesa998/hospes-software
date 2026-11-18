package rs.omniit.hospes.esir

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException

/**
 * ESIR: veza sa L-PFR-om (E-SDC), prema TaxCore protokolu v3 („POS to SDC Protocol“).
 *
 * L-PFR ne traži prijavu sertifikatom; pristup se štiti mrežom i PIN-om kartice.
 *
 * @param baseUrl adresa koja se završava sa `/api`, npr. `http://192.168.1.10:8888/api`
 *   ili adresa razvojnog L-PFR-a sa portala Poreske.
 */
class LpfrVeza(baseUrl: String, private val http: HttpClient) {

    private val osnova = baseUrl.trimEnd('/')

    private fun adresa(putanja: String) = "$osnova/v3/$putanja"

    /**
     * „Attention“: provera da li je L-PFR dostupan. Šalje se pre PIN-a i pre izdavanja računa.
     * Vraća `false` umesto greške, jer je cilj samo provera.
     */
    suspend fun attention(): Boolean = try {
        http.get(adresa("attention")) { accept(ContentType.Application.Json) }.status.isSuccess()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** „Get Status“: stanje L-PFR-a, šifre stanja i važeće poreske stope. */
    suspend fun status(): StatusOdgovor {
        val odgovor = pozovi { http.get(adresa("status")) { accept(ContentType.Application.Json) } }
        return procitaj(odgovor)
    }

    /**
     * Bezbedan unos PIN-a, redosledom iz dokumentacije: `attention` → `status` → `pin`.
     *
     * PIN se šalje **najviše jednom** i samo ako ga L-PFR traži i ako je kartica u čitaču.
     * Posle 5 pogrešnih PIN-ova kartica se zaključava, zato ovde nema ponovnih pokušaja.
     */
    suspend fun unesiPin(pin: String): RezultatPina {
        proveriFormatPina(pin)
        if (!attention()) return RezultatPina.NijePoslat("L-PFR se ne javlja")

        val status = status()
        val blokirajuca = status.gsc.firstOrNull { it in SIFRE_KOJE_BLOKIRAJU_PIN }
        if (blokirajuca != null) {
            val sifra = SifreStanja.za(blokirajuca)
            return RezultatPina.NijePoslat(sifra.opis, sifra)
        }
        if (status.isPinRequired == false) return RezultatPina.NijePotreban

        val sifra = posaljiPin(pin)
        return if (sifra.kod == SIFRA_PIN_ISPRAVAN) RezultatPina.Prihvacen else RezultatPina.Odbijen(sifra)
    }

    /**
     * „Verify PIN“: šalje PIN tačno jednom i vraća šifru koju je L-PFR odgovorio.
     * Telo zahteva je samo PIN (4 cifre), a odgovor je šifra stanja, npr. `"0100"`.
     */
    suspend fun posaljiPin(pin: String): SifraStanja {
        proveriFormatPina(pin)
        val odgovor = pozovi {
            http.post(adresa("pin")) {
                accept(ContentType.Application.Json)
                setBody(TextContent(pin, ContentType.Application.Json))
            }
        }
        val kod = odgovor.bodyAsText().trim().removeSurrounding("\"")
        if (!FORMAT_PINA.matches(kod)) {
            throw EsirGreska.NeispravanOdgovor(IllegalStateException("L-PFR je na PIN odgovorio: '${kod.take(50)}'"))
        }
        return SifreStanja.za(kod)
    }

    /**
     * „Create Invoice“: fiskalizacija računa, sa zaštitom od duplog računa.
     *
     * 1. `attention`: ako se L-PFR ne javlja, račun se ne šalje.
     * 2. `POST invoices` sa zaglavljem `RequestId`.
     * 3. Ako veza pukne posle slanja, račun se traži po `RequestId` (`GET invoices/{requestId}`),
     *    umesto da se naslepo šalje ponovo.
     *
     * @param jezik jezik teksta računa; mora biti među `supportedLanguages` iz statusa.
     */
    suspend fun izdajRacun(
        zahtev: ZahtevZaRacun,
        requestId: String = noviRequestId(),
        jezik: String? = JEZIK_SRPSKI_CIRILICA,
    ): RezultatRacuna {
        val problemi = zahtev.proveri()
        require(problemi.isEmpty()) { "Zahtev za račun nije ispravan: ${problemi.joinToString("; ")}" }
        require(FORMAT_REQUEST_ID.matches(requestId)) { "RequestId mora da ima 1–32 slova, cifre ili crtice" }

        if (!attention()) return RezultatRacuna.NijeIzdat(requestId, "L-PFR se ne javlja, račun nije poslat")

        val odgovor = try {
            http.post(adresa("invoices")) {
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.Json)
                header(ZAGLAVLJE_REQUEST_ID, requestId)
                if (jezik != null) header(HttpHeaders.AcceptLanguage, jezik)
                setBody(zahtev)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return proveriPoslePrekida(requestId, e)
        }

        if (!odgovor.status.isSuccess()) {
            val telo = odgovor.bodyAsText()
            val greska = runCatching { EsirHttp.json.decodeFromString(GreskaPfra.serializer(), telo) }.getOrNull()
            return RezultatRacuna.Odbijen(requestId, odgovor.status.value, greska, telo.take(2000))
        }

        return try {
            RezultatRacuna.Izdat(requestId, odgovor.body())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // PFR je odgovorio uspešno, ali odgovor nije pročitan: račun je verovatno izdat, pa ga tražimo.
            proveriPoslePrekida(requestId, e)
        }
    }

    /**
     * „Get Last Signed Invoice“: traži račun po [requestId]. Vraća `null` ako L-PFR nema takav račun.
     */
    suspend fun racunPoRequestId(requestId: String): FiskalizovanRacun? {
        require(FORMAT_REQUEST_ID.matches(requestId)) { "RequestId mora da ima 1–32 slova, cifre ili crtice" }
        val odgovor = try {
            http.get(adresa("invoices/$requestId")) { accept(ContentType.Application.Json) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EsirGreska.PfrNedostupan(e)
        }
        if (odgovor.status == HttpStatusCode.NotFound || odgovor.status == HttpStatusCode.NoContent) return null
        val telo = odgovor.bodyAsText().trim()
        if (!odgovor.status.isSuccess()) throw EsirGreska.NeocekivanOdgovor(odgovor.status.value, telo.take(500))
        if (telo.isEmpty() || telo == "null") return null
        return try {
            EsirHttp.json.decodeFromString(FiskalizovanRacun.serializer(), telo)
        } catch (e: Exception) {
            throw EsirGreska.NeispravanOdgovor(e)
        }
    }

    private suspend fun proveriPoslePrekida(requestId: String, uzrok: Throwable): RezultatRacuna = try {
        val racun = racunPoRequestId(requestId)
        if (racun != null) {
            RezultatRacuna.Izdat(requestId, racun, pronadjenNaknadno = true)
        } else {
            RezultatRacuna.NijeIzdat(requestId, "Veza je prekinuta (${uzrok.message}), a L-PFR potvrđuje da račun nije izdat")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RezultatRacuna.NepoznatoStanje(
            requestId,
            "Veza je prekinuta (${uzrok.message}), a provera po RequestId nije uspela (${e.message})",
        )
    }

    private fun proveriFormatPina(pin: String) {
        require(FORMAT_PINA.matches(pin)) { "PIN mora da ima tačno 4 cifre" }
    }

    private suspend fun pozovi(zahtev: suspend () -> HttpResponse): HttpResponse {
        val odgovor = try {
            zahtev()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EsirGreska.PfrNedostupan(e)
        }
        if (!odgovor.status.isSuccess()) {
            throw EsirGreska.NeocekivanOdgovor(odgovor.status.value, odgovor.bodyAsText().take(500))
        }
        return odgovor
    }

    private suspend inline fun <reified T> procitaj(odgovor: HttpResponse): T = try {
        odgovor.body()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw EsirGreska.NeispravanOdgovor(e)
    }

    companion object {
        /** Jezik teksta računa: srpski, ćirilica (kao u primerima iz PURS uputstva). */
        const val JEZIK_SRPSKI_CIRILICA = "sr-Cyrl-RS"

        private const val ZAGLAVLJE_REQUEST_ID = "RequestId"
        private const val SIFRA_PIN_ISPRAVAN = "0100"
        private val FORMAT_PINA = Regex("""\d{4}""")
        private val FORMAT_REQUEST_ID = Regex("""[A-Za-z0-9-]{1,32}""")

        /** Kartica nije u čitaču, zaključana je ili L-PFR ne može da joj pristupi: PIN se tada ne šalje. */
        private val SIFRE_KOJE_BLOKIRAJU_PIN = setOf("1300", "2110", "2220")
    }
}
