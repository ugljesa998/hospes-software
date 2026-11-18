package rs.omniit.hospes.esir

/** Osnovni podaci o ESIR-u (prikazuju se u „O programu“, upitnik: operativne funkcije, pitanje 6). */
object Esir {
    /** Naziv proizvoda, isti kao u prijavi 1795 na portalu Poreske uprave. */
    const val PROIZVOD = "Hospes POS"

    const val PROIZVODJAC = "OmniIT"

    /** Verzija softvera, ista kao u prijavi 1795 na portalu Poreske uprave. */
    const val VERSION = "0.0.1"

    /** Verzija TaxCore protokola između ESIR-a i PFR-a koja se koristi u Srbiji. */
    const val PROTOCOL = "v3"
}
