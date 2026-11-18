package rs.omniit.hospes.esir.cli

import rs.omniit.hospes.esir.Avans
import rs.omniit.hospes.esir.AvansnaUplata
import rs.omniit.hospes.esir.Decimal4
import rs.omniit.hospes.esir.NacinPlacanja
import rs.omniit.hospes.esir.Placanje
import rs.omniit.hospes.esir.PoreskaStopa
import rs.omniit.hospes.esir.SifraAvansa
import rs.omniit.hospes.esir.StatusOdgovor
import rs.omniit.hospes.esir.Stavka
import rs.omniit.hospes.esir.naziv

/**
 * `avans --iznos 1000 [--oznaka A] [--sifra 11] [--placanje prenos] [--kupac 10:123456789] [--prethodni <broj>]`
 *
 * Izdaje Avans Prodaju. Ako je naveden `--prethodni`, novi avans se poziva na prethodni (isti posao).
 */
internal suspend fun komandaAvans(argumenti: List<String>) {
    val iznos = iznosIzOpcije(argumenti)
    val nacin = nacinPlacanja(argumenti) ?: NacinPlacanja.PRENOS_NA_RACUN
    val kupac = vrednostOpcije(argumenti, "--kupac")
    val prethodni = vrednostOpcije(argumenti, "--prethodni")?.let(::ucitajIzdat)

    saPfrom(argumenti) { veza, status ->
        val stopa = stopaIzOpcije(argumenti, status)
        val sifra = sifraAvansa(argumenti, stopa)
        val zahtev = try {
            Avans.zahtevZaAvans(
                uplate = listOf(AvansnaUplata(sifra, stopa.label, iznos)),
                placanje = listOf(Placanje(iznos, nacin)),
                idKupca = kupac,
                prethodni = prethodni,
                kasir = "Hospes test",
            )
        } catch (e: IllegalArgumentException) {
            prekini(e.message ?: e.toString())
        }
        val opisPrethodnog = prethodni?.let { ", poziva se na ${it.racun.invoiceNumber}" } ?: ", prvi avans"
        izdajIPrikazi(
            veza, status, zahtev,
            "Avans Prodaja $iznos RSD (${sifra.kod}: ${sifra.opis}), ${nacin.naziv.lowercase()}$opisPrethodnog",
        )
    }
}

/**
 * `zatvori-avans <broj poslednje Avans Prodaje> [--kupac 10:123456789]`
 *
 * Izdaje Avans Refundaciju za zbir svih avansa iz lanca. Ovaj račun se ne daje kupcu.
 */
internal suspend fun komandaZatvoriAvans(argumenti: List<String>) {
    val broj = argumenti.getOrNull(1)?.takeUnless { it.startsWith("--") }
        ?: prekini("Upotreba: zatvori-avans <broj poslednje Avans Prodaje> [--kupac 10:123456789]")
    val kupac = vrednostOpcije(argumenti, "--kupac")
    val (lanac, zahtev) = try {
        val lanac = Avans.lanacAvansa(ucitajIzdat(broj), ::nadjiSacuvan)
        lanac to Avans.zahtevZaZatvaranje(lanac, idKupca = kupac)
    } catch (e: IllegalArgumentException) {
        prekini(e.message ?: e.toString())
    }

    println("Lanac avansa (${lanac.size}): ${lanac.joinToString(" → ") { it.racun.invoiceNumber }}")
    saPfrom(argumenti) { veza, status ->
        val ukupno = zahtev.items.fold(Decimal4.NULA) { a, s -> a + s.totalAmount }
        izdajIPrikazi(
            veza, status, zahtev,
            "Avans Refundacija (zatvaranje avansa) $ukupno RSD, poziva se na $broj. Ne daje se kupcu.",
        )
    }
}

/**
 * `konacni-racun <broj Avans Refundacije> --iznos 2500 [--naziv "Najam vozila"] [--oznaka A] [--placanje kartica]`
 *
 * Izdaje konačni račun (Promet Prodaja) posle zatvaranja avansa. PFR-u se šalje samo ostatak za plaćanje.
 */
internal suspend fun komandaKonacniRacun(argumenti: List<String>) {
    val broj = argumenti.getOrNull(1)?.takeUnless { it.startsWith("--") }
        ?: prekini("Upotreba: konacni-racun <broj Avans Refundacije> --iznos 2500 [--naziv \"...\"] [--placanje kartica]")
    val iznos = iznosIzOpcije(argumenti)
    val naziv = vrednostOpcije(argumenti, "--naziv") ?: "Test usluga"
    val nacin = nacinPlacanja(argumenti) ?: NacinPlacanja.PLATNA_KARTICA
    val zatvaranje = ucitajIzdat(broj)

    saPfrom(argumenti) { veza, status ->
        val stopa = stopaIzOpcije(argumenti, status)
        val konacni = try {
            Avans.konacniRacun(
                zatvaranje = zatvaranje,
                stavke = listOf(Stavka(naziv, Decimal4.parse("1"), iznos, iznos, listOf(stopa.label))),
                nacinPlacanja = nacin,
            )
        } catch (e: IllegalArgumentException) {
            prekini(e.message ?: e.toString())
        }
        red("Plaćeno avansom", "${konacni.placenoAvansom} RSD")
        red("Preostalo", "${konacni.preostaloZaPlacanje} RSD (${nacin.naziv.lowercase()}), samo ovo ide PFR-u kao plaćanje")
        izdajIPrikazi(
            veza, status, konacni.zahtev,
            "Promet Prodaja (konačni račun) „$naziv“ $iznos RSD, poziva se na $broj",
            dodatak = konacni.dodatniRedovi + listOf("Рекламно поље: ${konacni.reklamnoPolje}"),
        )
    }
}

private fun iznosIzOpcije(argumenti: List<String>): Decimal4 {
    val tekst = vrednostOpcije(argumenti, "--iznos") ?: prekini("Navedi iznos, npr. --iznos 1000")
    val iznos = runCatching { Decimal4.parse(tekst) }.getOrElse { prekini("Neispravan iznos: $tekst") }
    if (iznos <= Decimal4.NULA) prekini("Iznos mora biti veći od nule")
    return iznos
}

private fun stopaIzOpcije(argumenti: List<String>, status: StatusOdgovor): PoreskaStopa {
    val oznaka = vrednostOpcije(argumenti, "--oznaka") ?: "A"
    val stope = status.currentTaxRates?.taxCategories.orEmpty().flatMap { it.taxRates }
    return stope.firstOrNull { it.label == oznaka }
        ?: prekini("Poreska oznaka $oznaka ne postoji. Dostupne: ${stope.joinToString(", ") { "${it.label} (${it.rate}%)" }}")
}

/**
 * Šifra avansa (10–13). Ako nije navedena, određuje se po stopi: 20% → 10, 10% → 11, 0% → 12.
 * U sandbox okruženju stope su drugačije nego u produkciji, pa se šifra po potrebi navodi sa `--sifra`.
 */
private fun sifraAvansa(argumenti: List<String>, stopa: PoreskaStopa): SifraAvansa {
    vrednostOpcije(argumenti, "--sifra")?.let { tekst ->
        return tekst.toIntOrNull()?.let(SifraAvansa::zaKod)
            ?: prekini("Nepoznata šifra avansa $tekst. Dozvoljeno: ${SifraAvansa.entries.joinToString { "${it.kod}" }}")
    }
    return when (stopa.rate.toString()) {
        "20" -> SifraAvansa.OPSTA_STOPA
        "10" -> SifraAvansa.POSEBNA_STOPA
        "0" -> SifraAvansa.OSLOBODJEN_PDV
        else -> prekini(
            "Za stopu ${stopa.rate}% (oznaka ${stopa.label}) ne može da se odredi šifra avansa. Navedi je, npr. --sifra 10",
        )
    }
}
