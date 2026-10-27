package fr.folzocorp.bocal.data

import android.content.Context

/**
 * Cycle de vie complet de la connexion Nextcloud (critère C1) :
 * connexion (mot de passe consommé une fois), lecture de l'état, déconnexion
 * propre = révocation serveur best-effort + purge fichier + purge clé Keystore.
 */
class ConnectionManager(
    private val store: TokenStore,
    private val client: NextcloudClient = NextcloudClient(),
) {

    constructor(
        context: Context,
        backend: KeystoreBackend = AndroidKeystoreBackend,
        transport: HttpTransport = UrlConnectionTransport(),
    ) : this(TokenStore(context, backend), NextcloudClient(transport))

    fun currentConnection(): StoredConnection? = store.load()

    /** Vrai si la dernière lecture a trouvé une connexion irrécupérable (purée, reconnexion requise). */
    fun lecturePrecedenteEtaitIllisible(): Boolean = store.lectureEtaitIllisible

    fun isConnected(): Boolean = store.isConfigured()

    fun connect(baseUrl: String, user: String, password: CharArray): NextcloudClient.LoginResult {
        val result = client.login(baseUrl, user, password)
        if (result is NextcloudClient.LoginResult.Success) {
            store.save(result.connection)
        }
        return result
    }

    /**
     * Déconnexion propre. La révocation côté serveur est tentée en premier
     * (best effort : hors ligne, on purge quand même l'appareil) ; le stockage
     * local est ensuite intégralement détruit (fichier + clé Keystore).
     */
    fun disconnect() {
        val connection = store.load()
        if (connection != null) {
            runCatching { client.revoke(connection) }
        }
        store.wipe()
    }
}
