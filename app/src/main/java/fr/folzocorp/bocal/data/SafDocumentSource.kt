package fr.folzocorp.bocal.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Implémentation SAF de [DocumentSource] : requêtes directes sur
 * DocumentsContract (et pas DocumentFile), parce que le contrat renvoie TOUS
 * les enfants — fichiers cachés compris — là où certaines surcouches OEM des
 * listFiles() filtrent les noms en « . » (cahier §4.1, vérification atelier).
 *
 * Les identifiants de documents sont découverts au fil du listage ; [open]
 * ne marche donc qu'après le listage du parent — ce que [TreeScanner] fait
 * toujours avant de lire.
 */
class SafDocumentSource(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : DocumentSource {

    private val documentIds = HashMap<String, String>()

    init {
        documentIds[""] = DocumentsContract.getTreeDocumentId(treeUri)
    }

    override fun listChildren(path: String): List<DocumentSource.DocumentEntry> {
        val parentId = documentIds[path] ?: throw FileNotFoundException("dossier non listé : $path")
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val entries = mutableListOf<DocumentSource.DocumentEntry>()
        resolver.query(childrenUri, COLUMNS, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val name = if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                    cursor.getString(nameColumn)
                } else {
                    continue
                }
                val isDirectory = mimeColumn >= 0 && !cursor.isNull(mimeColumn) &&
                    cursor.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR
                documentIds[joinPath(path, name)] =
                    if (idColumn >= 0 && !cursor.isNull(idColumn)) cursor.getString(idColumn)
                    else "$parentId/$name"
                entries.add(
                    DocumentSource.DocumentEntry(
                        name = name,
                        isDirectory = isDirectory,
                        sizeBytes = longAt(cursor, sizeColumn),
                        lastModifiedMs = longAt(cursor, modifiedColumn),
                    ),
                )
            }
        } ?: throw FileNotFoundException("listage vide du fournisseur : $path")
        return entries
    }

    override fun open(path: String): InputStream {
        val documentId = documentIds[path]
            ?: throw FileNotFoundException("fichier non listé : $path")
        return resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId))
            ?: throw FileNotFoundException("flux refusé : $path")
    }

    private fun longAt(cursor: android.database.Cursor, column: Int): Long? =
        if (column >= 0 && !cursor.isNull(column)) cursor.getLong(column) else null

    private companion object {
        val COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
