package fr.folzocorp.bocal.data

import java.io.IOException
import java.net.URL
import java.util.Arrays
import java.util.Base64

/**
 * Client d'authentification Nextcloud (cahier §7.2, critère C1).
 *
 * Mécanique « Basic → Bearer » :
 *  1. handshake anonyme sur l'endpoint login v2 (`POST /index.php/login/v2`) —
 *     prouve que l'URL est bien un Nextcloud ;
 *  2. le mot de passe n'est envoyé QU'UNE FOIS, en Basic, pour créer un token
 *     d'application (`GET /ocs/v2.php/core/getapppassword`, repli sur l'ancien
 *     `POST /ocs/v2.php/core_AUTHTOKEN/V1/bocal` des serveurs plus anciens) ;
 *  3. ensuite, tous les appels (vérification, révocation, futur WebDAV)
 *     utilisent uniquement le token en Bearer ;
 *  4. le tableau de caractères du mot de passe est effacé en mémoire dès
 *     l'échange terminé (finally).
 */
class NextcloudClient(
    private val transport: HttpTransport = UrlConnectionTransport(),
    /** Plafond global d'un login (enchaînement des requêtes) — l'UI n'attend pas au-delà. */
    private val delaiGlobalMs: Long = 45_000L,
) {

    companion object {
        const val APP_TOKEN_NAME = "bocal"
        const val USER_AGENT = "Bocal/0.1 (Android)"
    }

    sealed class LoginResult {
        data class Success(val connection: StoredConnection) : LoginResult()
        data class Failure(
            val kind: Kind,
            val httpStatus: Int = 0,
        ) : LoginResult() {
            enum class Kind { BAD_URL, NOT_NEXTCLOUD, AUTH_REJECTED, NETWORK, SERVER }
        }
    }

    fun login(rawBaseUrl: String, user: String, password: CharArray): LoginResult {
        // Normaliser AVANT le try, mais effacer le mot de passe dans les DEUX
        // chemins : le finally ci-dessous ne couvre que le corps try.
        val baseUrl = normalizeBaseUrl(rawBaseUrl)
        if (baseUrl == null) {
            password.fill('\u0000')
            return LoginResult.Failure(LoginResult.Failure.Kind.BAD_URL)
        }
        return try {
            val echeance = System.nanoTime() + delaiGlobalMs * 1_000_000L
            val handshake = poindreLoginV2(baseUrl, echeance)
            when {
                handshake.status == 429 || handshake.status in 500..599 ->
                    return LoginResult.Failure(LoginResult.Failure.Kind.SERVER, handshake.status)

                handshake.status in 300..499 ->
                    return LoginResult.Failure(LoginResult.Failure.Kind.NOT_NEXTCLOUD, handshake.status)

                MiniJson.stringField(handshake.body, "token") == null &&
                    MiniJson.stringField(handshake.body, "login") == null ->
                    return LoginResult.Failure(LoginResult.Failure.Kind.NOT_NEXTCLOUD, handshake.status)
            }
            val basic = basicAuthHeader(user, password)
            if (delaiDepasse(echeance)) {
                return LoginResult.Failure(LoginResult.Failure.Kind.SERVER)
            }
            val created = transport.execute(
                HttpRequest(
                    method = "GET",
                    url = "$baseUrl/ocs/v2.php/core/getapppassword",
                    headers = mapOf(
                        "Authorization" to basic,
                        "OCS-APIRequest" to "true",
                        "Accept" to "application/json",
                        "User-Agent" to USER_AGENT,
                    ),
                ),
            )
            if (created.status == 404) {
                // Serveur plus ancien : ancien endpoint OCS core_AUTHTOKEN.
                return createAppTokenLegacy(baseUrl, user, basic, echeance)
            }
            when {
                created.status == 401 || created.status == 403 ->
                    return LoginResult.Failure(LoginResult.Failure.Kind.AUTH_REJECTED, created.status)

                created.status !in 200..299 ->
                    return LoginResult.Failure(LoginResult.Failure.Kind.SERVER, created.status)
            }
            val token = MiniJson.stringField(created.body, "apppassword")
                ?: return LoginResult.Failure(LoginResult.Failure.Kind.SERVER, created.status)
            finishLogin(baseUrl, user, token, echeance)
        } catch (exception: IOException) {
            LoginResult.Failure(LoginResult.Failure.Kind.NETWORK)
        } finally {
            password.fill('\u0000')
        }
    }

    /**
     * Le handshake login/v2 est anonyme : suivre les redirects (max 3) est sûr
     * (aucun credential en jeu) et évite de rejeter un Nextcloud légitime
     * derrière un redirect de chemin ou de domaine canonique.
     */
    private fun poindreLoginV2(baseUrl: String, echeance: Long): HttpResponse {
        var url = "$baseUrl/index.php/login/v2"
        var reponse = executerLoginV2(url)
        var hops = 0
        while (reponse.status in 301..308 && hops < 3) {
            if (delaiDepasse(echeance)) return HttpResponse(504, "")
            val location = reponse.headers["location"].orEmpty()
            if (location.isEmpty()) break
            url = runCatching { URL(URL(url), location).toString() }.getOrElse { break }
            reponse = executerLoginV2(url)
            hops++
        }
        return reponse
    }

    private fun executerLoginV2(url: String): HttpResponse =
        transport.execute(
            HttpRequest(
                method = "POST",
                url = url,
                headers = mapOf("Accept" to "application/json"),
            ),
        )

    /** Ancien flux (Nextcloud < 32 environ) : POST OCS core_AUTHTOKEN en Basic. */
    private fun createAppTokenLegacy(
        baseUrl: String,
        user: String,
        basic: String,
        echeance: Long,
    ): LoginResult {
        if (delaiDepasse(echeance)) {
            return LoginResult.Failure(LoginResult.Failure.Kind.SERVER)
        }
        val created = transport.execute(
            HttpRequest(
                method = "POST",
                url = "$baseUrl/ocs/v2.php/core_AUTHTOKEN/V1/$APP_TOKEN_NAME",
                headers = mapOf(
                    "Authorization" to basic,
                    "OCS-APIRequest" to "true",
                    "Accept" to "application/json",
                    "User-Agent" to USER_AGENT,
                    "Content-Type" to "application/x-www-form-urlencoded",
                ),
                body = "",
            ),
        )
        when {
            created.status == 401 || created.status == 403 ->
                return LoginResult.Failure(LoginResult.Failure.Kind.AUTH_REJECTED, created.status)

            created.status !in 200..299 ->
                return LoginResult.Failure(LoginResult.Failure.Kind.SERVER, created.status)
        }
        val token = MiniJson.stringField(created.body, "token")
            ?: return LoginResult.Failure(LoginResult.Failure.Kind.SERVER, created.status)
        return finishLogin(baseUrl, user, token, echeance)
    }

    private fun finishLogin(baseUrl: String, user: String, token: String, echeance: Long): LoginResult {
        if (delaiDepasse(echeance)) {
            return LoginResult.Failure(LoginResult.Failure.Kind.SERVER)
        }
        val verified = transport.execute(
            HttpRequest(
                method = "GET",
                url = "$baseUrl/ocs/v2.php/cloud/user?format=json",
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "OCS-APIRequest" to "true",
                    "Accept" to "application/json",
                    "User-Agent" to USER_AGENT,
                ),
            ),
        )
        if (verified.status !in 200..299) {
            return LoginResult.Failure(LoginResult.Failure.Kind.SERVER, verified.status)
        }
        val loginName = MiniJson.stringField(verified.body, "id") ?: user
        return LoginResult.Success(StoredConnection(baseUrl, loginName, token))
    }

    /** Vérifie qu'un token enregistré est toujours valide (200) — appel Bearer pur. */
    fun verify(connection: StoredConnection): Int =
        transport.execute(
            HttpRequest(
                method = "GET",
                url = "${connection.baseUrl}/ocs/v2.php/cloud/user?format=json",
                headers = mapOf(
                    "Authorization" to "Bearer ${connection.appToken}",
                    "OCS-APIRequest" to "true",
                    "Accept" to "application/json",
                ),
            ),
        ).status

    /** Révocation serveur du token d'application (logout propre, côté serveur aussi). */
    fun revoke(connection: StoredConnection): Int {
        val modern = transport.execute(
            HttpRequest(
                method = "DELETE",
                url = "${connection.baseUrl}/ocs/v2.php/core/apppassword",
                headers = mapOf(
                    "Authorization" to "Bearer ${connection.appToken}",
                    "OCS-APIRequest" to "true",
                    "Accept" to "application/json",
                    "User-Agent" to USER_AGENT,
                ),
            ),
        )
        if (modern.status != 404) return modern.status
        return transport.execute(
            HttpRequest(
                method = "DELETE",
                url = "${connection.baseUrl}/ocs/v2.php/core_AUTHTOKEN/V1/$APP_TOKEN_NAME",
                headers = mapOf(
                    "Authorization" to "Bearer ${connection.appToken}",
                    "OCS-APIRequest" to "true",
                    "Accept" to "application/json",
                    "User-Agent" to USER_AGENT,
                ),
            ),
        ).status
    }

    /**
     * HTTPS obligatoire, sauf cible locale (loopback) pour les environnements
     * de test type CI/docker.
     */
    fun normalizeBaseUrl(raw: String): String? {
        var url = raw.trim()
        if (url.isEmpty()) return null
        if (!url.contains("://")) url = "https://$url"
        if (url.startsWith("https://") || (isLoopback(url) && url.startsWith("http://"))) {
            return url.trimEnd('/')
        }
        return null
    }

    private fun isLoopback(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/')
            .substringBefore('@').substringBefore(':')
        return host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"
    }

    private fun delaiDepasse(echeance: Long): Boolean = System.nanoTime() > echeance

    private fun basicAuthHeader(user: String, password: CharArray): String {
        val credentials = "$user:${String(password)}".toByteArray(Charsets.UTF_8)
        Arrays.fill(password, '\u0000')
        return "Basic " + Base64.getEncoder().encodeToString(credentials)
    }
}
