package fr.folzocorp.bocal.data

import java.net.HttpURLConnection
import java.net.URL

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    /** Corps binaire (PUT/PROPFIND WebDAV). Prioritaire sur [body] si posé. */
    val binaryBody: ByteArray? = null,
)

data class HttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
    /** Contenu brut de la réponse (retéléchargement WebDAV ; [body] en dérive en UTF-8). */
    val bytes: ByteArray = ByteArray(0),
)

interface HttpTransport {
    fun execute(request: HttpRequest): HttpResponse
}

class UrlConnectionTransport : HttpTransport {

    override fun execute(request: HttpRequest): HttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = request.method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            for ((name, value) in request.headers) {
                connection.setRequestProperty(name, value)
            }
            val payload = request.binaryBody ?: request.body?.toByteArray(Charsets.UTF_8)
            payload?.let { bytes ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val body = String(bytes, Charsets.UTF_8)
            val headers = connection.headerFields.orEmpty()
                .mapNotNull { (name, values) ->
                    val valeur = values?.firstOrNull()
                    if (name != null && valeur != null) name.lowercase() to valeur else null
                }
                .toMap()
            HttpResponse(status, body, headers, bytes)
        } finally {
            connection.disconnect()
        }
    }
}
