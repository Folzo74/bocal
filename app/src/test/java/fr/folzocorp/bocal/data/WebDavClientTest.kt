package fr.folzocorp.bocal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Mécanique WebDAV du bocal sur serveur simulé : chemins (§4.4), jamais
 * d'écrasement (If-None-Match), liste PROPFIND, garde-fou de préfixe.
 */
class WebDavClientTest {

    private val jour = LocalDate.of(2026, 10, 27)

    private class Fixture(val serveur: FakeWebDavServer = FakeWebDavServer()) {
        val client = WebDavClient(
            StoredConnection("http://test.example", serveur.user, "jeton-webdav"),
            serveur,
        )
    }

    private fun fixtureAvecDossiers(): Fixture = Fixture().apply {
        check(client.ensureCollection("WhatsApp/pixel/2026-10-27"))
    }

    @Test
    fun cheminDuBocalConformeCahier() {
        assertEquals(
            "WhatsApp/pixel/2026-10-27",
            WebDavClient.bocalPrefix("pixel", jour),
        )
        assertEquals(
            "WhatsApp/pixel/2026-10-27.2",
            WebDavClient.bocalPrefix("pixel", jour, 2),
        )
    }

    @Test
    fun nomAppareilAssainiSansTraversal() {
        val douteux = WebDavClient.sanitizeDevice("../../evil")
        assertFalse(douteux.split('/').any { it == ".." })
        assertFalse(WebDavClient.bocalPrefix(douteux, jour).contains("//"))
        assertEquals("appareil", WebDavClient.sanitizeDevice("   "))
        assertEquals("Pixel 7", WebDavClient.sanitizeDevice("  Pixel 7  "))
    }

    @Test
    fun mkCOLCreeChaqueSegmentEtLePutCreeLeFichier() {
        val f = Fixture()
        assertTrue(f.client.ensureCollection("WhatsApp/pixel/2026-10-27/Databases"))
        val mkcols = f.serveur.requetes.filter { it.method == "MKCOL" }.map {
            FakeWebDavServer.relative(it.url)
        }
        assertEquals(
            listOf("WhatsApp", "WhatsApp/pixel", "WhatsApp/pixel/2026-10-27", "WhatsApp/pixel/2026-10-27/Databases"),
            mkcols,
        )

        val result = f.client.putNoOverwrite(
            "WhatsApp/pixel/2026-10-27/Databases/backup_time",
            "1762000000".toByteArray(),
        )
        assertTrue(result is WebDavClient.PutResult.Created)
        assertArrayEqualsSafe(
            "1762000000".toByteArray(),
            f.serveur.files["WhatsApp/pixel/2026-10-27/Databases/backup_time"],
        )
    }

    @Test
    fun putSurCibleExistanteRefuseSansEcraser() {
        val f = fixtureAvecDossiers()
        check(f.client.ensureCollection("WhatsApp/pixel/2026-10-27/Databases"))
        val cible = "WhatsApp/pixel/2026-10-27/Databases/msgstore.db.crypt14"
        assertTrue(f.client.putNoOverwrite(cible, "original".toByteArray()) is WebDavClient.PutResult.Created)

        val second = f.client.putNoOverwrite(cible, "BOUGÉ".toByteArray())
        assertTrue("le second PUT doit être AlreadyExists", second is WebDavClient.PutResult.AlreadyExists)
        assertArrayEqualsSafe("original".toByteArray(), f.serveur.files[cible])

        val put = f.serveur.requetes.last { it.method == "PUT" }
        assertEquals("*", put.headers["If-None-Match"])
        assertEquals("Bearer jeton-webdav", put.headers["Authorization"])
    }

    @Test
    fun listePropfindRetrouveFichiersEtDossiersYComprisCaches() {
        val f = fixtureAvecDossiers()
        check(f.client.ensureCollection("WhatsApp/pixel/2026-10-27/Media/.keys"))
        check(f.client.ensureCollection("WhatsApp/pixel/2026-10-27/Media/WhatsApp Images"))
        f.client.putNoOverwrite("WhatsApp/pixel/2026-10-27/Media/.keys/keyid-01", "k".toByteArray())
        f.client.putNoOverwrite("WhatsApp/pixel/2026-10-27/Media/WhatsApp Images/IMG 1.jpg", "i".toByteArray())

        val entrees = f.client.list("WhatsApp/pixel/2026-10-27")
        val fichiers = entrees.filter { !it.isCollection }.map { it.path }
        assertTrue(fichiers.any { it.endsWith("Media/.keys/keyid-01") })
        assertTrue(fichiers.any { it.endsWith("Media/WhatsApp Images/IMG 1.jpg") })
        assertTrue(entrees.any { it.isCollection && it.path.endsWith("Media/WhatsApp Images") })
    }

    @Test
    fun gardeFouRefuseToutCheminHorsPrefixeWhatsApp() {
        val f = Fixture()
        assertThrows(IllegalArgumentException::class.java) {
            f.client.putNoOverwrite("Secrets/x.txt", "x".toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.client.get("Autre/appareil/jour/fichier")
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.client.ensureCollection("WhatsApp")
        }
        assertThrows(IllegalArgumentException::class.java) {
            f.client.delete("index.html")
        }
    }

    @Test
    fun bocalLibreAjouteSuffixePourLeMemeJour() {
        val f = Fixture()
        check(f.client.ensureCollection("WhatsApp/pixel/2026-10-27"))
        check(f.client.ensureCollection("WhatsApp/pixel/2026-10-27.1"))
        assertEquals("WhatsApp/pixel/2026-10-27.2", f.client.freeBocalPrefix("pixel", jour))
    }

    @Test
    fun bocalLibreVideServeurInexistantDonneJourSansSuffixe() {
        val f = Fixture()
        assertEquals("WhatsApp/pixel/2026-10-27", f.client.freeBocalPrefix("pixel", jour))
    }

    private fun assertArrayEqualsSafe(expected: ByteArray, actual: ByteArray?) {
        assertTrue(
            "contenu serveur inattendu",
            actual != null && expected.contentEquals(actual),
        )
    }
}
