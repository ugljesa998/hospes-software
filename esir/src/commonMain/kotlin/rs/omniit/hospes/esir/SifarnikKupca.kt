package rs.omniit.hospes.esir

/**
 * Šifarnici za identifikaciju kupca iz PURS „Техничко упутство…“ v1.17, poglavlje 9.8.
 *
 * ID kupca i opciono polje kupca upisuju se kao `šifra:vrednost`, npr. `10:123456789`.
 */
object SifarnikKupca {

    /** Šifre za polje „ID kupca“ (`buyerId`). */
    val idKupca: Map<String, String> = mapOf(
        "10" to "PIB kupca (domaće pravno lice)",
        "11" to "JMBG (domaće fizičko lice koje obavlja samostalnu delatnost)",
        "12" to "PIB i JBKJS kupca (korisnik javnih sredstava), format PIB:JBKJS",
        "13" to "Kod penzionerske kartice",
        "14" to "PIB (poljoprivredno gazdinstvo, pravno lice)",
        "15" to "JMBG (poljoprivredno gazdinstvo, fizičko lice)",
        "16" to "BPG (broj poljoprivrednog gazdinstva)",
        "20" to "Broj lične karte (domaće fizičko lice)",
        "21" to "Broj izbegličke legitimacije",
        "22" to "EBS (strano fizičko lice sa prijavljenim boravkom)",
        "23" to "Broj pasoša (domaće fizičko lice)",
        "30" to "Broj pasoša (strano fizičko lice)",
        "31" to "Broj diplomatske legitimacije/LK",
        "32" to "Broj lične karte MKD",
        "33" to "Broj lične karte MNE",
        "34" to "Broj lične karte ALB",
        "35" to "Broj lične karte BIH",
        "36" to "Broj lične karte po Odluci (EU, Švajcarska, Norveška, Island)",
        "40" to "Poreski ID iz strane države (TIN)",
    )

    /** Šifre za „Opciono polje kupca“ (`buyerCostCenterId`). */
    val opcionoPoljeKupca: Map<String, String> = mapOf(
        "20" to "Broj SNPDV",
        "21" to "Broj LNPDV",
        "30" to "Broj PPO-PDV",
        "31" to "Broj ZPPO-PDV",
        "32" to "Broj MPPO-PDV",
        "33" to "Broj IPPO-PDV",
        "50" to "Broj korporacijske kartice (plaćanje je uvek vaučer)",
        "60" to "Vremenski period za zbirnu refundaciju korporacijskih kartica, format ddmmgggg_ddmmgggg",
    )

    private val FORMAT = Regex("""(\d{2}):(.+)""")

    /** Problem sa ID-om kupca, ili `null` ako je ispravan. */
    fun proveriIdKupca(vrednost: String): String? = proveri(vrednost, idKupca, "ID kupca", maksDuzina = 20)

    /** Problem sa opcionim poljem kupca, ili `null` ako je ispravno. */
    fun proveriOpcionoPolje(vrednost: String): String? =
        proveri(vrednost, opcionoPoljeKupca, "Opciono polje kupca", maksDuzina = 50)

    private fun proveri(vrednost: String, sifre: Map<String, String>, naziv: String, maksDuzina: Int): String? {
        val poklapanje = FORMAT.matchEntire(vrednost)
            ?: return "$naziv mora biti u formatu šifra:vrednost, npr. ${sifre.keys.first()}:123456789"
        val sifra = poklapanje.groupValues[1]
        return when {
            sifra !in sifre -> "$naziv: nepoznata šifra $sifra (dozvoljene: ${sifre.keys.joinToString(", ")})"
            vrednost.length > maksDuzina -> "$naziv može da ima najviše $maksDuzina znakova"
            else -> null
        }
    }
}
