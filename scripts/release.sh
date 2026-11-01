#!/usr/bin/env bash
# Construit, contrôle et publie une release GitHub de Dictée.
#
# Usage : scripts/release.sh [--dry-run] [-- arguments Gradle]
#
# Le script lit la version dans app/build.gradle.kts, construit l'APK de release
# signé avec le trousseau désigné par l'environnement, contrôle sa signature, puis
# crée le tag v<version> et la release GitHub. Avec --dry-run, il s'arrête après
# la construction et le contrôle, avant toute création de tag.
#
# Variables d'environnement :
#   DICTEE_KEYSTORE           chemin du trousseau de signature (obligatoire)
#   DICTEE_KEYSTORE_PASSWORD  mot de passe du trousseau (obligatoire)
#   DICTEE_KEY_ALIAS          alias de la clé dans le trousseau (obligatoire)
#   DICTEE_KEY_PASSWORD       mot de passe de la clé (celui du trousseau si absent)
#   DICTEE_EXPECTED_SHA1      empreinte SHA-1 attendue du certificat (facultative)

set -euo pipefail

fail() {
  echo "Erreur : $*" >&2
  exit 1
}

info() {
  echo "==> $*"
}

# Affiche une empreinte hexadécimale en majuscules, séparée par des deux-points.
format_sha1() {
  printf '%s' "$1" | tr 'a-f' 'A-F' | sed 's/../&:/g; s/:$//'
}

usage() {
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "${BASH_SOURCE[0]}"
}

dry_run=0
gradle_args=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --dry-run) dry_run=1 ;;
    -h | --help)
      usage
      exit 0
      ;;
    --)
      shift
      gradle_args=("$@")
      break
      ;;
    *)
      echo "Erreur : option inconnue $1." >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# Retrouve apksigner dans la plus récente version des build-tools du SDK Android.
find_apksigner() {
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -z "$sdk" ] && [ -f local.properties ]; then
    sdk="$(sed -n 's/^sdk\.dir=//p' local.properties | head -n 1)"
  fi
  [ -n "$sdk" ] || fail "SDK Android introuvable : définissez ANDROID_HOME ou sdk.dir dans local.properties."
  [ -d "$sdk/build-tools" ] || fail "Le dossier $sdk/build-tools n'existe pas."
  local build_tools
  while IFS= read -r build_tools; do
    if [ -x "$sdk/build-tools/$build_tools/apksigner" ]; then
      echo "$sdk/build-tools/$build_tools/apksigner"
      return 0
    fi
  done < <(ls -1 "$sdk/build-tools" | sort -rV)
  fail "apksigner est introuvable dans $sdk/build-tools."
}

command -v git > /dev/null || fail "git est introuvable."
if [ "$dry_run" -eq 0 ]; then
  command -v gh > /dev/null || fail "gh (GitHub CLI) est introuvable."
fi

version="$(sed -n 's/^[[:space:]]*versionName = "\(.*\)"/\1/p' app/build.gradle.kts | head -n 1)"
[ -n "$version" ] || fail "versionName est introuvable dans app/build.gradle.kts."
tag="v$version"
info "Version $version, tag $tag"

# Contrôles avant toute construction.
if [ -n "$(git status --porcelain)" ]; then
  git status --short >&2
  fail "L'arbre git est modifié. Validez ou retirez les changements ci-dessus avant de publier."
fi

[ -n "${DICTEE_KEYSTORE:-}" ] || fail "DICTEE_KEYSTORE n'est pas défini : sans trousseau, l'APK n'est pas signé."
[ -f "$DICTEE_KEYSTORE" ] || fail "Le trousseau $DICTEE_KEYSTORE n'existe pas."
[ -n "${DICTEE_KEYSTORE_PASSWORD:-}" ] || fail "DICTEE_KEYSTORE_PASSWORD n'est pas défini."
[ -n "${DICTEE_KEY_ALIAS:-}" ] || fail "DICTEE_KEY_ALIAS n'est pas défini."
DICTEE_KEYSTORE="$(cd "$(dirname "$DICTEE_KEYSTORE")" && pwd)/$(basename "$DICTEE_KEYSTORE")"
export DICTEE_KEYSTORE

expected_sha1=""
if [ -n "${DICTEE_EXPECTED_SHA1:-}" ]; then
  expected_sha1="$(printf '%s' "$DICTEE_EXPECTED_SHA1" | tr -d ': ' | tr 'A-F' 'a-f')"
  case "$expected_sha1" in
    *[!0-9a-f]* | "") fail "DICTEE_EXPECTED_SHA1 n'est pas une empreinte SHA-1 valide." ;;
  esac
  [ "${#expected_sha1}" -eq 40 ] || fail "DICTEE_EXPECTED_SHA1 doit compter 40 chiffres hexadécimaux."
fi

if git rev-parse -q --verify "refs/tags/$tag" > /dev/null; then
  fail "Le tag $tag existe déjà en local."
fi
remote_status=0
git ls-remote --exit-code --tags origin "refs/tags/$tag" > /dev/null 2>&1 || remote_status=$?
case "$remote_status" in
  0) fail "Le tag $tag existe déjà sur origin." ;;
  2) ;;
  *) fail "Impossible d'interroger origin pour vérifier le tag $tag." ;;
esac

# Contrôles propres à la publication, faits avant la construction.
if [ "$dry_run" -eq 0 ]; then
  [ -n "$(git branch -r --contains HEAD 2> /dev/null)" ] \
    || fail "Le commit courant n'est pas sur origin. Poussez la branche avant de publier."
  gh auth status > /dev/null 2>&1 || fail "gh n'est pas connecté à GitHub (gh auth login)."
fi

apksigner="$(find_apksigner)"

# Construction.
info "Tests et construction de l'APK de release"
./gradlew testDebugUnitTest assembleRelease ${gradle_args[@]+"${gradle_args[@]}"} \
  || fail "La construction a échoué. Rien n'a été publié."

apk="app/build/outputs/apk/release/app-release.apk"
[ -f "$apk" ] || fail "L'APK signé $apk est introuvable. Vérifiez les variables DICTEE_*."

# Contrôle de la signature.
info "Contrôle de la signature"
certs="$("$apksigner" verify --print-certs "$apk" 2>&1)" \
  || fail "apksigner refuse l'APK :"$'\n'"$certs"
sha1="$(printf '%s\n' "$certs" | sed -n 's/^Signer #1 certificate SHA-1 digest: //p' | head -n 1)"
[ -n "$sha1" ] || fail "L'empreinte SHA-1 du certificat est introuvable dans la sortie d'apksigner."
sha1_display="$(format_sha1 "$sha1")"
info "Empreinte SHA-1 du certificat : $sha1_display"
if [ -n "$expected_sha1" ] && [ "$sha1" != "$expected_sha1" ]; then
  fail "L'empreinte du certificat ne correspond pas à DICTEE_EXPECTED_SHA1 (attendue $(format_sha1 "$expected_sha1"), obtenue $sha1_display). Rien n'a été publié."
fi

mkdir -p build/release
asset="build/release/dictee-$version.apk"
cp "$apk" "$asset"
info "APK prêt : $asset"

if [ "$dry_run" -eq 1 ]; then
  info "Mode --dry-run : arrêt avant la création du tag $tag."
  exit 0
fi

# Publication.
info "Création et envoi du tag $tag"
git tag "$tag"
if ! git push origin "refs/tags/$tag"; then
  git tag -d "$tag" > /dev/null
  fail "L'envoi du tag $tag a échoué. Le tag local a été retiré."
fi

notes="Dictée $version. Téléchargez dictee-$version.apk, puis autorisez l'installation depuis votre navigateur ou votre gestionnaire de fichiers. Empreinte SHA-1 du certificat de signature : $sha1_display."
info "Création de la release GitHub"
gh release create "$tag" "$asset" --verify-tag --title "Dictée $version" --notes "$notes" \
  || fail "Le tag $tag est envoyé, mais la release n'a pas été créée. Relancez gh release create $tag $asset."
info "Release $tag publiée"
