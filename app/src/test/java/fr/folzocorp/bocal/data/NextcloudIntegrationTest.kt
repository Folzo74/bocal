package fr.folzocorp.bocal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Test d'intégration critère C1 contre un vrai serveur Nextcloud.
 * Activé uniquement quand NEXTCLOUD_URL est posé (job CI « integration-nextcloud » :
 * service docker nextcloud:stable + utilisateur de démo). Sauté partout ailleurs.
 *
 * Preuves alignées sur le critère :
 *  1. handshake login v2 anonyme → 200 (l'URL est bien un Nextcloud) ;
 *  2. login complet (mot de passe → token d'application) → succès ;
 *  3. appel ultérieur en Bearer seul → 200 ;
 *  4. révocation (logout serveur) → 200, et le token devient mort → 401.
 */
class NextcloudIntegrationTest {

    private val baseUrl: String get() = System.getenv("NEXTCLOUD_URL")!!.trimEnd('/')
    private val adminUser: String get() = System.getenv("NEXTCLOUD_ADMIN_USER") ?: "admin"
    private val adminPass: String get() = System.getenv("NEXTCLOUD_ADMIN_PASSWORD") ?: "admin"
    private val demoUser: String get() = System.getenv("NEXTCLOUD_DEMO_USER") ?: "demo"
    private val demoPass: String get() = System.getenv("NEXTCLOUD_DEMO_PASSWORD") ?: "BocalDemo2026!"
    private val transport: HttpTransport = UrlConnectionTransport()
    private val client = NextcloudClient(transport)

    @Before
    fun exigerUnServeur() {
        val url = System.getenv("NEXTCLOUD_URL")
        assumeTrue(
            "NEXTCLOUD_URL absent : le test d'intégration Nextcloud est sauté (réservé à la CI)",
            !url.isNullOrBlank(),
        )
    }

    @Test
    fun connexionCompleteSurVraiNextcloud() {
        creerUtilisateurDemo()

        val handshake = transport.execute(
            HttpRequest("POST", "$baseUrl/index.php/login/v2", mapOf("Accept" to "application/json")),
        )
        assertEquals("login v2 doit répondre 200", 200, handshake.status)
        assertNotNull("login v2 doit exposer un champ token", MiniJson.stringField(handshake.body, "token"))

        val motDePasse = demoPass.toCharArray()
        val result = client.login(baseUrl, demoUser, motDePasse)
        val success = when (result) {
            is NextcloudClient.LoginResult.Success -> result
            is NextcloudClient.LoginResult.Failure -> throw AssertionError(
                "login échoué : ${result.kind} (http ${result.httpStatus})",
            )
        }
        assertTrue(
            "le mot de passe doit être effacé en mémoire",
            motDePasse.all { it == '\u0000' },
        )

        val verify = transport.execute(
            HttpRequest(
                "GET",
                "$baseUrl/ocs/v2.php/cloud/user?format=json",
                mapOf(
                    "Authorization" to "Bearer ${success.connection.appToken}",
                    "OCS-APIRequest" to "true",
                    "Accept" to "application/json",
                ),
            ),
        )
        assertEquals("l'appel Bearer après login doit être 200", 200, verify.status)
        assertTrue(verify.body.contains(demoUser))

        val revoke = client.revoke(success.connection)
        assertEquals("la révocation du token doit être 200", 200, revoke)

        val after = client.verify(success.connection)
        assertEquals("un token révoqué doit être refusé", 401, after)
    }

    private fun creerUtilisateurDemo() {
        val existe = transport.execute(
            HttpRequest(
                "GET",
                "$baseUrl/ocs/v2.php/cloud/users/$demoUser?format=json",
                adminHeaders(),
            ),
        )
        if (existe.status == 200) return
        val creation = transport.execute(
            HttpRequest(
                "POST",
                "$baseUrl/ocs/v2.php/cloud/users?format=json",
                adminHeaders(),
                body = "userid=$demoUser&password=$demoPass&displayName=Bocal%20Demo",
            ),
        )
        assertTrue(
            "création de l'utilisateur de démo impossible (http ${creation.status}) : ${creation.body.take(200)}",
            creation.status in 200..299 || creation.body.contains("already exists", ignoreCase = true),
        )
    }

    private fun adminHeaders(): Map<String, String> = mapOf(
        "Authorization" to basic(adminUser, adminPass),
        "OCS-APIRequest" to "true",
        "Accept" to "application/json",
        "Content-Type" to "application/x-www-form-urlencoded",
    )

    private fun basic(user: String, pass: String): String =
        "Basic " + java.util.Base64.getEncoder().encodeToString("$user:$pass".toByteArray())
}
