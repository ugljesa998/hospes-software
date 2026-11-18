package rs.omniit.hospes.esir

enum class VrstaSifre { INFO, UPOZORENJE, GRESKA }

/**
 * Jedna šifra stanja iz TaxCore dokumentacije („Status and Error Codes“).
 *
 * @property trajna `true` za greške koje se ne rešavaju ponovnim pokušajem (npr. zaključana kartica).
 */
data class SifraStanja(
    val kod: String,
    val vrsta: VrstaSifre,
    val opis: String,
    val trajna: Boolean = false,
)

/** Opšte šifre stanja koje L-PFR i V-PFR vraćaju ESIR-u, sa opisom na srpskom. */
object SifreStanja {
    private val poznate: Map<String, SifraStanja> = listOf(
        SifraStanja("0000", VrstaSifre.INFO, "Sve je u redu"),
        SifraStanja("0100", VrstaSifre.INFO, "PIN je ispravan"),
        SifraStanja("0210", VrstaSifre.INFO, "Internet je dostupan"),
        SifraStanja("0220", VrstaSifre.INFO, "Internet nije dostupan"),
        SifraStanja("1100", VrstaSifre.UPOZORENJE, "Skladište audit paketa je 90% puno, potreban je audit"),
        SifraStanja("1300", VrstaSifre.UPOZORENJE, "Kartica (bezbednosni element) nije u čitaču"),
        SifraStanja("1400", VrstaSifre.UPOZORENJE, "Potreban je audit (dostignuto 75% limita kartice)"),
        SifraStanja("1500", VrstaSifre.UPOZORENJE, "Potreban je PIN"),
        SifraStanja("1999", VrstaSifre.UPOZORENJE, "Nedefinisano upozorenje"),
        SifraStanja("2100", VrstaSifre.GRESKA, "PIN nije ispravan"),
        SifraStanja("2110", VrstaSifre.GRESKA, "Kartica je zaključana zbog previše pogrešnih PIN-ova", trajna = true),
        SifraStanja("2210", VrstaSifre.GRESKA, "Bezbednosni element je zaključan, potreban je audit"),
        SifraStanja("2220", VrstaSifre.GRESKA, "Nema komunikacije sa bezbednosnim elementom"),
        SifraStanja("2230", VrstaSifre.GRESKA, "Bezbednosni element ne podržava traženu verziju protokola"),
        SifraStanja("2310", VrstaSifre.GRESKA, "Neispravne poreske oznake"),
        SifraStanja("2400", VrstaSifre.GRESKA, "PFR nije potpuno podešen (poreske stope ili adresa za proveru)"),
        SifraStanja("2800", VrstaSifre.GRESKA, "Nedostaje obavezno polje"),
        SifraStanja("2801", VrstaSifre.GRESKA, "Vrednost polja je predugačka"),
        SifraStanja("2802", VrstaSifre.GRESKA, "Vrednost polja je prekratka"),
        SifraStanja("2803", VrstaSifre.GRESKA, "Neispravna dužina polja"),
        SifraStanja("2804", VrstaSifre.GRESKA, "Vrednost polja je van dozvoljenog opsega"),
        SifraStanja("2805", VrstaSifre.GRESKA, "Neispravna vrednost polja"),
        SifraStanja("2806", VrstaSifre.GRESKA, "Neispravan format podataka"),
        SifraStanja("2807", VrstaSifre.GRESKA, "Lista je prekratka (nema stavki ili poreskih oznaka)"),
        SifraStanja("2808", VrstaSifre.GRESKA, "Lista je predugačka"),
        SifraStanja("2809", VrstaSifre.GRESKA, "Sertifikat bezbednosnog elementa je istekao", trajna = true),
    ).associateBy { it.kod }

    /** Opis šifre. Nepoznate šifre (npr. šifre proizvođača) dobijaju vrstu po prvoj cifri. */
    fun za(kod: String): SifraStanja = poznate[kod] ?: SifraStanja(
        kod = kod,
        vrsta = when (kod.firstOrNull()) {
            '0' -> VrstaSifre.INFO
            '1' -> VrstaSifre.UPOZORENJE
            else -> VrstaSifre.GRESKA
        },
        opis = "Nepoznata šifra",
    )
}
