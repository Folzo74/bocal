package fr.folzocorp.bocal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Aller-retour complet sur la fixture cahier §4.2 contre le serveur WebDAV
 * simulé : même moteur que la preuve CI, sans réseau. C'est le critère de
 * sortie M0 (§10) rejoué localement.
 */
class BocalRoundTripTest {

    private val prefix = "WhatsApp/fixture-phone/2026-10-27"

    private class Atelier {
        val serveur = FakeWebDavServer()
        val webdav = WebDavClient(
            StoredConnection("http://test.example", serveur.user, "jeton-webdav"),
            serveur,
        )
        val moteur = BocalRoundTrip(WhatsAppFixture.build(), webdav)
    }

    @Test
    fun allerRetourDeLaFixtureSes17FichiersSha256Identiques() {
        val a = Atelier()
        val rapport = a.moteur.run(prefix)

        assertEquals(WhatsAppFixture.EXPECTED_FILES.sorted(), rapport.items.map { it.path }.sorted())
        assertEquals(
            "tous les fichiers doivent être montés une fois",
            WhatsAppFixture.EXPECTED_FILES.sorted(),
            rapport.uploaded.sorted(),
        )
        assertTrue("fichiers non lus : ${rapport.unreadable}", rapport.unreadable.isEmpty())
        assertTrue("échecs de monture : ${rapport.failedUpload}", rapport.failedUpload.isEmpty())
        assertTrue("écarts de sha256 : ${rapport.mismatches}", rapport.mismatches.isEmpty())
        assertTrue("aller-retour non identique", rapport.allIdentical)

        for (chemin in listOf(
            "Media/.keys/medias-index-key",
            "Databases/msgstore.db.crypt14",
            ".wa",
        )) {
            assertTrue("$chemin doit figurer au sha256SUMS", rapport.sha256SumsText().contains(chemin))
        }
    }

    @Test
    fun sha256SumsListeSourceEtServeurPuisVerdict() {
        val rapport = Atelier().moteur.run(prefix)
        val texte = rapport.sha256SumsText()

        val lignesSha = texte.lines().count { Regex("^[0-9a-f]{64}  \\S.*").matches(it) }
        assertEquals(2 * WhatsAppFixture.EXPECTED_FILES.size, lignesSha)
        assertTrue(texte.contains("# SOURCE (17 fichiers)"))
        assertTrue(texte.contains("# SERVEUR (retéléchargé WebDAV)"))
        assertTrue(texte.contains("# VERDICT : 17/17 identiques"))
    }

    @Test
    fun secondePasseSurMemeBocalNEcraseRienEtResteIdentique() {
        val a = Atelier()
        a.moteur.run(prefix)
        val avant = a.serveur.files.toMap()

        val second = a.moteur.run(prefix)
        assertTrue("deuxième passe : rien de monté", second.uploaded.isEmpty())
        assertEquals(WhatsAppFixture.EXPECTED_FILES.sorted(), second.alreadyThere.sorted())
        assertTrue("aller-retour toujours identique", second.allIdentical)
        for ((chemin, octets) in avant) {
            assertTrue(
                "le serveur a été modifié hors écrasement autorisé : $chemin",
                octets.contentEquals(a.serveur.files[chemin] ?: ByteArray(0)),
            )
        }
    }

    @Test
    fun jourEtAppareilDonnentLePrefixeDuCahier() {
        val a = Atelier()
        assertEquals(
            prefix,
            WebDavClient.bocalPrefix("fixture-phone", LocalDate.of(2026, 10, 27)),
        )
        a.moteur.run(prefix)
        assertTrue(a.serveur.collections.any { it.endsWith("Media/.keys") })
        assertTrue(a.serveur.collections.any { it.endsWith("Media/WhatsApp Stickers") })
    }
}
