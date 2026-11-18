package rs.omniit.hospes.esir

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Zajednička HTTP podešavanja za komunikaciju ESIR-a sa PFR-om. */
object EsirHttp {
    /** API može da doda nova polja u odgovor, pa nepoznata polja ignorišemo. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun napravi(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 20_000
            socketTimeoutMillis = 20_000
        }
    }
}
