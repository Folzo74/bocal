package fr.folzocorp.bocal

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibelleDossierTest {

    @Test
    fun formeVolumeSimpleDonneLeCheminApresLesDeuxPoints() {
        val uri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fmedia",
        )
        assertEquals("Android/media", libelleDossierDepuisUri(uri))
    }

    @Test
    fun formeDocumentDonneLeDocIdEtJamaisLeVolumeRacineVide() {
        // Forme rendue par le picker prépositionné (preset §4.1) : volume réduit à
        // « primary: » + docId porteur du nom — l'ancien code affichait « » ici.
        val uri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3A/document/" +
                "primary%3AAndroid%2Fmedia%2Fcom.whatsapp",
        )
        assertEquals("Android/media/com.whatsapp", libelleDossierDepuisUri(uri))
    }

    @Test
    fun volumeRacineSeulNeDonneJamaisUnLibelleVide() {
        val uri = Uri.parse(
            "content://com.android.externalstorage.documents/tree/primary%3A",
        )
        assertTrue("libellé vide : « ${libelleDossierDepuisUri(uri)} »", libelleDossierDepuisUri(uri).isNotBlank())
    }

    @Test
    fun uriInattenduRetombeSurLAuriDecodee() {
        val uri = Uri.parse("content://other.provider/no-tree")
        assertTrue(libelleDossierDepuisUri(uri).isNotBlank())
    }
}
