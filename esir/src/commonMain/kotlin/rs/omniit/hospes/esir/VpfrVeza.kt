package rs.omniit.hospes.esir

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header

/**
 * ESIR: veza sa V-PFR-om (V-SDC) Poreske uprave, preko interneta.
 *
 * Prijava se radi klijentskim sertifikatom (TLS), a uz svaki zahtev ide i PAC.
 * Sertifikat se podešava u HTTP klijentu (npr. `EsirHttp.napraviZaJvmSaSertifikatom`), ne ovde.
 * V-PFR nema PIN.
 *
 * @param adresaVpfr adresa V-PFR-a bez `/api`, npr. `https://vsdc.sandbox.suf.purs.gov.rs`.
 *   Dokumentacija traži da se ona ne upisuje u kod, nego čita iz sertifikata (vidi [TaxCoreSertifikat]) ili podešavanja.
 * @param pac PAC koji pripada sertifikatu.
 */
class VpfrVeza(adresaVpfr: String, private val pac: String, http: HttpClient) :
    OsnovnaPfrVeza(adresaVpfr.trimEnd('/') + "/api", http) {

    init {
        require(pac.isNotBlank()) { "V-PFR traži PAC" }
    }

    override val naziv: String = "V-PFR"

    override fun HttpRequestBuilder.zaglavljaPfra() {
        header(ZAGLAVLJE_PAC, pac)
    }

    override fun toString(): String = "VpfrVeza(pac=****)"

    private companion object {
        const val ZAGLAVLJE_PAC = "PAC"
    }
}

/**
 * Posebna polja koja TaxCore upisuje u sertifikat, kao ekstenzije sa OID-om
 * `1.3.6.1.4.1.49952.{okruženje}.{država}.{polje}` (Srbija = 8; sandbox = 5, produkcija = 3).
 */
object TaxCoreSertifikat {
    const val OID_PREFIKS = "1.3.6.1.4.1.49952"
    const val POLJE_TAXCORE_API = 5
    const val POLJE_PIB = 6
    const val POLJE_ADRESA_VPFR = 7

    private val FORMAT_OID = Regex("""1\.3\.6\.1\.4\.1\.49952\.(\d+)\.(\d+)\.(\d+)""")

    /** Broj polja iz OID-a (npr. 7 za adresu V-PFR-a), ili `null` ako OID nije TaxCore polje. */
    fun poljeIzOid(oid: String): Int? = FORMAT_OID.matchEntire(oid)?.groupValues?.get(3)?.toIntOrNull()

    /**
     * Tekst iz vrednosti ekstenzije, onako kako je vraća `X509Certificate.getExtensionValue`:
     * DER OCTET STRING, u kome je ili sam tekst ili DER tekstualni tip (UTF8String, IA5String, PrintableString).
     */
    fun tekstEkstenzije(der: ByteArray): String {
        var sadrzaj = procitajTlv(der, ocekivaniTag = OCTET_STRING) ?: der
        if (sadrzaj.isNotEmpty() && sadrzaj[0].toInt() in TEKSTUALNI_TAGOVI) {
            sadrzaj = procitajTlv(sadrzaj, ocekivaniTag = sadrzaj[0].toInt()) ?: sadrzaj
        }
        return sadrzaj.decodeToString().trim()
    }

    private const val OCTET_STRING = 0x04
    private val TEKSTUALNI_TAGOVI = setOf(0x0C, 0x13, 0x16)

    /** Sadržaj jednog DER elementa (tag, dužina, vrednost), ili `null` ako bajtovi nisu takav element. */
    private fun procitajTlv(b: ByteArray, ocekivaniTag: Int): ByteArray? {
        if (b.size < 2 || b[0].toInt() != ocekivaniTag) return null
        val prvi = b[1].toInt() and 0xFF
        val (duzina, pocetak) = if (prvi < 0x80) {
            prvi to 2
        } else {
            val brojBajtova = prvi and 0x7F
            if (brojBajtova !in 1..3 || b.size < 2 + brojBajtova) return null
            var d = 0
            for (i in 0 until brojBajtova) d = (d shl 8) or (b[2 + i].toInt() and 0xFF)
            d to 2 + brojBajtova
        }
        if (pocetak + duzina != b.size) return null
        return b.copyOfRange(pocetak, pocetak + duzina)
    }
}
