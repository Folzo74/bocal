package fr.folzocorp.bocal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests du moteur d'énumération sur l'arbre synthétique §4.2 — exécution
 * pure JVM (la fixture est un [DocumentSource] en mémoire, pas un émulateur).
 */
class TreeScannerTest {

    private fun scanner(fixture: FakeDocumentSource = WhatsAppFixture.build()) =
        TreeScanner(fixture)

    @Test
    fun enumereCentPourcentDeArbreWhatsapp() {
        val report = scanner().scan()
        assertEquals(
            "100 % d'énumération exigée (cahier §4.2)",
            WhatsAppFixture.EXPECTED_FILES.toSet(),
            report.entries.map { it.path }.toSet(),
        )
        assertEquals(WhatsAppFixture.EXPECTED_FILES.size, report.entries.size)
        assertTrue("arbre sain : rien de non lu", report.fullyReadable)
    }

    @Test
    fun lesFichiersCachesSontDansLinventaireEtMarques() {
        val report = scanner().scan()
        val byPath = report.entries.associateBy { it.path }
        for (path in listOf(
            "Media/.keys/medias-index-key",
            "Media/.keys/keyid-0123456789abcdef",
            "Media/.keys/.media-key-cache",
            "Media/WhatsApp Images/.nommedegoue.jpg",
            ".wa",
        )) {
            assertTrue("caché absent de l'inventaire : $path", byPath.containsKey(path))
            assertTrue("mal marqué caché : $path", byPath.getValue(path).isHidden)
        }
        assertFalse(
            "faussement marqué caché",
            byPath.getValue("Media/WhatsApp Images/IMG-20261001-WA0001.jpg").isHidden,
        )
    }

    @Test
    fun chaqueLignePorteCheminTailleMtimeSha256() {
        val report = scanner().scan()
        val byPath = report.entries.associateBy { it.path }

        val backupTime = byPath.getValue("Databases/backup_time")
        assertEquals(10L, backupTime.sizeBytes)
        assertEquals(1_762_000_500_000L, backupTime.lastModifiedMs)
        assertEquals(
            WhatsAppFixture.sha256(WhatsAppFixture.bytes("1762000000")),
            backupTime.sha256,
        )

        val cle = byPath.getValue("Media/.keys/keyid-0123456789abcdef")
        assertEquals(11L, cle.sizeBytes)
        assertEquals(FakeDocumentSource.DEFAULT_MTIME, cle.lastModifiedMs)
        assertEquals(WhatsAppFixture.sha256(WhatsAppFixture.bytes("cle-media-1")), cle.sha256)

        val marqueur = byPath.getValue(".wa")
        assertEquals(0L, marqueur.sizeBytes)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", marqueur.sha256)

        val video = byPath.getValue("Media/WhatsApp Video/VID-20261001-WA0001.mp4")
        assertEquals(15_000L, video.sizeBytes)
    }

    @Test
    fun tailleInconnueDuFournisseurEstMesureeALecture() {
        val source = FakeDocumentSource()
        source.addFile("seulement-du-contenu.bin", WhatsAppFixture.bytes("ab".repeat(4000)), sizeAnnounced = false)
        val entry = TreeScanner(source).scan().entries.single()
        assertEquals(8_000L, entry.sizeBytes)
    }

    @Test
    fun fichierNonLuCachéOuNonApparaitAuRapportSansInterrompre() {
        val fixture = WhatsAppFixture.build()
        fixture.rendreIllisible(WhatsAppFixture.HIDDEN_UNREADABLE)
        fixture.rendreIllisible(WhatsAppFixture.REGULAR_UNREADABLE)

        val report = TreeScanner(fixture).scan()

        assertFalse("le rapport ne doit jamais être silencieux", report.fullyReadable)
        val unreadPaths = report.unread.map { it.path }.toSet()
        assertEquals(
            setOf(WhatsAppFixture.HIDDEN_UNREADABLE, WhatsAppFixture.REGULAR_UNREADABLE),
            unreadPaths,
        )
        for (unread in report.unread) {
            assertTrue("motif vide pour ${unread.path}", unread.reason.isNotBlank())
        }
        assertEquals(
            "le scan doit continuer après une erreur : tout le reste est lu",
            fixture.allFiles(),
            report.entries.map { it.path }.toSet() + unreadPaths,
        )
    }

    @Test
    fun dossierEntierementIllisibleEstSignaleLeResteEstEnumere() {
        val fixture = WhatsAppFixture.build()
        fixture.rendreDossierIllisible("Media/.keys")

        val report = TreeScanner(fixture).scan()

        assertEquals(listOf("Media/.keys/"), report.unread.map { it.path })
        assertTrue(report.unread.single().reason.isNotBlank())
        val remaining = WhatsAppFixture.EXPECTED_FILES.filterNot { it.startsWith("Media/.keys/") }
        assertEquals(remaining.toSet(), report.entries.map { it.path }.toSet())
    }

    @Test
    fun coherenceEntreesPlusNonLusEgalTotal() {
        val fixture = WhatsAppFixture.build()
        fixture.rendreIllisible(WhatsAppFixture.HIDDEN_UNREADABLE)
        val report = TreeScanner(fixture).scan()
        assertEquals(
            "aucun fichier ne doit disparaître entre entrées et non-lus",
            fixture.allFiles().size,
            report.entries.size + report.unread.size,
        )
    }

    @Test
    fun arbreVideDonneRapportVideSansException() {
        val report = TreeScanner(FakeDocumentSource()).scan()
        assertTrue(report.entries.isEmpty())
        assertTrue(report.unread.isEmpty())
    }

    @Test
    fun sousDossierVideNeJettePas() {
        val fixture = WhatsAppFixture.build()
        val report = TreeScanner(fixture).scan()
        assertTrue("le dossier stickers vide est traversé sans erreur", report.fullyReadable)
    }

    @Test
    fun fichierFantomeAnnnonceMaisAbsentEstSignale() {
        val fixture = object : FakeDocumentSource() {
            override fun open(path: String): java.io.InputStream {
                if (path == "backups/databases-2.db") {
                    throw java.io.FileNotFoundException("fichier disparu entre listage et lecture")
                }
                return super.open(path)
            }
        }.apply { WhatsAppFixture.build().allFiles().forEach { addFile(it, WhatsAppFixture.bytes(it)) } }
        val report = TreeScanner(fixture).scan()
        assertEquals(listOf("backups/databases-2.db"), report.unread.map { it.path })
        assertTrue(report.unread.single().reason.contains("FileNotFoundException"))
    }
}
