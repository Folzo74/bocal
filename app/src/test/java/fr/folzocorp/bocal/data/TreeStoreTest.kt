package fr.folzocorp.bocal.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TreeStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val resolver = context.contentResolver

    private val presetUri: Uri = Uri.parse(
        "content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fmedia",
    )
    private val autreUri: Uri = Uri.parse(
        "content://com.android.externalstorage.documents/tree/primary%3ADownload",
    )

    private fun nouveauStore(): TreeStore = TreeStore(context)

    @Test
    fun lEnregistrementPrendLaPermissionPersistanteDeLecture() {
        nouveauStore().save(presetUri)
        val persisted = resolver.persistedUriPermissions
        assertTrue("la capacité doit survivre aux redémarrages", persisted.any { it.uri == presetUri && it.isReadPermission })
        assertFalse("jamais d'écriture dans ce preset M0", persisted.any { it.uri == presetUri && it.isWritePermission })
    }

    @Test
    fun leTreeUriSurvitAURedemarrage() {
        nouveauStore().save(presetUri)
        // Nouveau store = nouveau processus (le fichier privé est la mémoire, la
        // permission persistée est la capacité réelle).
        val relu = nouveauStore().load()
        assertEquals(presetUri, relu)
        assertTrue(nouveauStore().isConfigured())
    }

    @Test
    fun lOubliRelacheLaPermissionEtPurgeLeFichier() {
        val store = nouveauStore()
        store.save(presetUri)
        store.forget()
        assertFalse(resolver.persistedUriPermissions.any { it.uri == presetUri })
        assertFalse(nouveauStore().isConfigured())
        assertNull(nouveauStore().load())
    }

    @Test
    fun lOubliRestePropreQuandLaPermissionAVaitEteRevoquee() {
        val store = nouveauStore()
        store.save(presetUri)
        resolver.releasePersistableUriPermission(presetUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        store.forget() // ne doit rien jeter
        assertFalse(nouveauStore().isConfigured())
    }

    @Test
    fun permissionRevoqueeEstSignaleeNullSansCrierCorruption() {
        nouveauStore().save(presetUri)
        resolver.releasePersistableUriPermission(presetUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val store = nouveauStore()
        assertNull(store.load())
        assertFalse("ce n'est pas une corruption, l'UI dira « rechoisir »", store.lectureEtaitIllisible)
        assertTrue("le fichier est intact, la capacité a disparu", store.isConfigured())
    }

    @Test
    fun fichierCorrompuEstRejeteEtPurge() {
        nouveauStore().save(presetUri)
        fichier().writeText("ceci n'est pas un arbre SAF")
        val store = nouveauStore()
        assertNull(store.load())
        assertTrue(store.lectureEtaitIllisible)
        assertFalse("purge après corruption", nouveauStore().isConfigured())
    }

    @Test
    fun uriAbsolumentPasUnArbreEstRejete() {
        nouveauStore().save(presetUri)
        fichier().writeText("BOCAL-TREE-1\nfile:///home/rien\n")
        val store = nouveauStore()
        assertNull(store.load())
        assertTrue(store.lectureEtaitIllisible)
        assertFalse(nouveauStore().isConfigured())
    }

    @Test
    fun absenceDeFichierNEstPasUneErreur() {
        val store = nouveauStore()
        assertNull(store.load())
        assertFalse(store.lectureEtaitIllisible)
        assertFalse(store.isConfigured())
    }

    @Test
    fun sauvegardeRemplaceLArbrePrecedent() {
        nouveauStore().save(presetUri)
        nouveauStore().save(autreUri)
        assertEquals(autreUri, nouveauStore().load())
        assertTrue(nouveauStore().hasPersistedReadPermission(autreUri))
    }

    @Test
    fun leRappelDuFichierNeContientRIENdeSecret() {
        nouveauStore().save(presetUri)
        val brut = fichier().readText()
        assertTrue("l'URI de l'arbre est une capacité privée applicative, pas un secret utilisateur", brut.contains("tree"))
        assertTrue(isLikelyWhatsappTree(Uri.parse(brut.lines()[1])))
    }

    @Test
    fun lePresetCorrespondAuCheminAndroidMediaComWhatsapp() {
        val hint = WhatsAppPreset.initialTreeHint()
        assertEquals("com.android.externalstorage.documents", hint.authority)
        assertTrue(isLikelyWhatsappTree(hint))
        val direct = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fmedia%2Fcom.whatsapp",
        )
        assertTrue(isLikelyWhatsappTree(direct))
        assertFalse(isLikelyWhatsappTree(autreUri))
        assertFalse(isLikelyWhatsappTree(Uri.parse("content://media/external/images/media/42")))
    }

    private fun fichier(): java.io.File =
        java.io.File(context.getDir("bocal", Context.MODE_PRIVATE), "arbre.txt")
}
