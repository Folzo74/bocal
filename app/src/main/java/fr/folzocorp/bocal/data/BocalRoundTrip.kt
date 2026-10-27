package fr.folzocorp.bocal.data

/**
 * Aller-retour d'un bocal (critère de sortie M0, cahier §10) : remplir le
 * bocal depuis un [DocumentSource] (arborescence SAF ou fixture §4.2), puis
 * le retélécharger et comparer les sha256 de TOUS les fichiers.
 *
 * Zéro silence (§4.2) : chaque fichier non lu ou non monté est consigné dans
 * le rapport. Jamais d'écrasement (§4.4) : le montage passe par
 * [WebDavClient.putNoOverwrite] ; une cible existante est signalée, jamais
 * modifiée.
 */
class BocalRoundTrip(
    private val source: DocumentSource,
    private val webdav: WebDavClient,
) {

    data class Item(
        val path: String,
        val bytes: ByteArray,
        val sha256: String,
    )

    data class Report(
        val prefix: String,
        val items: List<Item>,
        val uploaded: List<String>,
        val alreadyThere: List<String>,
        val failedUpload: List<Pair<String, String>>,
        val unreadable: List<Pair<String, String>>,
        /** sha256 (minuscules) de chaque fichier retéléchargé ; absent = jamais redescendu. */
        val serverSha: Map<String, String>,
    ) {
        val sourceSha: Map<String, String> get() = items.associate { it.path to it.sha256 }

        /** Chemins dont le sha256 serveur diffère du source (ou absents du serveur). */
        val mismatches: List<String>
            get() = items.mapNotNull {
                if (serverSha[it.path] == it.sha256) null else it.path
            }

        val allIdentical: Boolean
            get() = failedUpload.isEmpty() && unreadable.isEmpty() &&
                mismatches.isEmpty() && serverSha.size == items.size && items.isNotEmpty()

        /** Fichier preuve « sha256SUMS » du aller-retour : source puis serveur, verdict final. */
        fun sha256SumsText(): String = buildString {
            appendLine("# bocal $prefix — aller-retour Nextcloud (cahier §10 M0)")
            appendLine("# SOURCE (${items.size} fichiers)")
            for (item in items.sortedBy { it.path }) {
                appendLine("${item.sha256}  ${item.path}")
            }
            appendLine("# SERVEUR (retéléchargé WebDAV)")
            for (item in items.sortedBy { it.path }) {
                appendLine("${serverSha[item.path] ?: "MISSING"}  ${item.path}")
            }
            appendLine("# VERDICT : ${items.size - mismatches.size}/${items.size} identiques")
        }
    }

    /** Remplit [prefix] puis retélécharge et compare. Le préfixe est choisi par l'appelant. */
    fun run(prefix: String): Report {
        val directories = mutableListOf("")
        val items = mutableListOf<Item>()
        val unreadable = mutableListOf<Pair<String, String>>()
        walk(source, "", directories, items, unreadable)

        for (dir in directories) {
            check(webdav.ensureCollection(joinPath(prefix, dir))) {
                "MKCOL impossible sur ${joinPath(prefix, dir)}"
            }
        }

        val uploaded = mutableListOf<String>()
        val alreadyThere = mutableListOf<String>()
        val failedUpload = mutableListOf<Pair<String, String>>()
        for (item in items) {
            when (val result = webdav.putNoOverwrite(joinPath(prefix, item.path), item.bytes)) {
                is WebDavClient.PutResult.Created -> uploaded += item.path
                is WebDavClient.PutResult.AlreadyExists -> alreadyThere += item.path
                is WebDavClient.PutResult.Failed ->
                    failedUpload += item.path to "http ${result.httpStatus}"
            }
        }

        val serverSha = items.mapNotNull { item ->
            webdav.get(joinPath(prefix, item.path))?.let { item.path to sha256Hex(it) }
        }.toMap()

        return Report(
            prefix = prefix,
            items = items,
            uploaded = uploaded,
            alreadyThere = alreadyThere,
            failedUpload = failedUpload,
            unreadable = unreadable,
            serverSha = serverSha,
        )
    }

    private fun walk(
        source: DocumentSource,
        dir: String,
        directories: MutableList<String>,
        items: MutableList<Item>,
        unreadable: MutableList<Pair<String, String>>,
    ) {
        for (child in source.listChildren(dir).sortedBy { it.name }) {
            val path = joinPath(dir, child.name)
            if (child.isDirectory) {
                directories += path
                walk(source, path, directories, items, unreadable)
                continue
            }
            try {
                val bytes = source.open(path).use { it.readBytes() }
                items += Item(path, bytes, sha256Hex(bytes))
            } catch (exception: Exception) {
                unreadable += path to (exception.message ?: exception.javaClass.simpleName)
            }
        }
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
