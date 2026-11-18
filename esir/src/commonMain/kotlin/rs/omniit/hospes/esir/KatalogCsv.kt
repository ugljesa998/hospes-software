package rs.omniit.hospes.esir

/**
 * Uvoz i izvoz liste artikala (ESIR upitnik, deo 11, P6) u CSV formatu koji se otvara u Excelu:
 * kolone odvojene tačkom-zarezom, decimale sa zarezom ili tačkom, UTF-8.
 *
 * ```
 * sifra;naziv;jedinica_mere;cena;oznaka;gtin;kategorija
 * 101;Espresso;kom;180,00;Ђ;;Kafa
 * ```
 */
object KatalogCsv {
    val ZAGLAVLJE = listOf("sifra", "naziv", "jedinica_mere", "cena", "oznaka", "gtin", "kategorija")

    /** Rezultat uvoza: ispravni artikli i greške po redovima (artikli sa greškom se preskaču). */
    data class Uvoz(val artikli: List<Artikal>, val greske: List<String>)

    fun izvoz(artikli: List<Artikal>): String = buildString {
        append(ZAGLAVLJE.joinToString(";")).append('\n')
        artikli.forEach { a ->
            append(
                listOf(a.sifra, a.naziv, a.jedinicaMere, a.cena.zaokruzi(2).zaStampuBezHiljada(), a.oznaka, a.gtin ?: "", a.kategorija ?: "")
                    .joinToString(";") { it.replace(";", ",") },
            ).append('\n')
        }
    }

    fun uvoz(tekst: String): Uvoz {
        val redovi = tekst.removePrefix("﻿").lines().map { it.trimEnd('\r') }
        val prvi = redovi.indexOfFirst { it.isNotBlank() }
        if (prvi < 0) return Uvoz(emptyList(), listOf("Fajl je prazan"))

        val zaglavlje = redovi[prvi].split(";").map { it.trim().lowercase() }
        val kolona = ZAGLAVLJE.associateWith { zaglavlje.indexOf(it) }
        val nedostaju = listOf("sifra", "naziv", "cena", "oznaka").filter { kolona[it] == -1 }
        if (nedostaju.isNotEmpty()) {
            return Uvoz(emptyList(), listOf("U zaglavlju nedostaju kolone: ${nedostaju.joinToString()} (očekivano: ${ZAGLAVLJE.joinToString(";")})"))
        }

        val artikli = mutableListOf<Artikal>()
        val greske = mutableListOf<String>()
        val vidjeneSifre = mutableSetOf<String>()
        for (i in prvi + 1 until redovi.size) {
            val red = redovi[i]
            if (red.isBlank()) continue
            val polja = red.split(";").map { it.trim() }
            fun polje(ime: String): String? = kolona[ime]?.takeIf { it >= 0 }?.let { polja.getOrNull(it) }?.takeIf { it.isNotEmpty() }

            val brojReda = i + 1
            try {
                val sifra = polje("sifra") ?: throw IllegalArgumentException("nema šifre")
                require(vidjeneSifre.add(sifra)) { "šifra $sifra se ponavlja u fajlu" }
                val artikal = Artikal(
                    sifra = sifra,
                    naziv = polje("naziv") ?: throw IllegalArgumentException("nema naziva"),
                    cena = Decimal4.parse((polje("cena") ?: throw IllegalArgumentException("nema cene")).bezHiljada()),
                    oznaka = polje("oznaka") ?: throw IllegalArgumentException("nema poreske oznake"),
                    jedinicaMere = polje("jedinica_mere") ?: "kom",
                    gtin = polje("gtin"),
                    kategorija = polje("kategorija"),
                )
                val problemi = artikal.proveri()
                require(problemi.isEmpty()) { problemi.joinToString("; ") }
                artikli += artikal
            } catch (e: IllegalArgumentException) {
                greske += "Red $brojReda: ${e.message}"
            }
        }
        return Uvoz(artikli, greske)
    }

    /** „1.234,50“ → „1234.50“; „1234.5“ ostaje. */
    private fun String.bezHiljada(): String =
        if (',' in this) replace(".", "").replace(',', '.') else this

    private fun Decimal4.zaStampuBezHiljada(): String = zaStampu().replace(".", "")
}

/** Uvozi artikle u katalog: novi se dodaju, postojeći (ista šifra) se menjaju. Vraća broj uvezenih. */
fun Katalog.uvezi(uvoz: KatalogCsv.Uvoz): Int {
    uvoz.artikli.forEach(::sacuvaj)
    return uvoz.artikli.size
}
