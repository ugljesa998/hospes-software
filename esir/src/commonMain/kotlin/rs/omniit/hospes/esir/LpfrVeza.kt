package rs.omniit.hospes.esir

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
}
