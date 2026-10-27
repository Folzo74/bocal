package fr.folzocorp.bocal.data

import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDate

/**
 * Client WebDAV Nextcloud minimal (cahier §4.3/§4.4) : MKCOL, PUT sans
 * écrasement (If-None-Match), GET, PROPFIND. Uniquement le token d'application
 * en Bearer — jamais de Basic ici (le mot de passe n'existe plus après le
 * login, critère C1).
 *
 * Tous les chemins sont relatifs au home de l'utilisateur (« WhatsApp/pixel/
 * 2026-10-27/Databases/backup_time »). Garde-fou cahier §4.4 : chaque appel
 * exige de rester sous le préfixe « WhatsApp/ » — l'app ne touche jamais
 * ailleurs sur le serveur.
 */
class WebDavClient(
    private val connection: StoredConnection,
    private val transport: HttpTransport = UrlConnectionTransport(),
) {

    companion object {
        /** Préfixe unique où l'app a le droit d'écrire (cahier §4.4). */
        const val ROOT_PREFIX = "WhatsApp"

        /** Chemin d'un bocal : WhatsApp/<appareil>/AAAA-MM-JJ[.<dup>] (cahier §4.4). */
        fun bocalPrefix(device: String, day: LocalDate, duplicate: Int = 0): String {
            require(duplicate >= 0) { "suffixe de doublon négatif" }
            val suffix = if (duplicate > 0) ".$duplicate" else ""
            return "$ROOT_PREFIX/${sanitizeDevice(device)}/$day$suffix"
        }

        /** Nom d'appareil rendu sûr pour un segment de chemin (ni traversal, ni caractère magie). */
        fun sanitizeDevice(raw: String): String {
            val cleaned = raw.trim()
                .replace(Regex("[^A-Za-z0-9 ._\\-]"), "-")
                .replace(Regex("^\\.+$"), "")
                .replace(Regex("\\.+$"), "")
                .trim()
                .trim('-')
            return cleaned.ifBlank { "appareil" }
        }

        private fun encodeSegment(segment: String): String =
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

        /** Percent-decodage tolérant (un href illisible reste tel quel). */
        private fun decodeSegment(segment: String): String =
            runCatching { URLDecoder.decode(segment, "UTF-8") }.getOrDefault(segment)

        private val HREF_REGEX = Regex("(?is)<(?:[A-Za-z0-9_-]+:)?href\\s*>\\s*(.*?)\\s*</(?:[A-Za-z0-9_-]+:)?href\\s*>")
        private val RESPONSE_REGEX =
            Regex("(?is)<(?:[A-Za-z0-9_-]+:)?response\\b[^>]*>(.*?)</(?:[A-Za-z0-9_-]+:)?response\\s*>")
        private val COLLECTION_REGEX = Regex("(?is)<(?:[A-Za-z0-9_-]+:)?collection\\b")

        /** ifEmpty n'existe pas pour ByteArray (tableau primitif) — petit équivalent local. */
        private inline fun ByteArray.ifEmptyBytes(fallback: () -> ByteArray): ByteArray =
            if (isEmpty()) fallback() else this
    }

    sealed class PutResult {
        class Created(val httpStatus: Int) : PutResult()
        class AlreadyExists : PutResult()
        class Failed(val httpStatus: Int, val reason: String) : PutResult()
    }

    data class DavEntry(val path: String, val isCollection: Boolean)

    private val davRootPath = "/remote.php/dav/files/${encodeSegment(connection.loginName)}/"

    /** Préfixe libre pour le jour donné : suffixe .2, .3… si le bocal du jour existe déjà. */
    fun freeBocalPrefix(device: String, day: LocalDate, maxDuplicates: Int = 100): String {
        for (duplicate in 0..maxDuplicates) {
            val prefix = bocalPrefix(device, day, duplicate)
            when (val status = propfindStatus(prefix)) {
                404 -> return prefix
                207, 200 -> continue
                else -> throw IOException("serveur WebDAV inaccessible (http $status)")
            }
        }
        throw IOException("trop de bocaux déjà présents pour $day (max $maxDuplicates)")
    }

    /** MKCOL dossier par dossier ; un dossier existant (405) compte comme succès. */
    fun ensureCollection(path: String): Boolean {
        val clean = normalize(path)
        var built = ""
        for (segment in clean.split('/')) {
            built = if (built.isEmpty()) segment else "$built/$segment"
            val response = send("MKCOL", built)
            if (response.status !in listOf(201, 204, 405)) return false
        }
        return true
    }

    /** PUT strict sans écrasement : « If-None-Match: * » → 412 si la cible existe (cahier §4.4). */
    fun putNoOverwrite(path: String, bytes: ByteArray): PutResult {
        val response = send(
            "PUT",
            normalize(path),
            extraHeaders = mapOf("If-None-Match" to "*", "Content-Type" to "application/octet-stream"),
            body = bytes,
        )
        return when {
            response.status in 200..299 -> PutResult.Created(response.status)
            response.status == 412 -> PutResult.AlreadyExists()
            else -> PutResult.Failed(response.status, response.body.take(200))
        }
    }

    /** Contenu du fichier, ou null (404 ou erreur). */
    fun get(path: String): ByteArray? {
        val response = send("GET", normalize(path))
        if (response.status != 200) return null
        return response.bytes.ifEmptyBytes { response.body.toByteArray(Charsets.UTF_8) }
    }

    /** Status d'un PROPFIND depth 0 : 207/200 = existe, 404 = absent. */
    fun propfindStatus(path: String): Int =
        send("PROPFIND", normalize(path), extraHeaders = mapOf("Depth" to "0"), body = propfindXml()).status

    /** Liste récursive (Depth: infinity) sous [path] ; chemin relatif au home, décodé. */
    fun list(path: String): List<DavEntry> {
        val response = send("PROPFIND", normalize(path), extraHeaders = mapOf("Depth" to "infinity"), body = propfindXml())
        if (response.status != 207 && response.status !in 200..299) return emptyList()
        val body = response.body.ifEmpty { String(response.bytes, Charsets.UTF_8) }
        return RESPONSE_REGEX.findAll(body).map { block ->
            val href = HREF_REGEX.find(block.value)?.groupValues?.get(1)?.trim().orEmpty()
            DavEntry(path = relativeFromDavRoot(href), isCollection = COLLECTION_REGEX.containsMatchIn(block.value))
        }.filter { it.path.isNotEmpty() }.toList()
    }

    /** Effacement — réservé à la rétention (M1) : le garde-fou préfixe s'y applique pareil. */
    fun delete(path: String): Boolean = send("DELETE", normalize(path)).status in listOf(204, 200)

    private fun propfindXml(): ByteArray =
        (
            """<?xml version="1.0"?>""" +
                """<d:propfind xmlns:d="DAV:"><d:prop>""" +
                """<d:resourcetype/><d:getcontentlength/><d:getlastmodified/>""" +
                """</d:prop></d:propfind>"""
            ).toByteArray(Charsets.UTF_8)

    private fun send(
        method: String,
        path: String,
        extraHeaders: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): HttpResponse =
        transport.execute(
            HttpRequest(
                method = method,
                url = absoluteUrl(path),
                headers = mapOf(
                    "Authorization" to "Bearer ${connection.appToken}",
                    "User-Agent" to NextcloudClient.USER_AGENT,
                ) + extraHeaders,
                binaryBody = body,
            ),
        )

    /** Le garde-fou de préfixe est appliqué par [normalize] aux points d'entrée publics. */
    private fun absoluteUrl(path: String): String =
        connection.baseUrl + davRootPath +
            path.trim().trimStart('/').trimEnd('/')
                .split('/').joinToString("/") { encodeSegment(it) }

    /** Chemin d'un href PROPFIND (URL complète ou chemin) ramené au relatif sous le home. */
    private fun relativeFromDavRoot(href: String): String {
        var value = href.trim()
        if (value.contains("://")) value = value.substringAfter("://").substringAfter('/')
        val encodedHome = "/remote.php/dav/files/${connection.loginName}/"
        value = if (value.startsWith("/remote.php/dav/files/")) {
            value.substringAfter("/remote.php/dav/files/").substringAfter('/')
        } else if (value.contains(encodedHome)) {
            value.substringAfter(encodedHome)
        } else {
            value.trimStart('/')
        }
        return value.trimEnd('/').split('/').joinToString("/") { decodeSegment(it) }
    }

    /** Garde-fou §4.4 : aucune opération hors de WhatsApp/… */
    private fun normalize(path: String): String {
        val clean = path.trim().trimStart('/').trimEnd('/')
        require(clean.split('/').firstOrNull() == ROOT_PREFIX && clean.split('/').size >= 2) {
            "chemin hors du préfixe $ROOT_PREFIX/ : « $path » (garde-fou cahier §4.4)"
        }
        return clean
    }
}
