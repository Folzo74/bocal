#!/usr/bin/env bash
# Critère C1 (cahier §7.2) — preuve par le code : aucun stockage en clair,
# aucune trace de secret dans les logs, aucun mécanisme de fuite (cookies,
# WebView). Chaque règle affiche OK ou FAIL ; un FAIL rend la CI rouge.
set -uo pipefail

SRC="app/src/main"
FAIL=0

check() {
    local description="$1" pattern="$2"
    shift 2
    if grep -rnE --include='*.kt' --include='*.java' --include='*.xml' "$@" "$pattern" "$SRC"; then
        echo "FAIL : $description"
        FAIL=1
    else
        echo "OK   : $description"
    fi
}

# Stockage natif non chiffré interdit (le seul chemin d'écriture est TokenStore,
# chiffré via Android Keystore).
check "getSharedPreferences/EncryptedSharedPreferences (prefs bruts) absents" \
    'getSharedPreferences|EncryptedSharedPreferences'

check "mode de fichier public (MODE_WORLD) absent" \
    'MODE_WORLD'

# Écritures de fichier réservées à TokenStore.kt (fichier chiffré) et
# TreeStore.kt (l'URI d'arbre SAF est une capacité dans le répertoire privé —
# jamais un secret utilisateur ; cahier §4.1).
check "écritures de fichier hors TokenStore/TreeStore absentes" \
    'openFileOutput|writeText|FileOutputStream|\.outputStream\(\)' \
    --exclude=TokenStore.kt --exclude=TreeStore.kt

# Zéro log dans le code applicatif : rien ne peut donc tracer un secret.
check "aucun appel Log/println/System.out dans l'app" \
    'Log\.[vdifwe]\(|println\(|System\.(out|err)\.print'

# Le mot de passe ne doit jamais apparaître dans une écriture de préférence
# (les variables « motDePasse » locales sont autoritées, pas les persistances).
check "persistance de mot de passe/token dans du clair interdite" \
    '(putString|putInt|putBoolean)\s*\([^)]*(password|motDePasse|token)'

# Mécanismes de fuite de credentials hors Keystore.
check "gestionnaire de cookies persistant absent" \
    'CookieManager|CookieHandler|setCookie'
check "WebView absent (pas de flux login détourné)" \
    'WebView'

echo
if [ "$FAIL" -ne 0 ]; then
    echo "AUDIT : stockage en clair ou trace potentielle détectée — critère C1 non rempli."
    exit 1
fi
echo "AUDIT : aucun stockage clair, aucune trace de secret dans les logs."
