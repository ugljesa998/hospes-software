package rs.omniit.hospes.esir

/** Greške u komunikaciji ESIR-a sa PFR-om. */
sealed class EsirGreska(poruka: String, uzrok: Throwable? = null) : Exception(poruka, uzrok) {

    /** PFR se ne javlja: nema mreže, pogrešna adresa ili je PFR ugašen. */
    class PfrNedostupan(uzrok: Throwable) :
        EsirGreska("PFR nije dostupan: ${uzrok.message ?: uzrok::class.simpleName}", uzrok)

    /** PFR je odgovorio HTTP statusom koji nije uspešan. */
    class NeocekivanOdgovor(val httpStatus: Int, val telo: String) :
        EsirGreska("PFR je vratio HTTP $httpStatus")

    /** PFR je odgovorio, ali odgovor nije u formatu koji očekujemo. */
    class NeispravanOdgovor(uzrok: Throwable) :
        EsirGreska("Odgovor PFR-a nije u očekivanom formatu: ${uzrok.message}", uzrok)
}
