package ovh.plrapps.mapcompose.demo.utils

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js

actual fun getKtorClient(): HttpClient {
    return HttpClient(Js)
}
