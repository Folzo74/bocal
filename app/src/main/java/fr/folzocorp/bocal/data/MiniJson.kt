package fr.folzocorp.bocal.data

/**
 * Lecteur JSON minimaliste (zéro dépendance, exigence F-Droid).
 * Recherche en profondeur de la valeur d'une clé (string ou scalaire), où
 * qu'elle se trouve dans l'objet — suffisant pour les réponses OCS/login de
 * Nextcloud. Les chaînes sont toujours sautées par pans entiers : le contenu
 * d'une valeur ne peut jamais imiter une clé.
 */
object MiniJson {

    fun stringField(json: String, key: String): String? {
        val range = locate(json, key) ?: return null
        return unescape(json.substring(range.first, range.last + 1))
    }

    fun intField(json: String, key: String): Int? {
        val range = locate(json, key) ?: return null
        return json.substring(range.first, range.last + 1).trim().toIntOrNull()
    }

    private fun locate(json: String, key: String): IntRange? {
        var i = 0
        while (i < json.length) {
            if (json[i] != '"') {
                i++
                continue
            }
            val stringEnd = skipString(json, i)
            if (stringEnd < 0) return null
            val candidate = json.substring(i + 1, stringEnd)
            val colon = skipSpaces(json, stringEnd + 1)
            if (colon < 0 || json[colon] != ':') {
                // Cette chaîne était une valeur (ou du bruit) : on la saute.
                i = stringEnd + 1
                continue
            }
            val valueStart = skipSpaces(json, colon + 1)
            if (valueStart < 0) return null
            if (candidate == key) {
                return when (json[valueStart]) {
                    '"' -> {
                        val valueEnd = skipString(json, valueStart)
                        if (valueEnd < 0) return null
                        (valueStart + 1) until valueEnd
                    }

                    '{', '[' -> {
                        // Valeur composée : plonger dedans pour y trouver la clé.
                        locate(json.substring(valueStart), key)?.let { (valueStart + it.first)..(valueStart + it.last) }
                            ?: return null
                    }

                    else -> valueStart until skipScalar(json, valueStart)
                }
            }
            // Clé différente : sauter la valeur si c'est une chaîne (son
            // contenu ne doit jamais être lu comme des clés), sinon plonger.
            i = if (json[valueStart] == '"') skipString(json, valueStart) + 1 else valueStart
        }
        return null
    }

    private fun skipString(text: String, start: Int): Int {
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return -1
    }

    private fun skipScalar(text: String, start: Int): Int {
        var i = start
        while (i < text.length && text[i] != ',' && text[i] != '}' && text[i] != ']') i++
        return i
    }

    private fun skipSpaces(text: String, from: Int): Int {
        if (from < 0) return -1
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return if (i < text.length) i else -1
    }

    private fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val builder = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i + 1 >= value.length) {
                builder.append(c)
                i++
                continue
            }
            when (val escaped = value[i + 1]) {
                '"' -> builder.append('"')
                '\\' -> builder.append('\\')
                '/' -> builder.append('/')
                'b' -> builder.append('\b')
                'f' -> builder.append('\u000C')
                'n' -> builder.append('\n')
                'r' -> builder.append('\r')
                't' -> builder.append('\t')
                'u' -> {
                    builder.append(value.substring(i + 2, minOf(i + 6, value.length)).toIntOrNull(16)?.toChar() ?: escaped)
                    i += 4
                }

                else -> builder.append(escaped)
            }
            i += 2
        }
        return builder.toString()
    }
}
