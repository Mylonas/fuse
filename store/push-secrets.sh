#!/usr/bin/env bash
#
# Pushes the release secrets to all three game repos with `gh`.
#
#   bash store/push-secrets.sh
#
# One upload key signs all three games, so the four signing secrets are
# identical everywhere; only the AdMob ids differ per game.
#
# Nothing is written to disk and nothing is echoed. The password is read with
# the terminal echo off and passed to `gh` on stdin, so it never appears in your
# shell history, in a command line, or in the process list.
#
set -euo pipefail

OWNER="${OWNER:-Mylonas}"
REPOS=("chroma-core" "tether" "fuse")
KEY_DIR="${KEY_DIR:-$HOME/play-upload-key}"
B64="$KEY_DIR/upload-keystore.b64"
ALIAS="${ALIAS:-upload}"

command -v gh >/dev/null || { echo "gh (GitHub CLI) not found."; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "Not logged in. Run: gh auth login"; exit 1; }

if [ ! -f "$B64" ]; then
  echo "No keystore base64 at $B64"
  echo "Run first:  bash store/make-upload-key.sh"
  exit 1
fi

echo "Upload key : $B64"
echo "Repos      : ${REPOS[*]}"
echo "Owner      : $OWNER"
echo
echo "The keystore password will not be shown as you type."
printf 'Keystore password: '
read -rs KS_PASS
echo
[ -n "$KS_PASS" ] || { echo "Empty password, aborting."; exit 1; }
echo

# AdMob ids are per game: each game is its own app in AdMob.
declare -A APP_ID EAD_ID
for r in "${REPOS[@]}"; do
  echo "--- AdMob ids for $r ---"
  printf '  App ID        (ca-app-pub-XXXX~YYYY) : '
  read -r a
  printf '  Interstitial  (ca-app-pub-XXXX/ZZZZ) : '
  read -r i
  case "$a" in *'~'*) ;; *) echo "  !! that does not look like an App ID (needs a ~)"; exit 1;; esac
  case "$i" in *'/'*) ;; *) echo "  !! that does not look like an ad unit id (needs a /)"; exit 1;; esac
  APP_ID[$r]="$a"
  EAD_ID[$r]="$i"
  echo
done

for r in "${REPOS[@]}"; do
  repo="$OWNER/$r"
  echo "Setting secrets on $repo"
  gh secret set KEYSTORE_BASE64       --repo "$repo" < "$B64"
  printf '%s' "$KS_PASS"        | gh secret set KEYSTORE_PASSWORD     --repo "$repo"
  printf '%s' "$ALIAS"          | gh secret set KEY_ALIAS             --repo "$repo"
  printf '%s' "$KS_PASS"        | gh secret set KEY_PASSWORD          --repo "$repo"
  printf '%s' "${APP_ID[$r]}"   | gh secret set ADMOB_APP_ID          --repo "$repo"
  printf '%s' "${EAD_ID[$r]}"   | gh secret set ADMOB_INTERSTITIAL_ID --repo "$repo"
  echo "  done"
done

unset KS_PASS

echo
echo "All six secrets set on all three repos. Verify with:"
for r in "${REPOS[@]}"; do echo "  gh secret list --repo $OWNER/$r"; done
echo
echo "Then build a bundle:"
echo "  gh workflow run release.yml --repo $OWNER/chroma-core -f versionCode=2 -f versionName=1.1"
