package ovh.plrapps.mapcompose.demo.utils

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android

actual fun getKtorClient(): HttpClient {
    return HttpClient(Android)
}