package fr.folzocorp.bocal

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import fr.folzocorp.bocal.data.ConnectionManager
import fr.folzocorp.bocal.data.NextcloudClient
import fr.folzocorp.bocal.data.StoredConnection
import fr.folzocorp.bocal.data.TreeStore
import fr.folzocorp.bocal.data.WhatsAppPreset
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    /**
     * Le travail en cours vit hors de l'activité (rotation = nouvelle activité,
     * même processus). Un seul résultat peut être en vol (boutons désactivés
     * pendant le travail) : un unique slot [resultatEnAttente] suffit — pris
     * (« taken ») exactement une fois, il est livré à l'activité AU FRONT à
     * cet instant ([ecouteur]), sinon gardé pour son prochain onStart.
     */
    private companion object {
        val executor = Executors.newSingleThreadExecutor()
        val pupitre = Handler(Looper.getMainLooper())
        val verrou = Any()

        /** Écran au front, en référence faible : jamais de rétention d'activité détruite. */
        var ecouteur: WeakReference<MainActivity>? = null
        var enTravail = false
        var resultatEnAttente: Resultat? = null

        data class Resultat(
            val succes: StoredConnection?,
            val echec: NextcloudClient.LoginResult.Failure.Kind?,
            val deconnexion: Boolean,
        )

        fun registrerEcran(ecran: MainActivity) {
            synchronized(verrou) { ecouteur = WeakReference(ecran) }
        }

        fun deregistrerEcran(ecran: MainActivity) {
            synchronized(verrou) {
                if (ecouteur?.get() === ecran) ecouteur = null
            }
        }

        /** Sur le fil principal : vider le slot vers l'écran actuel, s'il y en a un. */
        fun livrerAuFilActuel() {
            val cible = synchronized(verrou) {
                val resultat = resultatEnAttente ?: return
                val ecran = ecouteur?.get() ?: return
                resultatEnAttente = null
                Pair(ecran, resultat)
            }
            cible.first.appliquerResultat(cible.second)
        }
    }

    private lateinit var manager: ConnectionManager
    private lateinit var treeStore: TreeStore

    /** Picker SAF prépositionné sur Android/media/com.whatsapp (cahier §4.1). */
    private val lanceurArbre =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            dossierErreur = if (uri == null) {
                false
            } else {
                runCatching { treeStore.save(uri) }.isFailure
            }
            afficherEtatDossier()
        }

    private var dossierErreur = false

    private lateinit var champUrl: EditText
    private lateinit var champUtilisateur: EditText
    private lateinit var champMotDePasse: EditText
    private lateinit var boutonConnexion: Button
    private lateinit var boutonDeconnexion: Button
    private lateinit var etatConnexion: TextView
    private lateinit var boutonDossier: Button
    private lateinit var boutonOublierDossier: Button
    private lateinit var etatDossier: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        manager = ConnectionManager(this)
        treeStore = TreeStore(this)

        boutonDossier = findViewById(R.id.bouton_dossier)
        boutonOublierDossier = findViewById(R.id.bouton_oublier_dossier)
        etatDossier = findViewById(R.id.etat_dossier)
        boutonDossier.setOnClickListener { lanceurArbre.launch(WhatsAppPreset.initialTreeHint()) }
        boutonOublierDossier.setOnClickListener { demanderOubliDuDossier() }

        champUrl = findViewById(R.id.champ_url)
        champUtilisateur = findViewById(R.id.champ_utilisateur)
        champMotDePasse = findViewById(R.id.champ_mot_de_passe)
        boutonConnexion = findViewById(R.id.bouton_connexion)
        boutonDeconnexion = findViewById(R.id.bouton_deconnexion)
        etatConnexion = findViewById(R.id.etat_connexion)

        boutonConnexion.setOnClickListener { lancerConnexion() }
        boutonDeconnexion.setOnClickListener { demanderDeconnexion() }
        champMotDePasse.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                lancerConnexion()
                true
            } else {
                false
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registrerEcran(this)
        afficherEtatDossier()
        if (!consommerResultatEnAttente()) {
            afficherEtatEnregistre()
        }
        if (synchronized(verrou) { enTravail }) {
            rendreFormulaireConnexion(false)
            etatConnexion.text = getString(R.string.connexion_en_cours)
        }
    }

    override fun onStop() {
        deregistrerEcran(this)
        super.onStop()
    }

    override fun onDestroy() {
        deregistrerEcran(this)
        super.onDestroy()
    }

    /** True si un résultat terminé attendait (rotation, ou écran absent à la fin du travail). */
    private fun consommerResultatEnAttente(): Boolean {
        val resultat = synchronized(verrou) {
            val r = resultatEnAttente ?: return false
            resultatEnAttente = null
            r
        }
        appliquerResultat(resultat)
        return true
    }

    /** Le fil de travail termine : slot + fin, puis livraison sur le fil principal. */
    private fun travailTermine(
        succes: StoredConnection?,
        echec: NextcloudClient.LoginResult.Failure.Kind?,
        deconnexion: Boolean,
    ) {
        synchronized(verrou) {
            enTravail = false
            resultatEnAttente = Resultat(succes, echec, deconnexion)
        }
        pupitre.post { livrerAuFilActuel() }
    }

    private fun afficherEtatEnregistre() {
        val connection = runCatching { manager.currentConnection() }.getOrNull()
        when {
            connection != null -> {
                etatConnexion.text =
                    getString(R.string.connexion_etat_connecte, connection.loginName, connection.baseUrl)
                rendreFormulaireConnexion(false)
                boutonDeconnexion.isEnabled = true
            }

            manager.lecturePrecedenteEtaitIllisible() -> {
                etatConnexion.text = getString(R.string.connexion_etat_illisible)
                rendreFormulaireConnexion(true)
                boutonDeconnexion.isEnabled = false
            }

            else -> {
                etatConnexion.text = getString(R.string.connexion_etat_deconnecte)
                rendreFormulaireConnexion(true)
                boutonDeconnexion.isEnabled = false
            }
        }
    }

    private fun lancerConnexion() {
        val url = champUrl.text.toString()
        val utilisateur = champUtilisateur.text.toString().trim()
        val motDePasse = CharArray(champMotDePasse.text.length)
        champMotDePasse.text.getChars(0, champMotDePasse.text.length, motDePasse, 0)
        if (url.isBlank() || utilisateur.isBlank() || motDePasse.isEmpty()) {
            etatConnexion.text = getString(R.string.connexion_erreur_champs)
            motDePasse.fill('\u0000')
            return
        }
        rendreFormulaireConnexion(false)
        etatConnexion.text = getString(R.string.connexion_en_cours)
        synchronized(verrou) { enTravail = true }
        executor.execute {
            // Un imprévu (Keystore HS, etc.) ne doit jamais laisser le formulaire
            // verrouillé pour tout le processus : il devient un échec ordinaire.
            val result = runCatching { manager.connect(url, utilisateur, motDePasse) }
                .getOrElse {
                    NextcloudClient.LoginResult.Failure(NextcloudClient.LoginResult.Failure.Kind.SERVER)
                }
            when (result) {
                is NextcloudClient.LoginResult.Success -> travailTermine(result.connection, null, false)
                is NextcloudClient.LoginResult.Failure -> travailTermine(null, result.kind, false)
            }
        }
    }

    /** Action destructives (révocation serveur + purge Keystore) : confirmation d'abord. */
    private fun demanderDeconnexion() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.deconnexion_confirmation_titre)
            .setMessage(R.string.deconnexion_confirmation_message)
            .setNegativeButton(R.string.action_annuler, null)
            .setPositiveButton(R.string.action_confirmer) { _, _ -> lancerDeconnexion() }
            .show()
    }

    private fun lancerDeconnexion() {
        rendreFormulaireConnexion(false)
        boutonDeconnexion.isEnabled = false
        etatConnexion.text = getString(R.string.deconnexion_en_cours)
        synchronized(verrou) { enTravail = true }
        executor.execute {
            // Même règle que la connexion : jamais de verrouillage UI permanent.
            val echec = runCatching { manager.disconnect() }.isFailure
            travailTermine(null, if (echec) NextcloudClient.LoginResult.Failure.Kind.SERVER else null,
                deconnexion = !echec)
        }
    }

    private fun appliquerResultat(resultat: Resultat) {
        when {
            resultat.deconnexion || (resultat.succes == null && resultat.echec == null) -> {
                etatConnexion.text = getString(R.string.connexion_etat_deconnecte)
                rendreFormulaireConnexion(true)
                boutonDeconnexion.isEnabled = false
            }

            resultat.succes != null -> {
                etatConnexion.text = getString(
                    R.string.connexion_etat_connecte,
                    resultat.succes.loginName,
                    resultat.succes.baseUrl,
                )
                rendreFormulaireConnexion(false)
                boutonDeconnexion.isEnabled = true
                champMotDePasse.setText("")
            }

            else -> {
                etatConnexion.text = when (resultat.echec) {
                    NextcloudClient.LoginResult.Failure.Kind.BAD_URL -> getString(R.string.connexion_erreur_url)
                    NextcloudClient.LoginResult.Failure.Kind.NOT_NEXTCLOUD -> getString(R.string.connexion_erreur_pas_nextcloud)
                    NextcloudClient.LoginResult.Failure.Kind.AUTH_REJECTED -> getString(R.string.connexion_erreur_auth)
                    NextcloudClient.LoginResult.Failure.Kind.NETWORK -> getString(R.string.connexion_erreur_reseau)
                    NextcloudClient.LoginResult.Failure.Kind.SERVER -> getString(R.string.connexion_erreur_serveur)
                    null -> getString(R.string.connexion_erreur_reseau)
                }
                rendreFormulaireConnexion(true)
            }
        }
    }

    private fun demanderOubliDuDossier() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dossier_oublier_confirmation_titre)
            .setMessage(R.string.dossier_oublier_confirmation_message)
            .setNegativeButton(R.string.action_annuler, null)
            .setPositiveButton(R.string.action_confirmer) { _, _ ->
                treeStore.forget()
                dossierErreur = false
                afficherEtatDossier()
            }
            .show()
    }

    private fun afficherEtatDossier() {
        val stored = runCatching { treeStore.load() }.getOrNull()
        etatDossier.text = when {
            dossierErreur -> getString(R.string.dossier_erreur)
            stored != null -> getString(R.string.dossier_etat_enregistre, libelleDossier(stored))
            treeStore.lectureEtaitIllisible -> getString(R.string.dossier_etat_illisible)
            treeStore.isConfigured() -> getString(R.string.dossier_etat_perdu)
            else -> getString(R.string.dossier_etat_aucun)
        }
        boutonOublierDossier.isEnabled = treeStore.isConfigured()
        dossierErreur = false
    }

    /** Joli nom pour l'humain : la fin du chemin choisi, pas l'URI technique. */
    private fun libelleDossier(uri: Uri): String =
        Uri.decode(uri.pathSegments.getOrNull(uri.pathSegments.indexOf("tree") + 1) ?: uri.toString())
            .substringAfterLast(':')

    private fun rendreFormulaireConnexion(actif: Boolean) {
        champUrl.isEnabled = actif
        champUtilisateur.isEnabled = actif
        champMotDePasse.isEnabled = actif
        boutonConnexion.isEnabled = actif
    }
}
