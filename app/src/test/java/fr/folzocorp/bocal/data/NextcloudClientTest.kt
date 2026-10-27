package fr.folzocorp.bocal.data

import fr.folzocorp.bocal.data.HttpRequest
import fr.folzocorp.bocal.data.HttpResponse
import fr.folzocorp.bocal.data.HttpTransport
import fr.folzocorp.bocal.data.NextcloudClient
import fr.folzocorp.bocal.data.StoredConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NextcloudClientTest {

    private class TransportScript : HttpTransport {
        val requetes = mutableListOf<HttpRequest>()
        val reponses = mutableMapOf<String, HttpResponse>()
        var defaut: HttpResponse = HttpResponse(500, "")

        override fun execute(request: HttpRequest): HttpResponse {
            requetes += request
            val path = request.url.substringAfter("://").substringAfter('/')
                .substringBefore('?')
            return reponses.entries.firstOrNull { (suff, _) -> path.endsWith(suff) }?.value ?: defaut
        }
    }

    private fun transportQuiMarche(): TransportScript = TransportScript().apply {
        reponses["index.php/login/v2"] =
            HttpResponse(200, """{"login":"https://cloud.example/login","token":"poll"}""")
        reponses["ocs/v2.php/core/getapppassword"] =
            HttpResponse(
                200,
                """{"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},""" +
                    """"data":{"apppassword":"jeton-app-1234"}}}""",
            )
        reponses["ocs/v2.php/core_AUTHTOKEN/V1/bocal"] =
            HttpResponse(
                200,
                """{"ocs":{"meta":{"status":"ok","statuscode":200,"message":"OK"},""" +
                    """"data":{"loginName":"jean-henry","token":"jeton-app-1234"}}}""",
            )
        reponses["ocs/v2.php/cloud/user"] =
            HttpResponse(200, """{"ocs":{"meta":{"status":"ok","statuscode":200},"data":{"id":"jean-henry"}}}""")
        reponses["ocs/v2.php/core/apppassword"] =
            HttpResponse(200, """{"ocs":{"meta":{"status":"ok","statuscode":200}}}""")
    }

    @Test
    fun connexionReussieConsommeLeMotDePasseUneFoisPuisPasseEnBearer() {
        val transport = transportQuiMarche()
        val client = NextcloudClient(transport)
        val motDePasse = "S3cret!".toCharArray()

        val result = client.login("https://cloud.example/", "jean-henry", motDePasse)

        val success = result as NextcloudClient.LoginResult.Success
        assertEquals(
            StoredConnection("https://cloud.example", "jean-henry", "jeton-app-1234"),
            success.connection,
        )

        assertEquals(3, transport.requetes.size)
        assertEquals("POST", transport.requetes[0].method)
        assertTrue(transport.requetes[0].url.endsWith("/index.php/login/v2"))

        assertEquals("GET", transport.requetes[1].method)
        assertTrue(transport.requetes[1].url.endsWith("/ocs/v2.php/core/getapppassword"))
        val basic = transport.requetes[1].headers["Authorization"].orEmpty()
        assertTrue("premier appel authentifie en Basic", basic.startsWith("Basic "))
        assertEquals("Basic " + java.util.Base64.getEncoder()
            .encodeToString("jean-henry:S3cret!".toByteArray()), basic)

        assertEquals("GET", transport.requetes[2].method)
        assertEquals(
            "Bearer jeton-app-1234",
            transport.requetes[2].headers["Authorization"],
        )
    }

    @Test
    fun leMotDePasseEstEffaceEnMemoireApresChaqueIssue() {
        for (transport in listOf(
            transportQuiMarche(),
            TransportScript().apply {
                reponses["ocs/v2.php/core/getapppassword"] = HttpResponse(401, "")
                reponses["index.php/login/v2"] = HttpResponse(200, """{"token":"poll"}""")
            },
        )) {
            val motDePasse = "S3cret!".toCharArray()
            NextcloudClient(transport).login("https://cloud.example", "u", motDePasse)
            assertTrue(
                "le tableau du mot de passe doit être intégralement neutralisé",
                motDePasse.all { it == '\u0000' },
            )
        }
    }

    @Test
    fun refusAuthentificationRemonteAUTH_REJECTED() {
        val transport = TransportScript().apply {
            reponses["index.php/login/v2"] = HttpResponse(200, """{"login":"l","token":"t"}""")
            reponses["ocs/v2.php/core/getapppassword"] = HttpResponse(401, "")
        }
        val result = NextcloudClient(transport).login("https://cloud.example", "u", "x".toCharArray())
        val failure = result as NextcloudClient.LoginResult.Failure
        assertEquals(NextcloudClient.LoginResult.Failure.Kind.AUTH_REJECTED, failure.kind)
        assertEquals(401, failure.httpStatus)
    }

    @Test
    fun urlSansChampLoginVEstPasUnNextcloud() {
        val transport = TransportScript().apply {
            reponses["index.php/login/v2"] = HttpResponse(404, "<html>404</html>")
        }
        val result = NextcloudClient(transport).login("https://serveur.example", "u", "x".toCharArray())
        val failure = result as NextcloudClient.LoginResult.Failure
        assertEquals(NextcloudClient.LoginResult.Failure.Kind.NOT_NEXTCLOUD, failure.kind)
        assertEquals("un handshake raté doit éviter les appels suivants", 1, transport.requetes.size)
    }

    @Test
    fun redirectDuHandshakeEstSuiviEtPasUnNextcloud() {
        val transport = TransportScript().apply {
            reponses["index.php/login/v2"] =
                HttpResponse(302, "", mapOf("location" to "https://cloud.example/index.php/login/v2/"))
            reponses["index.php/login/v2/"] = HttpResponse(200, """{"login":"l","token":"t"}""")
            reponses["ocs/v2.php/core/getapppassword"] =
                HttpResponse(200, """{"ocs":{"data":{"apppassword":"jeton-app-1234"}}}""")
            reponses["ocs/v2.php/cloud/user"] =
                HttpResponse(200, """{"ocs":{"meta":{"statuscode":200},"data":{"id":"jean-henry"}}}""")
        }
        val result = NextcloudClient(transport).login("https://cloud.example", "jean-henry", "x".toCharArray())
        assertTrue(result is NextcloudClient.LoginResult.Success)
        assertEquals(
            "https://cloud.example/index.php/login/v2/",
            transport.requetes[1].url,
        )
    }

    @Test
    fun rateLimit429EstUneErreurServeurPasUnServeurEtranger() {
        val transport = TransportScript().apply {
            reponses["index.php/login/v2"] = HttpResponse(429, "")
        }
        val result = NextcloudClient(transport).login("https://cloud.example", "u", "x".toCharArray())
        val failure = result as NextcloudClient.LoginResult.Failure
        assertEquals(NextcloudClient.LoginResult.Failure.Kind.SERVER, failure.kind)
        assertEquals(429, failure.httpStatus)
    }

    @Test
    fun httpSurHoteDistantEstRefuseHTTPSObligatoire() {
        val transport = TransportScript()
        val result = NextcloudClient(transport).login("http://cloud.example", "u", "x".toCharArray())
        val failure = result as NextcloudClient.LoginResult.Failure
        assertEquals(NextcloudClient.LoginResult.Failure.Kind.BAD_URL, failure.kind)
        assertTrue("aucun appel réseau avant validation du schéma", transport.requetes.isEmpty())
    }

    @Test
    fun loopbackEnHttpEstAutorisePourLaCI() {
        val client = NextcloudClient(TransportScript())
        assertEquals("http://localhost:8080", client.normalizeBaseUrl("http://localhost:8080/"))
        assertEquals("http://127.0.0.1:8080", client.normalizeBaseUrl("http://127.0.0.1:8080"))
    }

    @Test
    fun normalisationUrl() {
        val client = NextcloudClient(TransportScript())
        assertEquals("https://cloud.example", client.normalizeBaseUrl("  cloud.example  "))
        assertEquals("https://cloud.example/sub", client.normalizeBaseUrl("https://cloud.example/sub/"))
        assertEquals("https://cloud.example", client.normalizeBaseUrl("https://cloud.example/"))
        assertEquals(null, client.normalizeBaseUrl("ftp://cloud.example"))
        assertEquals(null, client.normalizeBaseUrl(""))
    }

    @Test
    fun verificationEtRevocationSontDesAppelsBearerPurs() {
        val transport = transportQuiMarche()
        val client = NextcloudClient(transport)
        val connection = StoredConnection("https://cloud.example", "u", "jeton-app-1234")
        assertEquals(200, client.verify(connection))
        client.revoke(connection)
        assertEquals("Bearer jeton-app-1234", transport.requetes[0].headers["Authorization"])
        assertEquals("GET", transport.requetes[0].method)
        assertEquals("DELETE", transport.requetes[1].method)
        assertTrue(transport.requetes[1].url.endsWith("/ocs/v2.php/core/apppassword"))
        assertTrue(
            transport.requetes.none { it.headers["Authorization"]?.startsWith("Basic") == true },
        )
    }

    @Test
    fun serveurSansGetAppPasswordBasculeSurCoreAuthtokenLegacy() {
        val transport = TransportScript().apply {
            reponses["index.php/login/v2"] = HttpResponse(200, """{"login":"l","token":"t"}""")
            reponses["ocs/v2.php/core/getapppassword"] = HttpResponse(404, "")
            reponses["ocs/v2.php/core_AUTHTOKEN/V1/bocal"] =
                HttpResponse(200, """{"ocs":{"data":{"loginName":"jean-henry","token":"jeton-app-1234"}}}""")
            reponses["ocs/v2.php/cloud/user"] =
                HttpResponse(200, """{"ocs":{"meta":{"statuscode":200},"data":{"id":"jean-henry"}}}""")
        }
        val result = NextcloudClient(transport).login("https://cloud.example", "jean-henry", "x".toCharArray())
        val success = result as NextcloudClient.LoginResult.Success
        assertEquals("jeton-app-1234", success.connection.appToken)
        val legacy = transport.requetes.first { it.url.endsWith("/ocs/v2.php/core_AUTHTOKEN/V1/bocal") }
        assertEquals("POST", legacy.method)
        assertTrue(legacy.headers["Authorization"]!!.startsWith("Basic"))
    }

    @Test
    fun delaiGlobalCoupeUnLoginQuiTraine() {
        val script = transportQuiMarche()
        val lent = object : HttpTransport {
            override fun execute(request: HttpRequest): HttpResponse {
                Thread.sleep(60)
                return script.execute(request)
            }
        }
        val motDePasse = "S3cret!".toCharArray()
        val result = NextcloudClient(lent, delaiGlobalMs = 30).login("https://cloud.example", "u", motDePasse)
        val failure = result as NextcloudClient.LoginResult.Failure
        assertEquals(NextcloudClient.LoginResult.Failure.Kind.TIMEOUT, failure.kind)
        assertTrue("le mot de passe doit être effacé même sur délai dépassé",
            motDePasse.all { it == '\u0000' })
    }

    @Test
    fun urlInvalideEffaceQuandMemeLeMotDePasse() {
        val motDePasse = "S3cret!".toCharArray()
        val result = NextcloudClient(TransportScript()).login("ftp://nope", "u", motDePasse)
        val failure = result as NextcloudClient.LoginResult.Failure
        assertEquals(NextcloudClient.LoginResult.Failure.Kind.BAD_URL, failure.kind)
        assertTrue(motDePasse.all { it == '\u0000' })
    }
}
