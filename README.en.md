# Bocal

[Français](README.md) · **EN**

**Self-hosted WhatsApp backup** for the whole family: every night (or on demand),
Bocal fills a « bocal » (jar) — a dated folder on the family Nextcloud — holding the
device's full WhatsApp backup (messages + media, `.crypt` and `.keys` files
included). A child must be able to restore their messages alone, on a freshly reset
phone.

Folzo Home project. **GPL-3.0** licence. Application ID: `fr.folzocorp.bocal`.

## Scope (v1)

- **3 screens only** (single metaphor): the tap (status, green/red light), the
  shelf (dated jars, restore), the assistant (guided setup and restore).
- File access through the **Storage Access Framework** only (preset
  `Android/media/com.whatsapp`), **no root** and no broad permission
  (`MANAGE_EXTERNAL_STORAGE` banned).
- Server side: **Nextcloud / WebDAV**, « dated jars » strategy — a new
  `WhatsApp/<device>/YYYY-MM-DD/` folder per backup, **never any overwrite**,
  retention configurable in-app.
- **Zero Google Play Services**, no ads, no trackers, no telemetry, no Bocal account.
  100 % Kotlin + Material 3 app, F-Droid compatible.
- Out of v1 scope: in-app E2EE, Signal, family dashboard (planned later).

## Build

Prerequisites (workshop machines):

- **JDK 21** in `~/tools/jdk21` (from milestone M0-A). The Gradle launcher reads
  `org.gradle.java.home` **without substitution** (no `~`, no `${user.home}`): declare
  it as an absolute path in **`~/.gradle/gradle.properties`** (outside git):
  `org.gradle.java.home=/home/<user>/tools/jdk21`. This keeps the repo portable.
- **Android SDK** with platform `android-35` and build-tools `35.0.0`; the SDK path is
  declared in `local.properties` (`sdk.dir=...`), a git-ignored file.

```sh
./gradlew assembleDebug   # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew lint            # 0 errors required
```

Check the right JDK is used: `./gradlew -V` (`JVM` line = Temurin 21.x).

## CI

GitHub Actions (`.github/workflows/ci.yml`): `assembleDebug` build, lint (0 errors),
unit tests (including Robolectric), **Nextcloud integration** (docker service
`nextcloud:stable-apache` + demo user: login v2 handshake → app password → Bearer 200
→ revocation → 401), **jar round-trip** (fixture §4.2 →
`WhatsApp/<device>/YYYY-MM-DD/` → re-download, sha256 compared, `sha256SUMS` proof as
a CI artifact), grep proofs for no-clear-text storage (`tools/audit-clear-text.sh`)
and zero-broad-permission/SAF-only (`tools/audit-saf-permissions.sh`), and a
reproducible-build draft (two builds with a frozen `SOURCE_DATE_EPOCH`, APK sha256
comparison rendered in the log).

## Credential security (M0-D / criterion C1)

The Nextcloud password is used **once**, to create an app token
(`GET /ocs/v2.php/core/getapppassword` over Basic, after the login v2 endpoint
handshake). Afterwards only the token (Bearer), the URL and the username are kept —
encrypted with a non-exportable **Android Keystore** AES-256-GCM key (same guarantees
as AndroidX's encrypted-prefs library, but with platform APIs: the `security-crypto`
library is deprecated on Google's side). The password is never written anywhere, never
logged, and its memory buffer is wiped as soon as the exchange is done. Logging out
revokes the token server-side, then destroys the file and the Keystore key.

## Milestone status

M0-B: skeleton (single `:app` module, Material 3). M0-D: **Nextcloud connection
(criterion C1)** — login screen (form + button + status light), authentication client
and Keystore storage tested (Robolectric + CI integration). M0-E: **SAF read of the
WhatsApp tree** — picker pre-positioned on `Android/media/com.whatsapp`, tree
remembered via persistent permission (no broad permission, grep proof
`tools/audit-saf-permissions.sh`), inventory (path, size, mtime, sha256) enumerating
100 % of the tree including hidden files (`Media/.keys`, `.wa`), any unread file
reported (zero silence) — 30 unit tests on the §4.2 fixture. M0-F: **M0 exit
criterion reached** — the §4.2 fixture tree is mounted into
`<home>/WhatsApp/<device>/YYYY-MM-DD/` of the CI Nextcloud over WebDAV (MKCOL + PUT
`If-None-Match`: never any overwrite, guardrail confined to the `WhatsApp/` prefix),
downloaded back, and the sha256 of all 17 files match — round-trip `sha256SUMS` proof
archived as a CI artifact, verdict rendered in the log. Chunked upload v2 and
retention land in M1, along with the shelf. M0-G: **publication** — public GitHub
repo, FR/EN README, C1–C10 table below. The project's reference document is
`DOSSIER-BOCAL.md` (Infrastructure_Folzo_Home).

## Criteria progress (C1–C10)

Reference: §11 of the `DOSSIER-BOCAL.md` spec document.

| Criterion | Summary | Status | Proof / where |
|---|---|---|---|
| **C1** Connection | Nextcloud URL + account → successful login; token encrypted in Keystore, nothing in clear text; clean logout | ✅ done (M0-D) | login v2 client + Robolectric; CI job “Nextcloud integration” (Bearer 200 → revoke → 401); grep proof `tools/audit-clear-text.sh` |
| **C2** First complete jar | 100 % of the source tree, `.keys`/`.crypt` included, proven by count + sha256 | 🟡 partial | CI round-trip on the §4.2 fixture (17 files, matching sha256, `sha256SUMS` artifact, M0-F); still: a real phone (M1) |
| **C3** Automatic | daily jars without touching the phone | ⬜ M1/M2 | scheduling (WorkManager) to come |
| **C4** Never overwrite | two jars/day = two prefixes; deletions confined to `WhatsApp/` | 🟡 started | `If-None-Match` + `WhatsApp/` prefix guardrail in `WebDavClient` (M0-F); `.n` suffix + retention test: M1 |
| **C5** Restore | assistant blocks while WhatsApp is active, complete copy back | ⬜ M2 | assistant (screen 3) to come |
| **C6** Robustness | resume after a network cut, 0 duplicate, 0 partial file marked complete | ⬜ M1 | chunked upload v2 to come |
| **C7** Child UI | at most 3 screens, usable with no explanation | 🟡 started | “tap” screen in place; shelf + assistant: M1–M2 |
| **C8** Go/No-Go | a child restores alone on a reset phone | ⬜ M3 | real pilot (decides the product exists) |
| **C9** Publication | proven reproducible builds + accepted F-Droid submission | 🟡 started | reproducibility draft in CI (double build with `SOURCE_DATE_EPOCH`); F-Droid submission: later |
| **C10** Docs | FR/EN README + child page + parent page | 🟡 in progress | FR + EN README in this repo (M0-G); child/parent pages: M2 |

## Licence

GPL-3.0 — see [LICENSE](LICENSE).
