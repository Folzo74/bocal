package fr.folzocorp.bocal.data

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Substitution de l'AndroidKeyStore pour les tests JVM : mêmes algos
 * (AES-256-GCM, IV aléatoire par chiffrement, AAD obligatoire) mais clés en
 * mémoire. Le code testé (TokenStore, format de fichier) est exactement celui
 * de la production ; seul le porteur de clés change.
 */
class FakeKeystoreBackend : KeystoreBackend {

    private val keys = mutableMapOf<String, SecretKey>()

    override fun getOrCreateKey(alias: String) {
        if (!keys.containsKey(alias)) {
            keys[alias] = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    }

    override fun keyExists(alias: String): Boolean = keys.containsKey(alias)

    override fun deleteKey(alias: String) {
        keys.remove(alias)
    }

    override fun encrypt(alias: String, plaintext: ByteArray, aad: ByteArray): KeystoreBackend.ByteArrayPair {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(alias))
        cipher.updateAAD(aad)
        return KeystoreBackend.ByteArrayPair(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(alias: String, ciphertext: ByteArray, iv: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun key(alias: String): SecretKey =
        keys[alias] ?: throw IllegalStateException("clé absente : $alias")
}
