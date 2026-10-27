package fr.folzocorp.bocal.data

import android.net.Uri
import android.provider.DocumentsContract

/**
 * Preset « WhatsApp » du cahier §4.2 : chemin d'accès via SAF
 * (Android/media/com.whatsapp, seul sous-dossier de Android laissé accessible
 * depuis Android 11 — §4.1) et reconnaissance des morceaux qui rendent une
 * restauration possible. L'app sauvegarde l'arbre ENTIER : ce preset sert à
 * qualifier et contrôler, jamais à filtrer silencieusement.
 */
object WhatsAppPreset {

    const val PACKAGE_NAME = "com.whatsapp"

    /** Filetier externe du téléphone : c'est là que le picker doit atterrir. */
    const val EXTERNAL_AUTHORITY = "com.android.externalstorage.documents"
    const val ANDROID_MEDIA_DOC_ID = "primary:Android/media"
    const val WHATSAPP_DOC_ID = "primary:Android/media/com.whatsapp"

    /** Sous le tronc com.whatsapp/WhatsApp/ (arborescence vue par le picker). */
    const val DATABASES_DIR = "Databases"
    const val MEDIA_DIR = "Media"
    const val KEYS_DIR = "Media/.keys"
    const val BACKUP_TIME = "Databases/backup_time"
    const val BACKUPS_DIR = "backups"
    const val WA_MARKER = ".wa"

    /** Bases de messages chiffrées : msgstore.db.cryptNN et msg-<date>.<n>.db.cryptNN (NN = 14/15/16). */
    private val CRYPT_DB = Regex("^(?:msgstore|msg-\\d{4}-\\d{2}-\\d{2}\\.\\d+)\\.db\\.crypt(?:14|15|16)$")

    /** Rôle d'un fichier dans une restauration (cahier §4.2). */
    enum class Role {
        MESSAGE_DB,
        BACKUP_TIME,
        MEDIA_KEY,
        MEDIA,
        OTHER,
    }

    /** URI d'index du picker (EXTRA_INITIAL_URI) : la vue démarre sur le dossier WhatsApp. */
    fun initialTreeHint(): Uri {
        val treeUri = DocumentsContract.buildTreeDocumentUri(EXTERNAL_AUTHORITY, ANDROID_MEDIA_DOC_ID)
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, WHATSAPP_DOC_ID)
    }

    fun isMessageDb(path: String): Boolean =
        parentPath(path) == DATABASES_DIR && CRYPT_DB.matches(path.substringAfterLast('/'))

    fun isBackupTime(path: String): Boolean = path == BACKUP_TIME

    fun isMediaKey(path: String): Boolean = isUnder(path, KEYS_DIR)

    fun isMedia(path: String): Boolean = isUnder(path, MEDIA_DIR) && !isMediaKey(path)

    fun isWaMarker(path: String): Boolean = path == WA_MARKER

    fun isLegacyBackup(path: String): Boolean = isUnder(path, BACKUPS_DIR)

    fun classify(path: String): Role = when {
        isMessageDb(path) -> Role.MESSAGE_DB
        isBackupTime(path) -> Role.BACKUP_TIME
        isMediaKey(path) -> Role.MEDIA_KEY
        isMedia(path) -> Role.MEDIA
        else -> Role.OTHER
    }

    /** Vrai si l'inventaire d'un arbre « preset WhatsApp » a la forme attendue (contrôle qualité, jamais un filtrage). */
    fun looksLikeWhatsappTree(paths: Collection<String>): Boolean =
        paths.any { isMessageDb(it) } || paths.any { isWaMarker(it) }

    private fun isUnder(path: String, dir: String): Boolean =
        path.startsWith("$dir/")
}

/**
 * Contrôle du treeUri mémorisé : le tronc doit être Android/media (ou
 * com.whatsapp directement) sur le filetier externe. Un arbre « dossier
 * libre » choisi ailleurs passe par d'autres contrôles, pas par celui-ci.
 */
fun isLikelyWhatsappTree(uri: Uri): Boolean {
    val segments = uri.pathSegments
    val treeIndex = segments.indexOf("tree")
    if (uri.authority != WhatsAppPreset.EXTERNAL_AUTHORITY || treeIndex < 0) return false
    if (treeIndex + 1 >= segments.size) return false
    val root = segments[treeIndex + 1]
    return root == WhatsAppPreset.WHATSAPP_DOC_ID || root == WhatsAppPreset.ANDROID_MEDIA_DOC_ID
}
