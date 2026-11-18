package rs.omniit.hospes.esir

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Upisuje tekst (UTF-8) preko privremenog fajla i atomskog premeštanja, na macOS-u i na Windows-u.
 * Ako nestane struje usred upisa, ostaje ili stari ili novi fajl, nikad pola fajla.
 */
internal fun File.upisiBezbedno(tekst: String) {
    val cilj = absoluteFile
    cilj.parentFile?.mkdirs()
    val privremeni = File(cilj.parentFile, cilj.name + ".tmp")
    privremeni.writeText(tekst)
    try {
        Files.move(privremeni.toPath(), cilj.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (e: AtomicMoveNotSupportedException) {
        Files.move(privremeni.toPath(), cilj.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
