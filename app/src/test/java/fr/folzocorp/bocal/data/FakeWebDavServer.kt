package fr.folzocorp.bocal.data

import fr.folzocorp.bocal.data.HttpRequest
import fr.folzocorp.bocal.data.HttpResponse
import fr.folzocorp.bocal.data.HttpTransport
import java.net.URLEncoder

/**
 * Serveur WebDAV en mémoire pour les tests JVM : sémantique resserrée sur les
 * points qui comptent pour le cahier — MKCOL (405 si existe, 409 si parent
 * absent), PUT avec If-None-Match (412 = jamais d'écrasement), GET 404 sur
 * l'inexistant, PROPFIND depth 0/infinity.
 */
class FakeWebDavServer(val user: String = "demo") : HttpTransport {

    val files = LinkedHashMap<String, ByteArray>()
    val collections = linkedSetOf("")
    val requetes = mutableListOf<HttpRequest>()

    override fun execute(request: HttpRequest): HttpResponse {
        requetes += request
        val path = relative(request.url)
        val authorized = request.headers["Authorization"] == "Bearer jeton-webdav"
        if (!authorized) return HttpResponse(401, "unauthorized")
        return when (request.method) {
            "MKCOL" -> mkcol(path)
            "PUT" -> put(request, path)
            "GET" -> get(path)
            "DELETE" -> delete(path)
            "PROPFIND" -> propfind(request, path)
            else -> HttpResponse(405, "méthode inconnue")
        }
    }

    private fun mkcol(path: String): HttpResponse = when {
        path in collections -> HttpResponse(405, "exists")
        parent(path) !in collections -> HttpResponse(409, "parent manquant")
        else -> {
            collections += path
            HttpResponse(201, "")
        }
    }

    private fun put(request: HttpRequest, path: String): HttpResponse {
        if (parent(path) !in collections) return HttpResponse(409, "parent manquant")
        val bytes = request.binaryBody ?: request.body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        if (path in files && request.headers["If-None-Match"] == "*") {
            return HttpResponse(412, "précondition échouée : jamais d'écrasement")
        }
        files[path] = bytes
        return HttpResponse(201, "")
    }

    private fun get(path: String): HttpResponse {
        val bytes = files[path] ?: return HttpResponse(404, "introuvable")
        return HttpResponse(200, String(bytes, Charsets.UTF_8), bytes = bytes)
    }

    private fun delete(path: String): HttpResponse {
        if (files.remove(path) == null && !collections.remove(path)) return HttpResponse(404, "introuvable")
        return HttpResponse(204, "")
    }

    private fun propfind(request: HttpRequest, path: String): HttpResponse {
        val known = path in collections || path in files
        if (!known) return HttpResponse(404, "introuvable")
        val infinite = request.headers["Depth"] == "infinity"
        val targets = if (infinite) {
            val prefix = if (path.isEmpty()) "" else "$path/"
            (collections + files.keys).sorted().filter { it == path || it.startsWith(prefix) }
        } else {
            listOf(path)
        }
        val xml = buildString {
            append("""<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">""")
            for (target in targets) {
                val type = if (target in collections) "<d:resourcetype><d:collection/></d:resourcetype>"
                else "<d:resourcetype/>"
                append("<d:response><d:href>${href(target)}</d:href>$type</d:response>")
            }
            append("</d:multistatus>")
        }
        return HttpResponse(207, xml)
    }

    private fun href(path: String): String =
        "/remote.php/dav/files/$user/" +
            (if (path.isEmpty()) "" else path.split('/').joinToString("/") { encode(it) })

    private fun parent(path: String): String = path.substringBeforeLast('/', "")

    companion object {
        fun encode(segment: String): String =
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

        fun relative(url: String): String =
            url.substringAfter("/remote.php/dav/files/").substringAfter('/')
                .split('/').joinToString("/") { decode(it) }

        private fun decode(segment: String): String =
            runCatching { java.net.URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)
    }
}
