package rs.omniit.hospes.esir.cli

import rs.omniit.hospes.esir.Esir

fun main(args: Array<String>) {
    println("Hospes ESIR ${Esir.VERSION} (TaxCore ${Esir.PROTOCOL})")

    when (val komanda = args.firstOrNull()) {
        null, "pomoc" -> ispisiPomoc()
        else -> {
            println("Nepoznata komanda: $komanda")
            ispisiPomoc()
        }
    }
}

private fun ispisiPomoc() {
    println(
        """
        Komande (u izradi):
          status   provera LPFR-a i poreske stope
          pin      slanje PIN-a
          racun    izdavanje Training Sale računa
        """.trimIndent()
    )
}
