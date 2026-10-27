package fr.folzocorp.bocal.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TokenStoreTest {

    private val context: android.content.Context =
        androidx.test.core.app.ApplicationProvider.getApplicationContext()

    private fun nouveauStore(backend: KeystoreBackend = FakeKeystoreBackend()): Pair<TokenStore, KeystoreBackend> {
        val store = TokenStore(context, backend)
        return store to backend
    }

    @Test
    fun allerRetourEnregistreEtRelitLaConnexion() {
        val (store, _) = nouveauStore()
        val connection = StoredConnection("https://cloud.folzo.fr", "jean-henry", "abc12-TOKEN-987zy")
        store.save(connection)
        assertEquals(connection, store.load())
        assertTrue(store.isConfigured())
    }

    @Test
    fun leFichierNeContientJamaisLeTokenNiLeLoginEnClair() {
        val (store, _) = nouveauStore()
        store.save(StoredConnection("https://cloud.folzo.fr", "jean-henry", "abc12-TOKEN-987zy"))
        val brut = fichierDeStockage().readText(Charsets.ISO_8859_1)
        assertFalse("token en clair dans le fichier", brut.contains("abc12-TOKEN-987zy"))
        assertFalse("mot de passe en clair dans le fichier", brut.contains("motdepassesecret"))
        assertEquals("le fichier doit porter l'en-tête de format", 'B', brut[0])
    }

    @Test
    fun deconnexionPurgeLeFichierEtLaCleKeystore() {
        val backend = FakeKeystoreBackend()
        val (store, _) = nouveauStore(backend)
        store.save(StoredConnection("https://cloud.folzo.fr", "jean-henry", "abc12-TOKEN-987zy"))
        assertTrue(backend.keyExists(TokenStore.KEY_ALIAS))
        store.wipe()
        assertFalse(fichierDeStockage().exists())
        assertFalse(backend.keyExists(TokenStore.KEY_ALIAS))
        assertNull(store.load())
        assertFalse(store.isConfigured())
    }

    @Test
    fun fichierCorrompuEstRejeteEtPurgeEtSignalé() {
        val (store, backend) = nouveauStore()
        store.save(StoredConnection("https://cloud.folzo.fr", "jean-henry", "abc12-TOKEN-987zy"))
        fichierDeStockage().writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        assertNull(store.load())
        assertTrue("l'UI doit pouvoir expliquer la reconnexion requise", store.lectureEtaitIllisible)
        assertFalse(fichierDeStockage().exists())
        assertFalse(backend.keyExists(TokenStore.KEY_ALIAS))
    }

    @Test
    fun absenceDeFichierNEstPasSignaleeCommeIllisible() {
        val (store, _) = nouveauStore()
        assertNull(store.load())
        assertFalse(store.lectureEtaitIllisible)
    }

    @Test
    fun lAADLieChaqueEntreeASonChamp() {
        val backend = FakeKeystoreBackend()
        val alias = TokenStore.KEY_ALIAS
        backend.getOrCreateKey(alias)
        val valeur = "abc12-TOKEN-987zy".toByteArray()
        val scelle = backend.encrypt(alias, valeur, "token".toByteArray())
        assertArrayEquals(valeur, backend.decrypt(alias, scelle.ciphertext, scelle.iv, "token".toByteArray()))
        var rejete = false
        try {
            backend.decrypt(alias, scelle.ciphertext, scelle.iv, "login".toByteArray())
        } catch (exception: javax.crypto.AEADBadTagException) {
            rejete = true
        }
        assertTrue("déplacée vers un autre champ, l'entrée doit être rejetée", rejete)
    }

    @Test
    fun reecritureReutiliseLaMemeCleEtChangeIV() {
        val (store, _) = nouveauStore()
        store.save(StoredConnection("https://a.example", "u", "token-1"))
        val premier = fichierDeStockage().readBytes()
        store.save(StoredConnection("https://a.example", "u", "token-2"))
        val second = fichierDeStockage().readBytes()
        assertFalse(
            "deux écritures du même champ doivent produire un chiffré différent (IV frais)",
            premier.contentEquals(second),
        )
        assertEquals("token-2", store.load()?.appToken)
        assertArrayEquals("baseUrl".toByteArray(), premier.copyOfRange(9, 16))
    }

    private fun fichierDeStockage(): java.io.File =
        java.io.File(context.getDir("bocal", android.content.Context.MODE_PRIVATE), "nextcloud.bin")
}
