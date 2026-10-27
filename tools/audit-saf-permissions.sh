#!/usr/bin/env bash
# Critère M0-E (cahier §4.1) — preuve par le code : accès aux fichiers
# uniquement par SAF (URI d'arbre persistée), aucune permission large.
# Chaque règle affiche OK ou FAIL ; un FAIL rend la CI rouge.
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

check "permission MANAGE_EXTERNAL_STORAGE (accès à tous les fichiers) absente" \
    'MANAGE_EXTERNAL_STORAGE'

check "permissions legacy de stockage absentes" \
    '(READ|WRITE)_EXTERNAL_STORAGE|requestLegacyExternalStorage|preserveLegacyExternalStorage'

# Seule INTERNET est déclarée dans le manifeste.
if grep -oE 'android\.permission\.[A-Z_]+' "$SRC/AndroidManifest.xml" | grep -v '^android\.permission\.INTERNET$'; then
    echo "FAIL : permission inattendue dans AndroidManifest.xml"
    FAIL=1
else
    echo "OK   : seule la permission INTERNET est déclarée"
fi

# L'accès aux fichiers passe par les API SAF, pas par java.io sur /storage.
check "chemins /storage ou ExternalStorageDir directs absents (SAF only)" \
    '/storage/|getExternalStorage(Directory|PublicDirectory)'

# Le picker est bien ACTION_OPEN_DOCUMENT_TREE avec prise de permission persistante.
if grep -qrE 'OpenDocumentTree|ACTION_OPEN_DOCUMENT_TREE' "$SRC" &&
    grep -qrE 'takePersistableUriPermission' "$SRC"; then
    echo "OK   : ACTION_OPEN_DOCUMENT_TREE + permission persistante présents"
else
    echo "FAIL : PICKER : ACTION_OPEN_DOCUMENT_TREE ou takePersistableUriPermission manquant"
    FAIL=1
fi

echo
if [ "$FAIL" -ne 0 ]; then
    echo "AUDIT : permission large ou accès hors SAF détecté — critère M0-E non rempli."
    exit 1
fi
echo "AUDIT : accès fichiers 100 % SAF, zéro permission large."
