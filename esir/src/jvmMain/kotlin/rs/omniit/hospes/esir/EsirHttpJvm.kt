package rs.omniit.hospes.esir

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/** HTTP klijent za desktop (Windows, macOS) i komandnu liniju. */
fun EsirHttp.napraviZaJvm(): HttpClient = napravi(OkHttp.create())
