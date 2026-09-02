package io.buildthisplease.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.accept
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import java.io.IOException

data class TransportResponse(val status: Int, val body: ByteArray)

interface BuildThisPleaseTransport {
    suspend fun send(
        method: String,
        url: String,
        body: ByteArray?,
        headers: Map<String, String>,
    ): TransportResponse
}

class KtorBuildThisPleaseTransport(
    private val client: HttpClient = HttpClient(OkHttp),
) : BuildThisPleaseTransport {
    override suspend fun send(method: String, url: String, body: ByteArray?, headers: Map<String, String>): TransportResponse {
        return try {
            val response = client.request(url) {
                this.method = HttpMethod.parse(method)
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.Json)
                headers { headers.forEach { (name, value) -> append(name, value) } }
                if (body != null) setBody(body)
            }
            TransportResponse(response.status.value, response.bodyAsBytes())
        } catch (failure: IOException) {
            throw BuildThisPleaseException.Offline(failure)
        }
    }
}
