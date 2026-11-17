package rs.omniit.hospes.esir

import kotlin.test.Test
import kotlin.test.assertEquals

class EsirTest {
    @Test
    fun verzijaOdgovaraPrijaviNaPortalu() {
        assertEquals("0.0.1", Esir.VERSION)
    }

    @Test
    fun koristiProtokolV3() {
        assertEquals("v3", Esir.PROTOCOL)
    }
}
