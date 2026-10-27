package fr.folzocorp.bocal.data

/**
 * Arbre synthétique reproduisant la liste exacte du cahier §4.2, sous le
 * tronc com.whatsapp/WhatsApp/ (racine de l'arbre = ce dossier). Contenus et
 * mtimes fixes pour que sha256/tailles soient déterministes.
 */
object WhatsAppFixture {

    fun bytes(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    fun build(): FakeDocumentSource = FakeDocumentSource().apply {
        // Databases/ — bases de messages chiffrées + marqueur
        addFile("Databases/msgstore.db.crypt14", bytes("base-chiffree-crypt14"))
        addFile("Databases/msgstore.db.crypt15", bytes("base-chiffree-crypt15"))
        addFile("Databases/msg-2026-10-26.1.db.crypt15", bytes("base-datede-1"))
        addFile("Databases/msg-2026-10-25.2.db.crypt14", bytes("base-datede-2"))
        addFile("Databases/backup_time", bytes("1762000000"), lastModifiedMs = 1_762_000_500_000L)
        addFile("Databases/store-journal.db", bytes("journal"))

        // Media/.keys/ — dossier CACHÉ, une clé par média (sans lui : médias perdus)
        addFile("Media/.keys/medias-index-key", bytes("cle-index"), lastModifiedMs = 1_761_900_000_000L)
        addFile("Media/.keys/keyid-0123456789abcdef", bytes("cle-media-1"))
        addFile("Media/.keys/keyid-fedcba9876543210", bytes("cle-media-2"))
        addFile("Media/.keys/.media-key-cache", bytes("meta-cle"))

        // Media/** — médias courants
        addFile("Media/WhatsApp Images/IMG-20261001-WA0001.jpg", bytes("jpeg-1"))
        addFile("Media/WhatsApp Images/IMG-20261001-WA0002.jpg", bytes("jpeg-2"))
        addFile("Media/WhatsApp Images/.nommedegoue.jpg", bytes("cache-cache"))
        addFile("Media/WhatsApp Video/VID-20261001-WA0001.mp4", bytes("mp4".repeat(5000)))
        addFile("Media/WhatsApp Voice Notes/PTA-20261025-WA0000.ogg", bytes("ogg"))
        addDirectory("Media/WhatsApp Stickers")

        // Divers — backups/ et le marqueur .wa
        addFile("backups/databases-2.db", bytes("vieux-contacts"))
        addFile(".wa", ByteArray(0), lastModifiedMs = 1_700_000_000_000L)
    }

    /** L'inventaire complet attendu de [build] (contrôle explicite du nombre). */
    val EXPECTED_FILES = listOf(
        ".wa",
        "Databases/backup_time",
        "Databases/msg-2026-10-25.2.db.crypt14",
        "Databases/msg-2026-10-26.1.db.crypt15",
        "Databases/msgstore.db.crypt14",
        "Databases/msgstore.db.crypt15",
        "Databases/store-journal.db",
        "Media/.keys/keyid-0123456789abcdef",
        "Media/.keys/keyid-fedcba9876543210",
        "Media/.keys/medias-index-key",
        "Media/.keys/.media-key-cache",
        "Media/WhatsApp Images/.nommedegoue.jpg",
        "Media/WhatsApp Images/IMG-20261001-WA0001.jpg",
        "Media/WhatsApp Images/IMG-20261001-WA0002.jpg",
        "Media/WhatsApp Video/VID-20261001-WA0001.mp4",
        "Media/WhatsApp Voice Notes/PTA-20261025-WA0000.ogg",
        "backups/databases-2.db",
    )

    /** Emplacements utilisés par les tests « fichier non lu ». */
    const val HIDDEN_UNREADABLE = "Media/.keys/keyid-0123456789abcdef"
    const val REGULAR_UNREADABLE = "Media/WhatsApp Images/IMG-20261001-WA0001.jpg"

    fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
