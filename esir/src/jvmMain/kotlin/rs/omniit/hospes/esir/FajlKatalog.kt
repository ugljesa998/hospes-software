package rs.omniit.hospes.esir

import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** Katalog artikala u JSON fajlu (desktop, komandna linija); upisuje se posle svake izmene. */
class FajlKatalog(val fajl: File) : KatalogUMemoriji() {

    init {
        if (fajl.exists()) {
            EsirHttp.json.decodeFromString(ListSerializer(Artikal.serializer()), fajl.readText())
                .forEach { artikli[it.sifra] = it }
        }
    }

    override fun posleIzmene() {
        fajl.upisiBezbedno(EsirHttp.json.encodeToString(ListSerializer(Artikal.serializer()), svi()))
    }
}
