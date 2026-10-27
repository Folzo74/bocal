package fr.folzocorp.bocal.data

import java.io.InputStream

/**
 * Abstraction d'un arbre de dossiers vu par l'app. En production : l'arbre
 * SAF choisi par l'utilisateur ([SafDocumentSource]). En test : un arbre en
 * mémoire (fixture cahier §4.2). Le moteur d'énumération ([TreeScanner]) ne
 * connaît QUE cette interface — c'est ce qui rend testable, sans émulateur,
 * l'exigence « 100 % d'énumération, fichiers cachés compris ».
 *
 * Chemins : relatifs à la racine de l'arbre, séparés par « / », sans slash
 * initial (« Databases/msgstore.db.crypt14 »).
 */
interface DocumentSource {

    /** Enfants du dossier `path` (racine = ""). Lève une exception si le dossier est illisible. */
    fun listChildren(path: String): List<DocumentEntry>

    /** Ouvre le fichier `path` en lecture. Lève une exception si illisible. Ne retourne jamais null. */
    fun open(path: String): InputStream

    data class DocumentEntry(
        val name: String,
        val isDirectory: Boolean,
        /** Taille annoncée par le fournisseur, nulle si inconnue (alors mesurée à la lecture). */
        val sizeBytes: Long?,
        /** Date de modification annoncée (epoch ms), nulle si inconnue. */
        val lastModifiedMs: Long?,
    )
}

/** Un nom est caché quand il commence par « . » (convention unix ; cahier §4.2 : dossier Media/.keys). */
fun isHiddenName(name: String): Boolean = name.startsWith(".")

/** Un chemin est caché si lui-même OU l'un de ses dossiers parents est caché. */
fun isHiddenPath(path: String): Boolean = path.split('/').any { isHiddenName(it) }

/** Colle un nom d'enfant à un chemin relatif. */
fun joinPath(parent: String, name: String): String =
    if (parent.isEmpty()) name else "$parent/$name"

/** Chemin du dossier parent (\"\" pour un fichier à la racine). */
fun parentPath(path: String): String = path.substringBeforeLast('/', "")
