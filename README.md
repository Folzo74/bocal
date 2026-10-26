# Bocal

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
tests unitaires, et ébauche de build reproductible (deux builds avec `SOURCE_DATE_EPOCH`
figé, comparaison des sha256 des APK rendue dans le log).

## État d'avancement

Squelette M0-B : module unique `:app`, Material 3, écran placeholder. Les jalons suivants
(M0/M1) ajoutent la connexion Nextcloud, le preset SAF WhatsApp, les bocaux et l'étagère.
Le cahier du projet est `DOSSIER-BOCAL.md` (Infrastructure_Folzo_Home).

## Licence

GPL-3.0 — voir [LICENSE](LICENSE).
