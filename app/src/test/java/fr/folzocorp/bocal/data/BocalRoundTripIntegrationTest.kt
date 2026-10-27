package fr.folzocorp.bocal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Critère de sortie M0 (cahier §10) contre un VRAI Nextcloud (service docker
 * de la CI, job nextcloud-integration) : l'arbre fixture §4.2 est monté dans
 * <home>/WhatsApp/<appareil>/AAAA-MM-JJ/ via WebDAV (jamais d'écrasement),
 * retéléchargé, et les sha256 des 17 fichiers doivent être identiques.
 *
 * Preuve produite : fichier sha256SUMS de l'aller-retour (répertoire pointé
 * par BOCAL_ROUNDTRIP_DIR, sinon build/reports/roundtrip) + le même contenu
 * dans le log du test. Activé seulement si NEXTCLOUD_URL est posé.
 */
class BocalRoundTripIntegrationTest {

    private val baseUrl: String get() = System.getenv("NEXTCLOUD_URL")!!.trimEnd('/')
    private val adminUser: String get() = System.getenv("NEXTCLOUD_ADMIN_USER") ?: "admin"
    private val adminPass: String get() = System.getenv("NEXTCLOUD_ADMIN_PASSWORD") ?: "admin"
    private val demoUser: String get() = System.getenv("NEXTCLOUD_DEMO_USER") ?: "demo"
    private val demoPass: String get() = System.getenv("NEXTCLOUD_DEMO_PASSWORD") ?: "BocalDemo2026!"
    private val device: String get() = System.getenv("BOCAL_TEST_DEVICE") ?: "fixture-phone"
    // MKCOL/PROPFIND passent par java.net.http : HttpURLConnection (le transport
    // de production) refuse les méthodes hors liste figée.
    private val transport: HttpTransport = JdkWebDavTransport()

    @Before
    fun exigerUnServeur() {
        val url = System.getenv("NEXTCLOUD_URL")
        assumeTrue(
            "NEXTCLOUD_URL absent : l'aller-retour bocal est sauté (réservé à la CI)",
            !url.isNullOrBlank(),
        )
    }

    @Test
    fun allerRetourBocalSurVraiNextcloud() {
        creerUtilisateurDemo()
        val login = NextcloudClient(transport).login(baseUrl, demoUser, demoPass.toCharArray())
        val connection = when (login) {
            is NextcloudClient.LoginResult.Success -> login.connection
            is NextcloudClient.LoginResult.Failure ->
                throw AssertionError("login échoué : ${login.kind} (http ${login.httpStatus})")
        }
        val webdav = WebDavClient(connection, transport)
        val prefix = webdav.freeBocalPrefix(device, LocalDate.now())

        val moteur = BocalRoundTrip(WhatsAppFixture.build(), webdav)
        val rapport = moteur.run(prefix)

        assertTrue("fichiers non lus : ${rapport.unreadable}", rapport.unreadable.isEmpty())
        assertTrue("échecs de monture : ${rapport.failedUpload}", rapport.failedUpload.isEmpty())
        assertEquals(
            "les 17 fichiers de la fixture doivent être montés",
            WhatsAppFixture.EXPECTED_FILES.sorted(),
            rapport.uploaded.sorted(),
        )
        assertEquals(
            "le PROPFIND doit retrouver exactement l'arbre monté",
            WhatsAppFixture.EXPECTED_FILES.map { "$prefix/$it" }.sorted(),
            webdav.list(prefix).filter { !it.isCollection }.map { it.path }.sorted(),
        )
        assertTrue("écarts de sha256 : ${rapport.mismatches}", rapport.mismatches.isEmpty())
        assertTrue("aller-retour non identique", rapport.allIdentical)

        val second = moteur.run(prefix)
        assertTrue(
            "un re-montage ne doit rien écraser : ${second.uploaded}",
            second.uploaded.isEmpty(),
        )
        assertEquals(WhatsAppFixture.EXPECTED_FILES.size, second.alreadyThere.size)
        assertTrue("second aller-retour non identique", second.allIdentical)

        val sums = rapport.sha256SumsText()
        ecrirePreuve(sums)
        println(sums)
        println("ROUNDTRIP: ${WhatsAppFixture.EXPECTED_FILES.size}/" +
            "${WhatsAppFixture.EXPECTED_FILES.size} IDENTIQUES ($prefix)")

        assertEquals(200, NextcloudClient(transport).revoke(connection))
    }

    private fun ecrirePreuve(texte: String) {
        val dossier = File(System.getenv("BOCAL_ROUNDTRIP_DIR") ?: "build/reports/roundtrip")
        check(dossier.mkdirs() || dossier.isDirectory) { "impossible de créer $dossier" }
        File(dossier, "sha256SUMS").writeText(texte)
    }

    private fun creerUtilisateurDemo() {
        val existe = transport.execute(
            HttpRequest("GET", "$baseUrl/ocs/v2.php/cloud/users/$demoUser?format=json", adminHeaders()),
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
