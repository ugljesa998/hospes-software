package rs.omniit.hospes.esir

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * CSV fajl kataloga na disku, tako da radi sa Excelom:
 * - čita UTF-8 (sa BOM-om ili bez), a ako fajl nije ispravan UTF-8, čita ga kao Windows-1250
 *   (Excel „CSV (razdvojeno zarezima)“ na srpskoj latinici);
 * - upisuje UTF-8 sa BOM-om, da Excel prepozna č, ć, đ i ćirilicu.
 */
object KatalogCsvFajl {
    private const val BOM = "﻿"

    fun procitaj(fajl: File): String = dekodiraj(fajl.readBytes())

    fun dekodiraj(bajtovi: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bajtovi))
            .toString()
    } catch (e: CharacterCodingException) {
        String(bajtovi, Charset.forName("windows-1250"))
    }

    fun upisi(fajl: File, tekst: String) {
        fajl.absoluteFile.parentFile?.mkdirs()
        fajl.writeText(BOM + tekst)
    }
}
