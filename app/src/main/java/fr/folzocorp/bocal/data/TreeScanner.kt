package fr.folzocorp.bocal.data

import java.io.IOException
import java.security.MessageDigest

/** Une ligne d'inventaire : un fichier lu intégralement (cahier §4.3 : empreinte locale). */
data class InventoryEntry(
    /** Chemin relatif dans l'arbre choisi (jamais l'URI complet). */
    val path: String,
    val sizeBytes: Long?,
    val lastModifiedMs: Long?,
    /** sha256 hexadécimal (minuscules) du contenu entier. */
    val sha256: String,
    val isHidden: Boolean,
)

/** Un fichier (ou dossier) que l'arbre a annoncé mais que l'app n'a pas pu lire. */
data class UnreadFile(
    val path: String,
    val reason: String,
)

/**
 * Résultat d'un scan. Règle d'ingénierie du cahier §4.2 : « la liste noire
 * n'existe pas » — un fichier absent de [entries] ET absent de [unread] est
 * un bug, jamais un choix.
 */
data class InventoryReport(
    val entries: List<InventoryEntry>,
    val unread: List<UnreadFile>,
) {
    val fullyReadable: Boolean get() = unread.isEmpty()
}

/**
 * Énumère l'arbre entier, fichiers cachés compris, et dresse l'inventaire
 * (chemin, taille, mtime, sha256 — cahier §4.3). Zéro tolérance silencieuse :
 * chaque erreur de lecture ou de listage est consignée dans le rapport et
 * n'interrompt jamais la suite du scan.
 */
class TreeScanner(private val source: DocumentSource) {

    fun scan(): InventoryReport {
        val entries = mutableListOf<InventoryEntry>()
        val unread = mutableListOf<UnreadFile>()
        val pending = ArrayDeque<String>()
        pending.addLast("")
        while (pending.isNotEmpty()) {
            val dir = pending.removeFirst()
            val children = try {
                source.listChildren(dir)
            } catch (exception: Exception) {
                // Dossier illisible en entier : c'est un rapport, pas un silence.
                unread.add(UnreadFile(displayPath(dir), motif(exception)))
                continue
            }
            for (child in children.sortedBy { it.name }) {
                val path = joinPath(dir, child.name)
                if (child.isDirectory) {
                    pending.addLast(path)
                    continue
                }
                read(child, path, entries, unread)
            }
        }
        return InventoryReport(entries.sortedBy { it.path }, unread.sortedBy { it.path })
    }

    private fun read(
        child: DocumentSource.DocumentEntry,
        path: String,
        entries: MutableList<InventoryEntry>,
        unread: MutableList<UnreadFile>,
    ) {
        try {
            val streamed = hash(path)
            entries.add(
                InventoryEntry(
                    path = path,
                    sizeBytes = streamed.bytesRead,
                    lastModifiedMs = child.lastModifiedMs,
                    sha256 = streamed.sha256,
                    // Un fichier sous dossier caché (Media/.keys/…) est caché au sens du cahier.
                    isHidden = isHiddenPath(path),
                ),
            )
        } catch (exception: Exception) {
            unread.add(UnreadFile(path, motif(exception)))
        }
    }

    private data class Hashed(val sha256: String, val bytesRead: Long)

    /** sha256 en streaming : les fichiers de plusieurs Go ne tiennent jamais en mémoire. */
    private fun hash(path: String): Hashed {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        source.open(path).use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                total += count.toLong()
            }
        }
        return Hashed(digest.digest().joinToString("") { "%02x".format(it) }, total)
    }

    private fun displayPath(dir: String): String = if (dir.isEmpty()) "/" else "$dir/"

    private fun motif(exception: Exception): String {
        val message = exception.message?.takeIf { it.isNotBlank() }
        return if (message == null) exception.javaClass.simpleName
        else "${exception.javaClass.simpleName} : $message"
    }
}
