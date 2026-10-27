package fr.folzocorp.bocal.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import java.io.File

/**
 * Mémorisation de l'arbre SAF choisi (cahier §4.1 : la SEULE capacité
 * d'accès à WhatsApp est cette URI persistée — aucune permission large).
 * La prise de permission persistante est la partie qui compte ; l'URI elle-
 * même n'est pas un secret (le fichier, dans le répertoire privé de l'app,
 * n'est qu'un rappel pratique — la source de vérité reste le ContentResolver).
 *
 * Lecture seule assumée en M0 : [save] ne demande que FLAG_GRANT_READ. La
 * scène de restauration (M1-C, écriture via SAF) redemandera une permission
 * avec l'écriture en conscience.
 */
class TreeStore(
    context: Context,
    private val resolver: android.content.ContentResolver = context.contentResolver,
    private val file: File = File(context.getDir(NAME, Context.MODE_PRIVATE), FILE_NAME),
) {

    companion object {
        private const val NAME = "bocal"
        private const val FILE_NAME = "arbre.txt"
        private const val MAGIC = "BOCAL-TREE-1"
    }

    /** Vrai quand le fichier mémorisé était illisible (UI : « il faut rechoisir le dossier »). */
    var lectureEtaitIllisible = false
        private set

    /** Prend la permission persistante (lecture) puis mémorise l'URI. */
    fun save(treeUri: Uri) {
        resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText("$MAGIC\n${treeUri}\n")
        if (!temp.renameTo(file)) {
            temp.delete()
            file.delete()
            temp.renameTo(file)
        }
    }

    /** L'arbre mémorisé, ou null (jamais mémorisé, fichier corrompu, ou permission perdue). */
    fun load(): Uri? {
        lectureEtaitIllisible = false
        if (!file.exists()) return null
        val uri = try {
            val lines = file.readText().split('\n')
            require(lines[0] == MAGIC) { "format invalide" }
            lines[1].toUri()
        } catch (exception: Exception) {
            wipe()
            lectureEtaitIllisible = true
            return null
        }
        if (!DocumentsContract.isTreeUri(uri)) {
            wipe()
            lectureEtaitIllisible = true
            return null
        }
        if (!hasPersistedReadPermission(uri)) {
            // Le fichier est propre mais l'utilisateur a révoqué l'accès (ou
            // l'app a été reinstallée ailleurs) : rien à purger d'urgent,
            // l'UI expliquera qu'il faut rechoisir le dossier.
            return null
        }
        return uri
    }

    /** Purge du rappel local (la capacité SAF, elle, reste jusqu'à oubli ou révocation). */
    private fun wipe() {
        file.delete()
        File(file.parentFile, "$FILE_NAME.tmp").delete()
    }

    /** Oubli propre : permission relâchée ET fichier supprimé. */
    fun forget() {
        val stored = runCatching { file.takeIf { it.exists() }?.readText()?.lines()?.getOrNull(1) }
            .getOrNull()
        if (stored != null) {
            runCatching {
                resolver.releasePersistableUriPermission(stored.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        file.delete()
        File(file.parentFile, "$FILE_NAME.tmp").delete()
    }

    fun isConfigured(): Boolean = file.exists()

    fun hasPersistedReadPermission(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
}
