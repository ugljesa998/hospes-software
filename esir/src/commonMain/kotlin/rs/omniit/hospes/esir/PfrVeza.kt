package rs.omniit.hospes.esir

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
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
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException

/**
 * ESIR: veza sa PFR-om, bilo L-PFR-om u lokalu ([LpfrVeza]) ili V-PFR-om Poreske ([VpfrVeza]).
 *
 * Izdavanje računa je isto za oba („Create Invoice is the same for both V-SDCs and E-SDCs“),
 * pa ostatak ESIR-a ne mora da zna sa kojim PFR-om radi.
 */
interface PfrVeza {
    /** „L-PFR“ ili „V-PFR“, za poruke korisniku. */
    val naziv: String

    /** „Attention“: da li se PFR javlja. Vraća `false` umesto greške. */
    suspend fun attention(): Boolean

    /** „Get Status“: stanje PFR-a, šifre stanja i važeće poreske stope. */
    suspend fun status(): StatusOdgovor

    /**
     * „Create Invoice“: fiskalizacija računa, sa zaštitom od duplog računa preko [requestId].
     * @param jezik jezik teksta računa; mora biti među `supportedLanguages` iz statusa.
     */
    suspend fun izdajRacun(
        zahtev: ZahtevZaRacun,
        requestId: String = noviRequestId(),
        jezik: String? = JEZIK_SRPSKI_CIRILICA,
    ): RezultatRacuna

    /** „Get Last Signed Invoice“: račun po [requestId], ili `null` ako ga PFR nema. */
    suspend fun racunPoRequestId(requestId: String): FiskalizovanRacun?

    companion object {
        /** Jezik teksta računa: srpski, ćirilica (kao u primerima iz PURS uputstva). */
        const val JEZIK_SRPSKI_CIRILICA = "sr-Cyrl-RS"
    }
}

/**
 * Zajednička implementacija TaxCore v3 poziva („POS to SDC Protocol“) za L-PFR i V-PFR.
 *
 * @param osnovaApi adresa do `/api`, npr. `http://192.168.1.10:8888/api` ili `https://vsdc…/api`.
 */
abstract class OsnovnaPfrVeza(osnovaApi: String, protected val http: HttpClient) : PfrVeza {

    private val osnova = osnovaApi.trimEnd('/')

    protected fun adresa(putanja: String) = "$osnova/v3/$putanja"

    /** Zaglavlja koja se dodaju svakom zahtevu ka ovom PFR-u (npr. PAC za V-PFR). */
    protected open fun HttpRequestBuilder.zaglavljaPfra() {}

    override suspend fun attention(): Boolean = try {
        http.get(adresa("attention")) {
            accept(ContentType.Application.Json)
            zaglavljaPfra()
        }.status.isSuccess()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    override suspend fun status(): StatusOdgovor {
        val odgovor = pozovi {
            http.get(adresa("status")) {
                accept(ContentType.Application.Json)
                zaglavljaPfra()
            }
        }
        return procitaj(odgovor)
    }

    /**
     * 1. `attention`: ako se PFR ne javlja, račun se ne šalje.
     * 2. `POST invoices` sa zaglavljem `RequestId`.
     * 3. Ako veza pukne posle slanja, račun se traži po `RequestId` (`GET invoices/{requestId}`),
     *    umesto da se naslepo šalje ponovo.
     */
    override suspend fun izdajRacun(zahtev: ZahtevZaRacun, requestId: String, jezik: String?): RezultatRacuna {
        val problemi = zahtev.proveri()
        require(problemi.isEmpty()) { "Zahtev za račun nije ispravan: ${problemi.joinToString("; ")}" }
        require(FORMAT_REQUEST_ID.matches(requestId)) { "RequestId mora da ima 1–32 slova, cifre ili crtice" }

        if (!attention()) return RezultatRacuna.NijeIzdat(requestId, "$naziv se ne javlja, račun nije poslat")

        val odgovor = try {
            http.post(adresa("invoices")) {
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.Json)
                header(ZAGLAVLJE_REQUEST_ID, requestId)
                if (jezik != null) header(HttpHeaders.AcceptLanguage, jezik)
                zaglavljaPfra()
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

    override suspend fun racunPoRequestId(requestId: String): FiskalizovanRacun? {
        require(FORMAT_REQUEST_ID.matches(requestId)) { "RequestId mora da ima 1–32 slova, cifre ili crtice" }
        val odgovor = try {
            http.get(adresa("invoices/$requestId")) {
                accept(ContentType.Application.Json)
                zaglavljaPfra()
            }
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
            RezultatRacuna.NijeIzdat(requestId, "Veza je prekinuta (${uzrok.message}), a $naziv potvrđuje da račun nije izdat")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        RezultatRacuna.NepoznatoStanje(
            requestId,
            "Veza je prekinuta (${uzrok.message}), a provera po RequestId nije uspela (${e.message})",
        )
    }

    protected suspend fun pozovi(zahtev: suspend () -> HttpResponse): HttpResponse {
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

    protected suspend inline fun <reified T> procitaj(odgovor: HttpResponse): T = try {
        odgovor.body()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw EsirGreska.NeispravanOdgovor(e)
    }

    private companion object {
        const val ZAGLAVLJE_REQUEST_ID = "RequestId"
        val FORMAT_REQUEST_ID = Regex("""[A-Za-z0-9-]{1,32}""")
    }
}
