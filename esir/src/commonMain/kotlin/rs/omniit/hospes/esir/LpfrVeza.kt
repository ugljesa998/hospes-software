package rs.omniit.hospes.esir

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent

/**
 * ESIR: veza sa L-PFR-om (E-SDC) u lokalu.
 *
 * L-PFR ne traži prijavu sertifikatom; pristup se štiti mrežom i PIN-om kartice.
 *
 * @param baseUrl adresa koja se završava sa `/api`, npr. `http://192.168.1.10:8888/api`
 *   ili adresa razvojnog L-PFR-a sa portala Poreske.
 */
class LpfrVeza(baseUrl: String, http: HttpClient) : OsnovnaPfrVeza(baseUrl, http) {

    override val naziv: String = "L-PFR"

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

    private fun proveriFormatPina(pin: String) {
        require(FORMAT_PINA.matches(pin)) { "PIN mora da ima tačno 4 cifre" }
    }

    private companion object {
        const val SIFRA_PIN_ISPRAVAN = "0100"
        val FORMAT_PINA = Regex("""\d{4}""")

        /** Kartica nije u čitaču, zaključana je ili L-PFR ne može da joj pristupi: PIN se tada ne šalje. */
        val SIFRE_KOJE_BLOKIRAJU_PIN = setOf("1300", "2110", "2220")
    }
}
