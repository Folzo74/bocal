package fr.folzocorp.bocal.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Connexion enregistrée sur l'appareil. `appToken` est LE secret : un token
 * d'application Nextcloud, créé une fois lors de la connexion (cahier §7.2).
 * Le mot de passe de l'utilisateur n'existe nulle part ici : il n'est jamais
 * passé à cette classe ni à aucun stockage.
 */
data class StoredConnection(
    val baseUrl: String,
    val loginName: String,
    val appToken: String,
)

/**
 * Interface avec l'Android Keystore. Séparée du stockage pour que la logique
 * (format de fichier, AAD, nonces) soit testable sur JVM/Robolectric avec une
 * implémentation de substitution, pendant que la production n'utilise QUE
 * l'AndroidKeyStore matériel/platform.
 */
interface KeystoreBackend {
    fun getOrCreateKey(alias: String)
    fun keyExists(alias: String): Boolean
    fun deleteKey(alias: String)
    fun encrypt(alias: String, plaintext: ByteArray, aad: ByteArray): ByteArrayPair
    fun decrypt(alias: String, ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray

    data class ByteArrayPair(val iv: ByteArray, val ciphertext: ByteArray)
}

/** Backend de production : clés AES-256-GCM non exportables dans l'AndroidKeyStore. */
object AndroidKeystoreBackend : KeystoreBackend {

    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun getOrCreateKey(alias: String) {
        val store = keyStore()
        if (store.containsAlias(alias)) return
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(spec)
        store.setEntry(alias, KeyStore.SecretKeyEntry(generator.generateKey()), null)
    }

    override fun keyExists(alias: String): Boolean = keyStore().containsAlias(alias)

    override fun deleteKey(alias: String) {
        val store = keyStore()
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    override fun encrypt(alias: String, plaintext: ByteArray, aad: ByteArray): KeystoreBackend.ByteArrayPair {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, androidKey(alias))
        cipher.updateAAD(aad)
        return KeystoreBackend.ByteArrayPair(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(alias: String, ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, androidKey(alias), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun androidKey(alias: String): SecretKey {
        val store = keyStore()
        val entry = store.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            ?: throw IllegalStateException("clé Keystore absente : $alias")
        return entry.secretKey
    }
}

/**
 * Stockage du token d'application, chiffré par une clé Android Keystore
 * (mêmes garanties que la librairie de prefs chiffrées AndroidX, en APIs
 * platform uniquement — la lib security-crypto étant dépréciée, cahier §7.2).
 *
 * Format du fichier (bocal/nextcloud.bin, mode privé applicatif) :
 *   [MAGIC 8 o][entrée]* avec entrée = [nom 1 o][IV 12 o][chiffré 4 o BE][chiffré]
 * Chaque entrée est chiffrée AES-256-GCM avec un IV aléatoire par écriture et
 * l'AAD = nom du champ (une entrée volée et déplacée vers un autre champ est
 * rejetée). Les valeurs ne sont JAMAIS écrites en clair.
 */
class TokenStore(
    context: Context,
    private val backend: KeystoreBackend = AndroidKeystoreBackend,
    private val file: File = File(context.getDir(NAME, Context.MODE_PRIVATE), FILE_NAME),
) {

    companion object {
        const val KEY_ALIAS = "fr.folzocorp.bocal.connection"
        private const val NAME = "bocal"
        private const val FILE_NAME = "nextcloud.bin"
        private val MAGIC = "BOCALv1\u0000".toByteArray(Charsets.US_ASCII)
        const val FIELD_BASE_URL = "baseUrl"
        const val FIELD_LOGIN = "login"
        const val FIELD_TOKEN = "token"
    }

    fun save(connection: StoredConnection) {
        backend.getOrCreateKey(KEY_ALIAS)
        val entries = linkedMapOf(
            FIELD_BASE_URL to connection.baseUrl.toByteArray(Charsets.UTF_8),
            FIELD_LOGIN to connection.loginName.toByteArray(Charsets.UTF_8),
            FIELD_TOKEN to connection.appToken.toByteArray(Charsets.UTF_8),
        )
        val buffer = java.io.ByteArrayOutputStream()
        buffer.write(MAGIC)
        for ((name, value) in entries) {
            val sealed = backend.encrypt(KEY_ALIAS, value, name.toByteArray(Charsets.UTF_8))
            buffer.write(name.toByteArray(Charsets.UTF_8).size.coerceAtMost(255))
            buffer.write(name.toByteArray(Charsets.UTF_8))
            writeFixed(buffer, sealed.iv)
            buffer.write(intToBytes(sealed.ciphertext.size))
            buffer.write(sealed.ciphertext)
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.outputStream().use { it.write(buffer.toByteArray()) }
        if (!temp.renameTo(file)) {
            temp.delete()
            file.delete()
            temp.renameTo(file)
        }
    }

    fun load(): StoredConnection? {
        if (!file.exists()) {
            lectureEtaitIllisible = false
            return null
        }
        val bytes = try {
            file.readBytes()
        } catch (exception: java.io.IOException) {
            // Lecture transitoirement impossible (panne disque, fichier ouvert
            // ailleurs) : on ne détruit RIEN et on ne sonne pas l'alerte.
            return null
        }
        return try {
            val connection = dechiffrer(bytes)
            lectureEtaitIllisible = false
            connection
        } catch (exception: Exception) {
            // Contenu vraiment corrompu ou clé Keystore perdue : purge propre,
            // et l'UI pourra expliquer qu'une reconnexion est nécessaire.
            wipe()
            lectureEtaitIllisible = true
            null
        }
    }

    /** Vrai quand la dernière lecture a trouvé une connexion irrécupérable (purge effectuée). */
    var lectureEtaitIllisible = false
        private set

    private fun dechiffrer(bytes: ByteArray): StoredConnection? {
        require(bytes.size > MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "format invalide"
        }
        var offset = MAGIC.size
        val values = mutableMapOf<String, String>()
        while (offset < bytes.size) {
            val nameLength = bytes[offset].toInt() and 0xFF
            offset++
            val name = bytes.decodeToString(offset, offset + nameLength)
            offset += nameLength
            val iv = bytes.copyOfRange(offset, offset + 12)
            offset += 12
            val length = bytesToInt(bytes.copyOfRange(offset, offset + 4))
            offset += 4
            val ciphertext = bytes.copyOfRange(offset, offset + length)
            offset += length
            val plaintext = backend.decrypt(KEY_ALIAS, ciphertext, iv, name.toByteArray(Charsets.UTF_8))
            values[name] = plaintext.decodeToString()
        }
        return StoredConnection(
            baseUrl = values[FIELD_BASE_URL] ?: return null,
            loginName = values[FIELD_LOGIN] ?: return null,
            appToken = values[FIELD_TOKEN] ?: return null,
        )
    }

    fun isConfigured(): Boolean = file.exists()

    /** Déconnexion propre : fichier purgé ET clé Keystore détruite. */
    fun wipe() {
        file.delete()
        File(file.parentFile, "$FILE_NAME.tmp").delete()
        backend.deleteKey(KEY_ALIAS)
    }
}

private fun intToBytes(value: Int): ByteArray =
    byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

private fun bytesToInt(bytes: ByteArray): Int =
    ((bytes[0].toInt() and 0xFF) shl 24) or
        ((bytes[1].toInt() and 0xFF) shl 16) or
        ((bytes[2].toInt() and 0xFF) shl 8) or
        (bytes[3].toInt() and 0xFF)

private fun writeFixed(buffer: java.io.ByteArrayOutputStream, iv: ByteArray) {
    require(iv.size == 12) { "IV GCM attendu : 12 octets" }
    buffer.write(iv)
}
