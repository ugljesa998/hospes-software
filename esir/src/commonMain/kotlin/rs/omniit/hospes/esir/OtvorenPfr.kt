package rs.omniit.hospes.esir

import io.ktor.client.HttpClient

/** Sa kojim PFR-om ESIR radi. */
enum class VrstaPfra(val naziv: String) {
    LPFR("L-PFR"),
    VPFR("V-PFR"),
}

/**
 * Otvorena veza sa PFR-om zajedno sa HTTP klijentom koji treba zatvoriti posle upotrebe.
 * @property opis kratak opis za prikaz (adresa bez tajnog tokena, sertifikat, PIB).
 */
class OtvorenPfr(val veza: PfrVeza, private val http: HttpClient, val opis: String) : AutoCloseable {
    override fun close() = http.close()
}
