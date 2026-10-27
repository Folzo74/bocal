package fr.folzocorp.bocal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reconnaissances de la liste §4.2 : positifs ET négatifs. */
class WhatsAppPresetTest {

    @Test
    fun basesDeMessagesReconnues() {
        for (path in listOf(
            "Databases/msgstore.db.crypt14",
            "Databases/msgstore.db.crypt15",
            "Databases/msgstore.db.crypt16",
            "Databases/msg-2026-10-26.1.db.crypt15",
            "Databases/msg-2026-01-02.12.db.crypt14",
        )) {
            assertTrue("base attendue : $path", WhatsAppPreset.isMessageDb(path))
            assertEquals(WhatsAppPreset.Role.MESSAGE_DB, WhatsAppPreset.classify(path))
        }
    }

    @Test
    fun faussesBasesDeMessagesRejetees() {
        for (path in listOf(
            "Databases/msgstore.db.crypt13",
            "Databases/msgstore.db.crypt17",
            "Databases/msgstore.db.crypt",
            "Databases/msgstore.db.crypt14.tmp",
            "Databases/msgstore.db.crypt14x",
            "Databases/MSGSTORE.DB.CRYPT14",
            "Databases/msg-2026-10-26.db.crypt14",
            "Databases/msg-2026-13-99.1.db.crypt99",
            "Media/msgstore.db.crypt14",
            "msgstore.db.crypt14",
            "Databases/sous-dossier/msgstore.db.crypt14",
            "Databases/store-journal.db",
        )) {
            assertFalse("fausse base acceptée : $path", WhatsAppPreset.isMessageDb(path))
        }
    }

    @Test
    fun marqueurBackupTime() {
        assertTrue(WhatsAppPreset.isBackupTime("Databases/backup_time"))
        assertEquals(WhatsAppPreset.Role.BACKUP_TIME, WhatsAppPreset.classify("Databases/backup_time"))
        for (path in listOf("backup_time", "Databases/Backup_Time", "Databases/backup_time.json", "backups/backup_time")) {
            assertFalse("faux marqueur accepté : $path", WhatsAppPreset.isBackupTime(path))
        }
    }

    @Test
    fun clesMediasDuDossierCache() {
        for (path in listOf(
            "Media/.keys/medias-index-key",
            "Media/.keys/keyid-0123456789abcdef",
            "Media/.keys/.media-key-cache",
            "Media/.keys/sous-dossier/cle",
        )) {
            assertTrue("clé attendue : $path", WhatsAppPreset.isMediaKey(path))
            assertEquals(WhatsAppPreset.Role.MEDIA_KEY, WhatsAppPreset.classify(path))
            assertFalse("une clé n'est pas un média ordinaire", WhatsAppPreset.isMedia(path))
        }
        for (path in listOf("Media/keys/cle", "Media/.keys", "keys/Media/.keys/cle")) {
            assertFalse("fausse clé acceptée : $path", WhatsAppPreset.isMediaKey(path))
        }
    }

    @Test
    fun mediasOrdinaires() {
        for (path in listOf(
            "Media/WhatsApp Images/IMG-20261001-WA0001.jpg",
            "Media/WhatsApp Video/VID-20261001-WA0001.mp4",
            "Media/WhatsApp Voice Notes/PTA-20261025-WA0000.ogg",
            "Media/.medias_noeuds/index",
        )) {
            assertTrue("média attendu : $path", WhatsAppPreset.isMedia(path))
            assertEquals(WhatsAppPreset.Role.MEDIA, WhatsAppPreset.classify(path))
        }
        assertFalse(WhatsAppPreset.isMedia("backups/x"))
        assertFalse(WhatsAppPreset.isMedia(".wa"))
    }

    @Test
    fun divers() {
        assertTrue(WhatsAppPreset.isWaMarker(".wa"))
        assertFalse(WhatsAppPreset.isWaMarker("Databases/.wa"))
        assertFalse(WhatsAppPreset.isWaMarker("backups/.wa"))
        assertTrue(WhatsAppPreset.isLegacyBackup("backups/databases-2.db"))
        assertTrue(WhatsAppPreset.isLegacyBackup("backups/sous-dossier/x.db"))
        assertFalse(WhatsAppPreset.isLegacyBackup("Databases/x.db"))
        assertEquals(WhatsAppPreset.Role.OTHER, WhatsAppPreset.classify("backups/databases-2.db"))
        assertEquals(WhatsAppPreset.Role.OTHER, WhatsAppPreset.classify(".wa"))
    }

    @Test
    fun toutInventaireDeFixtureEstBienQualifie() {
        val roles = WhatsAppFixture.EXPECTED_FILES.associateWith { WhatsAppPreset.classify(it) }
        assertEquals(WhatsAppPreset.Role.MESSAGE_DB, roles["Databases/msgstore.db.crypt14"])
        assertEquals(WhatsAppPreset.Role.MESSAGE_DB, roles["Databases/msg-2026-10-26.1.db.crypt15"])
        assertEquals(WhatsAppPreset.Role.BACKUP_TIME, roles["Databases/backup_time"])
        assertEquals(WhatsAppPreset.Role.MEDIA_KEY, roles["Media/.keys/medias-index-key"])
        assertEquals(WhatsAppPreset.Role.MEDIA, roles["Media/WhatsApp Images/IMG-20261001-WA0001.jpg"])
        assertEquals(WhatsAppPreset.Role.OTHER, roles[".wa"])
        assertEquals(WhatsAppPreset.Role.OTHER, roles["backups/databases-2.db"])
    }

    @Test
    fun formeDArbreWhatsappReconnue() {
        assertTrue(WhatsAppPreset.looksLikeWhatsappTree(WhatsAppFixture.EXPECTED_FILES))
        assertTrue(WhatsAppPreset.looksLikeWhatsappTree(listOf(".wa")))
        assertFalse(WhatsAppPreset.looksLikeWhatsappTree(listOf("Media/WhatsApp Images/x.jpg")))
        assertFalse(WhatsAppPreset.looksLikeWhatsappTree(emptyList()))
    }

    @Test
    fun presetConstantes() {
        assertEquals("com.whatsapp", WhatsAppPreset.PACKAGE_NAME)
        assertEquals("Databases/backup_time", WhatsAppPreset.BACKUP_TIME)
        assertEquals("Media/.keys", WhatsAppPreset.KEYS_DIR)
    }
}
