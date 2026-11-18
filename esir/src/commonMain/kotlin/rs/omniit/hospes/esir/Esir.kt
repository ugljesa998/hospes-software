package rs.omniit.hospes.esir

/** Osnovni podaci o ESIR-u (prikazuju se u „O programu“, upitnik: operativne funkcije, pitanje 6). */
object Esir {
    /** Naziv proizvoda, isti kao u prijavi 1795 na portalu Poreske uprave. */
    const val PROIZVOD = "Hospes POS"

    const val PROIZVODJAC = "OmniIT"

    /** Verzija softvera, ista kao u prijavi 1795 na portalu Poreske uprave. */
    const val VERSION = "0.0.1"

    /** ИБ број prijave ESIR-a na portalu Poreske uprave („Моје пријаве за одобрење“). */
    const val IB_BROJ = "1795"

    /**
     * „ЕСИР број“ na svakom računu: ИБ број/софтверска верзија (PURS tehničko uputstvo v1.17, upitnik
     * „Текстуални приказ“, pitanje 5, primer „123/1.0“). Šalje se PFR-u u polju `invoiceNumber`.
     * Nova verzija posle odobrenja ide na novu prijavu (uputstvo 7.9), pa se ovde menjaju oba dela.
     */
    const val BROJ = "$IB_BROJ/$VERSION"

    /** Verzija TaxCore protokola između ESIR-a i PFR-a koja se koristi u Srbiji. */
    const val PROTOCOL = "v3"
}
