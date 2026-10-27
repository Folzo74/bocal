# Bocal

**FR** · [English](README.en.md)

Sauvegarde **WhatsApp self-hosted** pour toute la famille : chaque nuit (ou à la demande),
Bocal remplit un « bocal » — un dossier daté sur le Nextcloud familial — contenant la
sauvegarde complète de WhatsApp de l'appareil (messages + médias, `.crypt` et `.keys`
inclus). Un enfant doit pouvoir restaurer seul ses messages sur un téléphone remis à zéro.

Projet Folzo Home. Licence **GPL-3.0**. Dépôt : `fr.folzocorp.bocal`.

## Périmètre (v1)

- **3 écrans seulement** (métaphore unique) : le robinet (état, voyant vert/rouge),
  l'étagère (bocaux datés, restauration), l'assistant (setup et restauration guidées).
- Accès aux fichiers via **Storage Access Framework** uniquement (preset
  `Android/media/com.whatsapp`), **sans root** et sans permission large
  (`MANAGE_EXTERNAL_STORAGE` banni).
- Côté serveur : **Nextcloud / WebDAV**, stratégie « bocaux datés » — un nouveau dossier
  `WhatsApp/<appareil>/AAAA-MM-JJ/` par sauvegarde, **jamais d'écrasement**, rétention
  réglable dans l'app.
- **Zéro Google Play Services**, zéro pub, zéro tracker, zéro télémétrie, zéro compte Bocal.
  Application 100 % Kotlin + Material 3, compatible F-Droid.
- Hors périmètre v1 : E2EE dans l'app, Signal, tableau de bord famille (prévus plus tard).

## Build

Prérequis (postes de l'atelier) :

- **JDK 21** dans `~/tools/jdk21` (issu de M0-A). Le launcher Gradle lit
  `org.gradle.java.home` **sans substitution** (ni `~` ni `${user.home}`) : il se déclare en
  chemin absolu dans **`~/.gradle/gradle.properties`** (hors git) :
  `org.gradle.java.home=/home/<user>/tools/jdk21`. Le dépôt reste ainsi portable.
- **Android SDK** avec platform `android-35` et build-tools `35.0.0` ; le chemin du SDK se
  déclare dans `local.properties` (`sdk.dir=...`), fichier hors git.

```sh
./gradlew assembleDebug   # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew lint             # 0 erreur exigée
```

Vérifier que le bon JDK est utilisé : `./gradlew -V` (ligne `JVM` = Temurin 21.x).

## CI

GitHub Actions (`.github/workflows/ci.yml`) : build `assembleDebug`, lint (0 erreur),
tests unitaires (dont Robolectric), **intégration Nextcloud** (service docker
`nextcloud:stable-apache` + utilisateur de démo : handshake login v2 → token d'application
→ Bearer 200 → révocation → 401), **aller-retour bocal** (fixture §4.2 →
`WhatsApp/<appareil>/AAAA-MM-JJ/` → re-téléchargement, sha256 comparés, preuve
`sha256SUMS` en artifact), preuves grep anti-stockage-clair
(`tools/audit-clear-text.sh`) et zéro-permission-large/SAF-only
(`tools/audit-saf-permissions.sh`), et ébauche de build reproductible (deux builds avec
`SOURCE_DATE_EPOCH` figé, comparaison des sha256 des APK rendue dans le log).

## Sécurité des identifiants (M0-D / critère C1)

Le mot de passe Nextcloud est utilisé **une seule fois**, pour créer un token
d'application (`GET /ocs/v2.php/core/getapppassword` en Basic, après handshake
de l'endpoint login v2). Ensuite, seuls le token (Bearer), l'URL et le nom
d'utilisateur sont conservés — chiffrés par une clé **Android Keystore**
AES-256-GCM non exportable (mêmes garanties que la lib de prefs chiffrées AndroidX,
mais en APIs platform : la lib `security-crypto` est dépréciée côté Google). Le mot
de passe n'est écrit nulle part, jamais loggé, et son tampon mémoire est effacé dès
l'échange terminé. La déconnexion révoque le token côté serveur puis détruit fichier
et clé Keystore.

## État d'avancement

M0-B : squelette (module unique `:app`, Material 3). M0-D : **connexion Nextcloud
(critère C1)** — écran de connexion (formulaire + bouton + voyant d'état), client
d'authentification et stockage Keystore testés (Robolectric + intégration CI).
M0-E : **lecture SAF de l'arborescence WhatsApp** — picker prépositionné sur
`Android/media/com.whatsapp`, arbre mémorisé par permission persistante (aucune
permission large, preuve grep `tools/audit-saf-permissions.sh`), inventaire
(chemin, taille, mtime, sha256) énumérant 100 % de l'arbre y compris les cachés
(`Media/.keys`, `.wa`), tout fichier non lu étant consigné au rapport (zéro
silence) — 30 tests unitaires sur fixture §4.2.
M0-F : **critère de sortie M0 atteint** — l'arbre fixture §4.2 est monté dans
`<home>/WhatsApp/<appareil>/AAAA-MM-JJ/` du Nextcloud de la CI via WebDAV
(MKCOL + PUT `If-None-Match` : jamais d'écrasement, garde-fou confiné au
préfixe `WhatsApp/`), retéléchargé, et les sha256 des 17 fichiers sont
identiques — preuve `sha256SUMS` du aller-retour archivée en artifact CI et
verdict rendu dans le log. L'upload chunked v2 et la rétention arrivent en
M1, avec l'étagère.
M0-G : **publication** — dépôt public GitHub, README FR/EN, tableau C1–C10 ci-dessous.
Le cahier du projet est `DOSSIER-BOCAL.md` (Infrastructure_Folzo_Home).

## Avancement des critères (C1–C10)

Référentiel : §11 du cahier `DOSSIER-BOCAL.md`.

| Critère | Énoncé (résumé) | État | Preuve / où |
|---|---|---|---|
| **C1** Connexion | URL + compte Nextcloud → login réussi ; token chiffré Keystore, aucun clair ; logout propre | ✅ OK (M0-D) | client login v2 + Robolectric ; job CI « Intégration Nextcloud » (Bearer 200 → révocation → 401) ; preuve grep `tools/audit-clear-text.sh` |
| **C2** Premier bocal complet | 100 % de l'arbre source, `.keys`/`.crypt` inclus, prouvé par comptage + sha256 | 🟡 partiel | aller-retour CI sur fixture §4.2 (17 fichiers, sha256 identiques, artifact `sha256SUMS`, M0-F) ; reste : téléphone réel (M1) |
| **C3** Automatique | bocaux quotidiens sans toucher au téléphone | ⬜ M1/M2 | planification (WorkManager) à venir |
| **C4** Jamais d'écrasement | deux bocaux/jour = deux préfixes ; suppressions confinées à `WhatsApp/` | 🟡 amorcé | garde-fou `If-None-Match` + préfixe `WhatsApp/` dans `WebDavClient` (M0-F) ; suffixe `.n` + test rétention : M1 |
| **C5** Restauration | assistant bloquant si WhatsApp actif, copie retour complète | ⬜ M2 | assistant (écran 3) à venir |
| **C6** Robustesse | reprise après coupure, 0 doublon, 0 incomplet marqué complet | ⬜ M1 | upload chunked v2 à venir |
| **C7** UI enfant | 3 écrans max, usage sans explication | 🟡 amorcé | écran « robinet » en place ; étagère + assistant : M1–M2 |
| **C8** Go/No-Go | un enfant restaure seul sur téléphone remis à zéro | ⬜ M3 | pilote réel (décide que le produit existe) |
| **C9** Publication | builds reproductibles prouvés + F-Droid accepté | 🟡 amorcé | ébauche reproductibilité en CI (double build `SOURCE_DATE_EPOCH`) ; soumission F-Droid : plus tard |
| **C10** Docs | README FR/EN + page enfant + page parent | 🟡 en cours | README FR + EN dans ce dépôt (M0-G) ; pages enfant/parent : M2 |


## Licence

GPL-3.0 — voir [LICENSE](LICENSE).
