package fr.folzocorp.bocal.data

import fr.folzocorp.bocal.data.DocumentSource.DocumentEntry
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Arbre en mémoire pour les tests JVM du [TreeScanner] : mêmes formes que le
 * filetier SAF (taille/mtime annoncés ou inconnus, noms cachés en « . ») plus
 * deux défauts simulés — fichier illisible ([rendreIllisible]) et dossier
 * intouchable ([rendreDossierIllisible]), qui reproduisent les filetiers OEM
 * qui masquent ou refusent (cahier §4.1).
 */
open class FakeDocumentSource : DocumentSource {

    private class Node(
        val name: String,
        val isDirectory: Boolean,
        var content: ByteArray = ByteArray(0),
        var lastModifiedMs: Long? = DEFAULT_MTIME,
        /** nulle = taille annoncée normalement ; SinonTaille.NULLE = fournisseur muet. */
        var sizeAnnounced: Boolean = true,
    )

    private val root = Node("", isDirectory = true)
    private val nodes = HashMap<String, Node>()
    private val unreadable = HashSet<String>()
    private val unlistable = HashSet<String>()

    init {
        nodes[""] = root
    }

    fun addDirectory(path: String, lastModifiedMs: Long? = DEFAULT_MTIME) {
        if (path.isEmpty()) return
        var prefix = ""
        for (part in path.split('/')) {
            prefix = joinPath(prefix, part)
            nodes.getOrPut(prefix) { Node(part, isDirectory = true) }.lastModifiedMs = lastModifiedMs
        }
    }

    fun addFile(
        path: String,
        content: ByteArray,
        lastModifiedMs: Long? = DEFAULT_MTIME,
        sizeAnnounced: Boolean = true,
    ) {
        val name = path.substringAfterLast('/')
        addDirectory(parentPath(path))
        nodes[path] = Node(name, isDirectory = false, content, lastModifiedMs, sizeAnnounced)
    }

    /** Simule un fichier que le fournisseur annonce mais refuse à la lecture. */
    fun rendreIllisible(path: String) {
        require(nodes[path]?.isDirectory == false) { "pas un fichier connu : $path" }
        unreadable.add(path)
    }

    /** Simule un dossier entier que le filetier refuse (provider planté ou masqué). */
    fun rendreDossierIllisible(path: String) {
        require(nodes[path]?.isDirectory == true) { "pas un dossier connu : $path" }
        unlistable.add(path)
    }

    /** Tous les fichiers de l'arbre (chemins complets) — référence pour « 100 % ». */
    fun allFiles(): Set<String> = nodes.filterValues { !it.isDirectory }.keys

    override fun listChildren(path: String): List<DocumentEntry> {
        if (path in unlistable) throw SecurityException("accès refusé par le filetier")
        val dir = nodes[path] ?: throw FileNotFoundException("dossier inconnu : $path")
        require(dir.isDirectory) { "pas un dossier : $path" }
        val prefix = if (path.isEmpty()) "" else "$path/"
        return nodes.filterKeys { key ->
            key != path && key.startsWith(prefix) && !key.substring(prefix.length).contains('/')
        }.map { (key, node) ->
            DocumentEntry(
                name = node.name,
                isDirectory = node.isDirectory,
                sizeBytes = if (node.isDirectory) null else if (node.sizeAnnounced) node.content.size.toLong() else null,
                lastModifiedMs = node.lastModifiedMs,
            )
        }
    }

    open override fun open(path: String): InputStream {
        if (path in unreadable) throw SecurityException("lecture refusée par le filetier")
        val node = nodes[path] ?: throw FileNotFoundException("fichier inconnu : $path")
        require(!node.isDirectory) { "pas un fichier : $path" }
        return ByteArrayInputStream(node.content)
    }

    companion object {
        const val DEFAULT_MTIME = 1_762_000_000_000L
    }
}
