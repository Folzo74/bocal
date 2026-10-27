package fr.folzocorp.bocal.data

import fr.folzocorp.bocal.data.HttpRequest
import fr.folzocorp.bocal.data.HttpResponse
import fr.folzocorp.bocal.data.HttpTransport
import java.io.IOException
import java.lang.reflect.Method
import java.net.URI
import java.time.Duration

/**
 * Transport JVM pour les tests d'intégration CI : HttpURLConnection (le
 * transport de production) refuse les méthodes hors de sa liste figée — ni
 * MKCOL ni PROPFIND. java.net.http accepte toute méthode ; il est piloté ici
 * par réflexion car android.jar ne le déclare pas (apparu en API 34), avec
 * lookup systématique sur les interfaces publiques du module (les
 * implémentations jdk.internal ne sont pas accessibles). Point d'extension
 * identique : [HttpTransport].
 */
class JdkWebDavTransport : HttpTransport {

    private val httpClientClass = Class.forName("java.net.http.HttpClient")
    private val builderClass = Class.forName("java.net.http.HttpClient\$Builder")
    private val httpRequestClass = Class.forName("java.net.http.HttpRequest")
    private val requestBuilderClass = Class.forName("java.net.http.HttpRequest\$Builder")
    private val publisherClass = Class.forName("java.net.http.HttpRequest\$BodyPublisher")
    private val bodyPublishersClass = Class.forName("java.net.http.HttpRequest\$BodyPublishers")
    private val responseClass = Class.forName("java.net.http.HttpResponse")
    private val bodyHandlersClass = Class.forName("java.net.http.HttpResponse\$BodyHandlers")

    private val client: Any = httpClientClass.method("newBuilder").invoke(null).let { builder ->
        builderClass.method("followRedirects", redirectNever().javaClass).invoke(builder, redirectNever())
        builderClass.method("connectTimeout", Duration::class.java)
            .invoke(builder, Duration.ofSeconds(15))
        builderClass.method("build").invoke(builder)
    }

    private val ofByteArrayHandler = bodyHandlersClass.method("ofByteArray").invoke(null)

    override fun execute(request: HttpRequest): HttpResponse = try {
        val builder = httpRequestClass
            .method("newBuilder", URI::class.java)
            .invoke(null, URI.create(request.url))
        requestBuilderClass.method("timeout", Duration::class.java)
            .invoke(builder, Duration.ofSeconds(60))
        requestBuilderClass.method("method", String::class.java, publisherClass)
            .invoke(builder, request.method, publisher(request))
        for ((name, value) in request.headers) {
            runCatching {
                requestBuilderClass.method("header", String::class.java, String::class.java)
                    .invoke(builder, name, value)
            }
        }
        val built = requestBuilderClass.method("build").invoke(builder)
        val handlerClass = Class.forName("java.net.http.HttpResponse\$BodyHandler")
        val response = httpClientClass.method("send", httpRequestClass, handlerClass)
            .invoke(client, built, ofByteArrayHandler)
        val status = responseClass.method("statusCode").invoke(response) as Int
        val bytes = responseClass.method("body").invoke(response) as? ByteArray ?: ByteArray(0)
        val headersObject = responseClass.method("headers").invoke(response)
        @Suppress("UNCHECKED_CAST")
        val headers = Class.forName("java.net.http.HttpHeaders").method("map").invoke(headersObject)
            as? Map<String, List<String>>
        HttpResponse(
            status = status,
            body = String(bytes, Charsets.UTF_8),
            headers = headers.orEmpty()
                .mapNotNull { (name, values) -> values.firstOrNull()?.let { name.lowercase() to it } }
                .toMap(),
            bytes = bytes,
        )
    } catch (exception: IOException) {
        throw exception
    } catch (exception: ReflectiveOperationException) {
        throw IOException("transport JDK indisponible : ${exception.cause ?: exception.message}", exception)
    } catch (exception: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("transport JDK interrompu", exception)
    }

    private fun redirectNever(): Any =
        Class.forName("java.net.http.HttpClient\$Redirect")
            .enumConstants!!.filterIsInstance<Enum<*>>().first { it.name == "NEVER" }

    private fun publisher(request: HttpRequest): Any = when {
        request.binaryBody != null -> bodyPublishersClass
            .method("ofByteArray", ByteArray::class.java)
            .invoke(null, request.binaryBody)

        request.body != null -> bodyPublishersClass
            .method("ofString", String::class.java, java.nio.charset.Charset::class.java)
            .invoke(null, request.body, Charsets.UTF_8)

        else -> bodyPublishersClass.method("noBody").invoke(null)
    }

    /** Lookup sur les types exportés du module java.net.http (jamais sur l'impl fournie). */
    private fun Class<*>.method(name: String, vararg parameters: Class<*>): Method =
        getMethod(name, *parameters)
}
