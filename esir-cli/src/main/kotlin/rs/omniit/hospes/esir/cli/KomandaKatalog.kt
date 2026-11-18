package rs.omniit.hospes.esir.cli

import rs.omniit.hospes.esir.Artikal
import rs.omniit.hospes.esir.Decimal4
import rs.omniit.hospes.esir.FajlKatalog
import rs.omniit.hospes.esir.KatalogCsv
import rs.omniit.hospes.esir.KatalogCsvFajl
import rs.omniit.hospes.esir.PoreskaStopa
import rs.omniit.hospes.esir.Stavka
import rs.omniit.hospes.esir.poSifriIliGtinu
import rs.omniit.hospes.esir.pretrazi
import rs.omniit.hospes.esir.promeniCenu
import rs.omniit.hospes.esir.uvezi
import java.io.File

/** Katalog artikala komandne linije: `podaci/katalog.json` (nije u Git-u). */
internal val katalog by lazy { FajlKatalog(File("podaci/katalog.json")) }

/**
 * `katalog [--trazi tekst]` · `katalog dodaj --sifra 101 --naziv 'Espresso' --cena 180 --oznaka A [--jm kom] [--gtin …] [--kategorija …]`
 * · `katalog cena <šifra> <cena>` · `katalog ukloni <šifra>` · `katalog uvoz <fajl.csv>` · `katalog izvoz <fajl.csv>`
 */
internal fun komandaKatalog(argumenti: List<String>) {
    when (argumenti.getOrNull(1)?.takeUnless { it.startsWith("--") }) {
        null -> ispisiKatalog(vrednostOpcije(argumenti, "--trazi"))
        "dodaj" -> dodajArtikal(argumenti)
        "cena" -> {
            val sifra = argumenti.getOrNull(2) ?: prekini("Upotreba: katalog cena <šifra> <nova cena>")
            val cena = broj(argumenti.getOrNull(3) ?: prekini("Upotreba: katalog cena <šifra> <nova cena>"), "cena")
            val stara = katalog.nadji(sifra)?.cena
            val novi = try {
                katalog.promeniCenu(sifra, cena)
            } catch (e: IllegalArgumentException) {
                prekini(e.message ?: e.toString())
            }
            println("Cena artikla ${novi.sifra} „${novi.naziv}“: ${stara?.zaStampu()} → ${novi.cena.zaStampu()} RSD")
        }
        "ukloni" -> {
            val sifra = argumenti.getOrNull(2) ?: prekini("Upotreba: katalog ukloni <šifra>")
            if (katalog.ukloni(sifra)) println("Artikal $sifra je uklonjen.") else prekini("Nema artikla sa šifrom $sifra")
        }
        "uvoz" -> {
            val fajl = File(argumenti.getOrNull(2) ?: prekini("Upotreba: katalog uvoz <fajl.csv>"))
            if (!fajl.exists()) prekini("Nema fajla ${fajl.path}")
            val uvoz = KatalogCsv.uvoz(KatalogCsvFajl.procitaj(fajl))
            val broj = try {
                katalog.uvezi(uvoz)
            } catch (e: IllegalArgumentException) {
                prekini(e.message ?: e.toString())
            }
            println("Uvezeno $broj artikala iz ${fajl.path}.")
            if (uvoz.greske.isNotEmpty()) {
                println("Preskočeno ${uvoz.greske.size} redova:")
                uvoz.greske.forEach { println("  $it") }
            }
        }
        "izvoz" -> {
            val fajl = File(argumenti.getOrNull(2) ?: prekini("Upotreba: katalog izvoz <fajl.csv>"))
            KatalogCsvFajl.upisi(fajl, KatalogCsv.izvoz(katalog.svi()))
            println("Izvezeno ${katalog.svi().size} artikala u ${fajl.path} (otvara se u Excelu).")
        }
        else -> prekini("Nepoznata komanda kataloga. Dozvoljeno: dodaj, cena, ukloni, uvoz, izvoz")
    }
}

private fun ispisiKatalog(trazi: String?) {
    val artikli = if (trazi == null) katalog.svi() else katalog.pretrazi(trazi)
    println("Katalog (${katalog.fajl.path}): ${artikli.size} artikala${trazi?.let { ", pretraga „$it“" } ?: ""}")
    if (artikli.isEmpty()) {
        println("Uvezi primer:  ./gradlew -q :esir-cli:run --args=\"katalog uvoz config/katalog-primer.csv\"")
        return
    }
    println()
    println("Šifra".padEnd(8) + "Naziv na računu".padEnd(32) + "Cena".padStart(12) + "  Ozn.  " + "GTIN".padEnd(15) + "Kategorija")
    artikli.forEach { a ->
        println(
            a.sifra.padEnd(8) + a.nazivNaRacunu.take(31).padEnd(32) + a.cena.zaStampu().padStart(12) +
                "  ${a.oznaka.padEnd(4)}  " + (a.gtin ?: "").padEnd(15) + (a.kategorija ?: ""),
        )
    }
}

private fun dodajArtikal(argumenti: List<String>) {
    val artikal = Artikal(
        sifra = vrednostOpcije(argumenti, "--sifra") ?: prekini("Navedi --sifra"),
        naziv = vrednostOpcije(argumenti, "--naziv") ?: prekini("Navedi --naziv"),
        cena = broj(vrednostOpcije(argumenti, "--cena") ?: prekini("Navedi --cena"), "cena"),
        oznaka = vrednostOpcije(argumenti, "--oznaka") ?: prekini("Navedi --oznaka (poreska oznaka, npr. A u sandbox-u)"),
        jedinicaMere = vrednostOpcije(argumenti, "--jm") ?: "kom",
        gtin = vrednostOpcije(argumenti, "--gtin"),
        kategorija = vrednostOpcije(argumenti, "--kategorija"),
    )
    val postojao = katalog.nadji(artikal.sifra) != null
    try {
        katalog.sacuvaj(artikal)
    } catch (e: IllegalArgumentException) {
        prekini(e.message ?: e.toString())
    }
    println("${if (postojao) "Izmenjen" else "Dodat"} artikal ${artikal.sifra}: ${artikal.nazivNaRacunu}, ${artikal.cena.zaStampu()} RSD, oznaka ${artikal.oznaka}")
}

/**
 * Stavka iz kataloga za račun: „šifra ili GTIN[;količina[;cena[;popust%]]]“, npr. „101;2“ ili „5449000000996“.
 * Cena se može promeniti na samoj prodaji. Poreska oznaka artikla mora postojati među stopama PFR-a.
 */
internal fun stavkaIzKataloga(opis: String, stope: List<PoreskaStopa>): Stavka {
    val delovi = opis.split(";").map { it.trim() }
    val artikal = katalog.poSifriIliGtinu(delovi[0])
        ?: prekini("U katalogu nema artikla sa šifrom ili GTIN-om „${delovi[0]}“. Spisak: katalog")
    if (stope.none { it.label == artikal.oznaka }) {
        prekini(
            "Artikal ${artikal.sifra} ima poresku oznaku ${artikal.oznaka}, a PFR je ne vraća. " +
                "Dostupne: ${stope.joinToString(", ") { "${it.label} (${it.rate}%)" }}. Promeni oznaku u katalogu.",
        )
    }
    return try {
        artikal.uStavku(
            kolicina = delovi.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { broj(it, "količina") } ?: Decimal4.parse("1"),
            cena = delovi.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let { broj(it, "cena") } ?: artikal.cena,
            popustProcenat = delovi.getOrNull(3)?.takeIf { it.isNotEmpty() }?.removeSuffix("%")?.let { broj(it, "popust") }
                ?: Decimal4.NULA,
        )
    } catch (e: IllegalArgumentException) {
        prekini("Artikal „$opis“: ${e.message}")
    }
}
