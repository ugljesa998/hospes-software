package rs.omniit.hospes.esir

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Protocol
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/** HTTP klijent za desktop (Windows, macOS) i komandnu liniju, bez sertifikata (L-PFR). */
fun EsirHttp.napraviZaJvm(): HttpClient = napravi(OkHttp.create())

/**
 * HTTP klijent sa klijentskim sertifikatom, za V-PFR.
 *
 * Server V-PFR-a ima javni sertifikat, pa se proverava sistemskim skupom poverenja;
 * naš sertifikat (PKCS12, `.p12`/`.pfx`) se šalje pri TLS prijavi.
 *
 * Server traži klijentski sertifikat tek posle prvog zahteva, ponovnim TLS rukovanjem (renegotiation).
 * To nije dozvoljeno u HTTP/2, pa bi server odgovorio 403. Zato se koristi samo HTTP/1.1.
 */
fun EsirHttp.napraviZaJvmSaSertifikatom(sertifikat: KeyStore, lozinka: CharArray): HttpClient {
    val kljucevi = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        .apply { init(sertifikat, lozinka) }
    val poverenje = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().single()
    val ssl = SSLContext.getInstance("TLS").apply { init(kljucevi.keyManagers, arrayOf(poverenje), null) }
    return napravi(
        OkHttp.create {
            config {
                sslSocketFactory(ssl.socketFactory, poverenje)
                protocols(listOf(Protocol.HTTP_1_1))
            }
        },
    )
}

/** Podaci iz TaxCore sertifikata u `.p12`/`.pfx` fajlu. */
class TaxCoreP12(bajtovi: ByteArray, lozinka: CharArray) {
    val keyStore: KeyStore = KeyStore.getInstance("PKCS12").apply { load(bajtovi.inputStream(), lozinka) }

    /** Sertifikat sa privatnim ključem (naš, ne sertifikati Poreske iz lanca). */
    val sertifikat: X509Certificate = keyStore.aliases().toList()
        .filter { keyStore.isKeyEntry(it) }
        .firstNotNullOfOrNull { keyStore.getCertificate(it) as? X509Certificate }
        ?: throw IllegalArgumentException("U fajlu nema sertifikata sa privatnim ključem")

    /** Adresa V-PFR-a upisana u sertifikat (polje 7), npr. `https://vsdc.sandbox.suf.purs.gov.rs`. */
    val adresaVpfr: String? get() = polje(TaxCoreSertifikat.POLJE_ADRESA_VPFR)

    /** PIB upisan u sertifikat (polje 6). */
    val pib: String? get() = polje(TaxCoreSertifikat.POLJE_PIB)

    /** Serijski broj iz naziva sertifikata, isto što i UID/JID (npr. `ACN84XSJ`). */
    val uid: String?
        get() = Regex("""(?:SERIALNUMBER|OID\.2\.5\.4\.5)=([^,]+)""")
            .find(sertifikat.subjectX500Principal.getName(X500Principal.RFC1779))?.groupValues?.get(1)?.trim()

    private fun polje(broj: Int): String? {
        val oidovi = sertifikat.nonCriticalExtensionOIDs.orEmpty() + sertifikat.criticalExtensionOIDs.orEmpty()
        val oid = oidovi.firstOrNull { TaxCoreSertifikat.poljeIzOid(it) == broj } ?: return null
        return sertifikat.getExtensionValue(oid)?.let(TaxCoreSertifikat::tekstEkstenzije)
    }
}
