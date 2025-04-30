package ovh.plrapps.mapcompose.demo.utils

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.prepareGet
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readBuffer
import kotlinx.io.RawSource


expect fun getKtorClient(): HttpClient

/**
 * Utility method to get a [RawSource] from a Ktor HTTP client, using a GET method.
 */
suspend fun readBuffer(client: HttpClient, path: String): RawSource {
    return client.prepareGet(path).execute { httpResponse ->
        val channel: ByteReadChannel = httpResponse.body()
        channel.readBuffer()
    }
}
