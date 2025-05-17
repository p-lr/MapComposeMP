package ovh.plrapps.mapcompose.maplibre.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation

internal val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
    }
}