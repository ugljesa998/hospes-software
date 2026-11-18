package rs.omniit.hospes.esir

/*
 * Specijalni slučajevi iz PURS „Техничко упутство…“ v1.17, poglavlje 9.
 * (9.1 avans je u Avans.kt, 9.6 refundacija u IzdatRacun.kt, 9.8 šifarnik kupca u SifarnikKupca.kt.)
 */

/**
 * 9.3 Poništavanje računa u slučaju greške: Promet Prodaja ili Avans Prodaja sa greškom poništava se
 * refundacijom celog računa, sa **sopstvenim PIB-om** u polju ID kupca (`10:PIB`) i pozivom na pogrešan račun.
 * Posle toga se izdaje novi, ispravan račun.
 *
 * @param pibProdavca PIB obveznika (9 cifara); ako se ne navede, uzima se iz odgovora PFR-a (`tin`).
 */
/** Da li se ovaj račun može poništiti po 9.3 (Promet Prodaja ili Avans Prodaja). */
val IzdatRacun.mozePonistavanje: Boolean
    get() = zahtev.transactionType == TipTransakcije.PRODAJA &&
        (zahtev.invoiceType == VrstaRacuna.PROMET || zahtev.invoiceType == VrstaRacuna.AVANS)

fun IzdatRacun.zahtevZaPonistavanje(pibProdavca: String? = null): ZahtevZaRacun {
    require(zahtev.transactionType == TipTransakcije.PRODAJA) { "Poništava se račun prodaje, a ovo je $oznaka" }
    require(zahtev.invoiceType == VrstaRacuna.PROMET || zahtev.invoiceType == VrstaRacuna.AVANS) {
        "Poništava se Promet Prodaja ili Avans Prodaja, a ovo je $oznaka"
    }
    val pib = (pibProdavca ?: racun.tin)?.filter { it.isDigit() }
    require(pib != null && pib.length == 9) { "Za poništavanje treba PIB prodavca (9 cifara)" }
    return zahtev.copy(
        transactionType = TipTransakcije.REFUNDACIJA,
        buyerId = "10:$pib",
        buyerCostCenterId = null,
        referentDocumentNumber = racun.invoiceNumber,
        referentDocumentDT = racun.sdcDateTime,
        dateAndTimeOfIssue = null,
    )
}

/**
 * 9.4 Prodaja putem korporacijskih kartica: za svaku prodaju fiskalni račun sa ID kupca (`10:PIB kupca`),
 * opcionim poljem `50:broj kartice` i plaćanjem **uvek vaučer**. Jednom mesečno se izdaje zbirna Promet
 * Refundacija za sve takve račune istog kupca.
 */
object KorporativnaKartica {
    const val SIFRA_KARTICE = "50"
    const val SIFRA_PERIODA = "60"

    /** Zahtev za prodaju korporacijskom karticom. */
    fun zahtevZaProdaju(
        stavke: List<Stavka>,
        pibKupca: String,
        brojKartice: String,
        kasir: String? = null,
    ): ZahtevZaRacun {
        require(PIB.matches(pibKupca)) { "PIB kupca mora imati 9 cifara" }
        require(brojKartice.isNotBlank()) { "Broj korporacijske kartice je obavezan" }
        return ZahtevZaRacun(
            invoiceType = VrstaRacuna.PROMET,
            transactionType = TipTransakcije.PRODAJA,
            payment = listOf(Placanje(Obracun.ukupno(stavke), NacinPlacanja.VAUCER)),
            items = stavke,
            cashier = kasir,
            buyerId = "10:$pibKupca",
            buyerCostCenterId = "$SIFRA_KARTICE:$brojKartice",
        )
    }

    /** Da li je račun prodaja korporacijskom karticom datom kupcu. */
    fun jeProdajaKarticom(r: IzdatRacun, pibKupca: String): Boolean =
        r.zahtev.invoiceType == VrstaRacuna.PROMET &&
            r.zahtev.transactionType == TipTransakcije.PRODAJA &&
            r.zahtev.buyerId == "10:$pibKupca" &&
            r.zahtev.buyerCostCenterId?.startsWith("$SIFRA_KARTICE:") == true &&
            r.zahtev.payment.all { it.paymentType == NacinPlacanja.VAUCER }

    /**
     * Zbirna mesečna Promet Refundacija za kupca (9.4): poziva se na poslednji račun kupcu u periodu
     * (bez obzira na broj kartice), opciono polje je `60:ddmmgggg_ddmmgggg`, plaćanje vaučer.
     * Stavke se sabiraju po nazivu, ceni i poreskoj oznaci.
     *
     * @param racuniUPeriodu računi iz žurnala za taj period (filtriraju se samo prodaje karticom ovom kupcu).
     * @param od prvi dan perioda, `gggg-mm-dd`; [doDana] poslednji dan, uključujući.
     */
    fun zahtevZaMesecnuRefundaciju(
        racuniUPeriodu: List<IzdatRacun>,
        pibKupca: String,
        od: String,
        doDana: String,
        kasir: String? = null,
    ): ZahtevZaRacun {
        val racuni = racuniUPeriodu.filter { jeProdajaKarticom(it, pibKupca) }
            .filter { r -> r.datum.let { it != null && it >= od && it <= doDana } }
        require(racuni.isNotEmpty()) { "Nema prodaja korporacijskom karticom kupcu $pibKupca od $od do $doDana" }
        val poslednji = racuni.sortedWith(NAJNOVIJI_PRVO).first()

        val stavke = racuni.flatMap { it.zahtev.items }
            .groupBy { Triple(it.name, it.unitPrice, it.labels) }
            .map { (kljuc, grupa) ->
                Stavka(
                    name = kljuc.first,
                    quantity = grupa.fold(Decimal4.NULA) { a, s -> a + s.quantity },
                    unitPrice = kljuc.second,
                    totalAmount = grupa.fold(Decimal4.NULA) { a, s -> a + s.totalAmount },
                    labels = kljuc.third,
                )
            }
        return ZahtevZaRacun(
            invoiceType = VrstaRacuna.PROMET,
            transactionType = TipTransakcije.REFUNDACIJA,
            payment = listOf(Placanje(Obracun.ukupno(stavke), NacinPlacanja.VAUCER)),
            items = stavke,
            cashier = kasir,
            buyerId = "10:$pibKupca",
            buyerCostCenterId = oznakaPerioda(od, doDana),
            referentDocumentNumber = poslednji.racun.invoiceNumber,
            referentDocumentDT = poslednji.racun.sdcDateTime,
        )
    }

    /** Opciono polje kupca za zbirnu refundaciju: `60:ddmmgggg_ddmmgggg`. */
    fun oznakaPerioda(od: String, doDana: String): String = "$SIFRA_PERIODA:${uDdMmGggg(od)}_${uDdMmGggg(doDana)}"

    /** Prvi i poslednji dan meseca `gggg-mm`, npr. `2026-02` → `2026-02-01`, `2026-02-28`. */
    fun periodMeseca(mesec: String): Pair<String, String> {
        val delovi = Regex("""(\d{4})-(\d{2})""").matchEntire(mesec.trim())
            ?: throw IllegalArgumentException("Mesec mora biti u formatu gggg-mm, npr. 2026-11")
        val godina = delovi.groupValues[1].toInt()
        val m = delovi.groupValues[2].toInt()
        require(m in 1..12) { "Mesec mora biti od 01 do 12" }
        val prestupna = (godina % 4 == 0 && godina % 100 != 0) || godina % 400 == 0
        val dana = when (m) {
            2 -> if (prestupna) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        val mm = m.toString().padStart(2, '0')
        return "$godina-$mm-01" to "$godina-$mm-$dana"
    }

    private val PIB = Regex("""\d{9}""")

    private fun uDdMmGggg(datum: String): String {
        val (g, m, d) = datum.split("-")
        return "$d$m$g"
    }
}

/**
 * 9.5 Jednonamenski vaučer: prodaja vaučera se evidentira kao Promet Prodaja sa stavkom
 * `20: Једнонаменски ваучер (oznaka)`; na jednom računu najviše jedan vaučer po poreskoj stopi.
 * Kada se vaučer iskoristi, fiskalni račun se **ne izdaje**.
 */
object JednonamenskiVaucer {
    const val PREFIKS = "20: Једнонаменски ваучер"

    fun stavka(oznaka: String, iznos: Decimal4): Stavka {
        require(iznos > Decimal4.NULA) { "Iznos vaučera mora biti veći od nule" }
        return Stavka(
            name = "$PREFIKS ($oznaka)",
            quantity = Decimal4.parse("1"),
            unitPrice = iznos,
            totalAmount = iznos,
            labels = listOf(oznaka),
        )
    }

    fun jeVaucer(s: Stavka): Boolean = s.name.startsWith(PREFIKS)
}

/**
 * 9.7 Promet bez naknade (izjednačen sa prometom uz naknadu po Zakonu o PDV): ispred naziva svake stavke
 * šifra `00: `, a način plaćanja „drugo bezgotovinsko plaćanje“. U reklamno polje obavezno ide napomena.
 * Besplatni uzorci i reklamni materijal manje vrednosti se ne evidentiraju.
 */
object PrometBezNaknade {
    const val PREFIKS = "00: "
    const val NAPOMENA = "*Промет без накнаде"

    fun zahtev(stavke: List<Stavka>, kasir: String? = null, idKupca: String? = null): ZahtevZaRacun {
        val oznacene = stavke.map { s -> if (s.name.startsWith(PREFIKS)) s else s.copy(name = PREFIKS + s.name) }
        return ZahtevZaRacun(
            invoiceType = VrstaRacuna.PROMET,
            transactionType = TipTransakcije.PRODAJA,
            payment = listOf(Placanje(Obracun.ukupno(oznacene), NacinPlacanja.DRUGO_BEZGOTOVINSKO)),
            items = oznacene,
            cashier = kasir,
            buyerId = idKupca,
        )
    }

    fun jeBezNaknade(s: Stavka): Boolean = s.name.startsWith(PREFIKS)
}

/**
 * 9.6: posle refundacije uz povraćaj **gotovine** izdaje se Kopija Refundacije na kojoj se kupac svojeručno potpisuje.
 * Vraća red za potpis koji ESIR dodaje na takvu kopiju, ili `null` ako potpis nije potreban.
 */
fun ZahtevZaRacun.redZaPotpisKupca(): String? =
    if (invoiceType == VrstaRacuna.KOPIJA &&
        transactionType == TipTransakcije.REFUNDACIJA &&
        payment.any { it.paymentType == NacinPlacanja.GOTOVINA }
    ) {
        "Потпис купца: ______________________"
    } else {
        null
    }

/**
 * 9.2: kada je avans uplaćen virmanom pre dana izdavanja računa, „ESIR vreme“ je datum uplate.
 * @param datum datum uplate `gggg-mm-dd`.
 */
fun esirVremeZaDatumUplate(datum: String): String {
    require(Regex("""\d{4}-\d{2}-\d{2}""").matches(datum)) { "Datum uplate mora biti u formatu gggg-mm-dd" }
    return "${datum}T00:00:00"
}
