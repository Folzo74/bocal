package fr.folzocorp.bocal.data

import androidx.test.core.app.ApplicationProvider
import fr.folzocorp.bocal.data.ConnectionManager
import fr.folzocorp.bocal.data.FakeKeystoreBackend
import fr.folzocorp.bocal.data.HttpRequest
import fr.folzocorp.bocal.data.HttpResponse
import fr.folzocorp.bocal.data.HttpTransport
import fr.folzocorp.bocal.data.NextcloudClient
import fr.folzocorp.bocal.data.TokenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class ConnectionManagerTest {

    private val context: android.content.Context = ApplicationProvider.getApplicationContext()

    private class TransportSimple : HttpTransport {
        val requetes = mutableListOf<HttpRequest>()
        var horsLigne = false

        override fun execute(request: HttpRequest): HttpResponse {
            requetes += request
            if (horsLigne) throw IOException("réseau coupé")
            val path = request.url.substringAfter("://").substringAfter('/')
                .substringBefore('?')
            return when {
                path.endsWith("index.php/login/v2") -> HttpResponse(200, """{"login":"l","token":"t"}""")
                path.endsWith("core/getapppassword") -> HttpResponse(200, """{"ocs":{"data":{"apppassword":"jeton"}}}""")
                path.endsWith("core_AUTHTOKEN/V1/bocal") ->
                    if (request.method == "POST") HttpResponse(200, """{"ocs":{"data":{"token":"jeton"}}}""")
                    else HttpResponse(200, """{"ocs":{"meta":{"statuscode":200}}}""")

                else -> HttpResponse(200, """{"ocs":{"meta":{"statuscode":200}}}""")
            }
        }
    }

    private fun gestionnaire(transport: HttpTransport) =
        ConnectionManager(TokenStore(context, FakeKeystoreBackend()), NextcloudClient(transport))

    @Test
    fun connexionReussieEnregistreLaConnexionChiffree() {
        val transport = TransportSimple()
        val gestionnaire = gestionnaire(transport)
        val result = gestionnaire.connect("https://cloud.example", "demo", "pass".toCharArray())
        assertTrue(result is NextcloudClient.LoginResult.Success)
        val stockee = gestionnaire.currentConnection()!!
        assertEquals("jeton", stockee.appToken)
        assertTrue(gestionnaire.isConnected())
    }

    @Test
    fun echecDeConnexionNEcritRien() {
        val transport = object : HttpTransport {
            override fun execute(request: HttpRequest) = HttpResponse(401, "")
        }
        val gestionnaire = gestionnaire(transport)
        val result = gestionnaire.connect("https://cloud.example", "demo", "pass".toCharArray())
        assertTrue(result is NextcloudClient.LoginResult.Failure)
        assertFalse(gestionnaire.isConnected())
        assertNull(gestionnaire.currentConnection())
    }

    @Test
    fun deconnexionRevoqueEnServeurEtPurgeTout() {
        val transport = TransportSimple()
        val gestionnaire = gestionnaire(transport)
        gestionnaire.connect("https://cloud.example", "demo", "pass".toCharArray())
        transport.requetes.clear()

        gestionnaire.disconnect()

        assertNull(gestionnaire.currentConnection())
        assertFalse(gestionnaire.isConnected())
        val revoke = transport.requetes.single()
        assertEquals("DELETE", revoke.method)
        assertTrue(revoke.url.endsWith("/ocs/v2.php/core/apppassword"))
        assertEquals("Bearer jeton", revoke.headers["Authorization"])
    }

    @Test
    fun deconnexionHorsLignePurgeQuandMemeLAppareil() {
        val transport = TransportSimple().apply { horsLigne = true }
        val gestionnaire = gestionnaire(transport)
        runCatching { gestionnaire.connect("https://cloud.example", "demo", "pass".toCharArray()) }

        transport.horsLigne = false
        gestionnaire.connect("https://cloud.example", "demo", "pass".toCharArray())

        transport.horsLigne = true
        gestionnaire.disconnect()
        assertNull(gestionnaire.currentConnection())
        assertFalse(gestionnaire.isConnected())
    }
}
