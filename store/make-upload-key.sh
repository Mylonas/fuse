#!/usr/bin/env bash
#
# Creates the ONE upload key that signs all three games for Google Play, using
# openssl only — no JDK required.
#
# Run this once. The same key is then reused for every game: the four signing
# secrets are identical across the three repos, and only the AdMob ids differ.
#
# Run it in Git Bash:   bash store/make-upload-key.sh
#
# It asks you for a password. That password is typed straight into openssl,
# never stored, never passed as an argument (which would put it in your shell
# history), and never printed.
#
set -euo pipefail

OUT_DIR="${1:-$HOME/play-upload-key}"
ALIAS="upload"
NAME="${CERT_NAME:-Mylonas}"
COUNTRY="${CERT_COUNTRY:-CY}"

command -v openssl >/dev/null || { echo "openssl not found. Run this in Git Bash."; exit 1; }

# One key for all three games, so an existing keystore is the normal case on
# the second and third repo — reuse it rather than making another.
if [ -e "$OUT_DIR/upload-keystore.p12" ]; then
  echo "You already have an upload key at $OUT_DIR/upload-keystore.p12"
  echo "Reusing it — the same key signs all three games."
  echo
  if [ ! -e "$OUT_DIR/upload-keystore.b64" ]; then
    base64 -w 0 "$OUT_DIR/upload-keystore.p12" > "$OUT_DIR/upload-keystore.b64"
    echo "(regenerated the base64)"
  fi
  echo "  base64 : $OUT_DIR/upload-keystore.b64"
  echo "  alias  : $ALIAS"
  echo
  echo "Push it to all three repos with:  bash store/push-secrets.sh"
  echo
  echo "If you really want a NEW key — which would orphan anything already"
  echo "published with the old one — move the existing files aside first."
  exit 0
fi

mkdir -p "$OUT_DIR"
cd "$OUT_DIR"

echo "Creating an upload key in: $OUT_DIR"
echo

# MSYS_NO_PATHCONV=1 is essential on Windows: without it Git Bash rewrites the
# "/CN=..." subject into a filesystem path and openssl rejects it.
MSYS_NO_PATHCONV=1 openssl req -x509 \
  -newkey rsa:2048 -sha256 -days 10000 -noenc \
  -keyout tmp.key -out tmp.crt \
  -subj "/CN=$NAME/O=$NAME/C=$COUNTRY" >/dev/null 2>&1

echo "Now choose the keystore password. Type it twice."
echo "Put it in your password manager — losing it means losing the listing."
echo
openssl pkcs12 -export -inkey tmp.key -in tmp.crt -name "$ALIAS" -out upload-keystore.p12

# The unencrypted private key must not linger.
shred -u tmp.key tmp.crt 2>/dev/null || rm -f tmp.key tmp.crt

base64 -w 0 upload-keystore.p12 > upload-keystore.b64

echo
echo "Done."
echo "  keystore : $OUT_DIR/upload-keystore.p12   <- BACK THIS UP, off this laptop"
echo "  base64   : $OUT_DIR/upload-keystore.b64   <- paste into the GitHub secret"
echo "  alias    : $ALIAS"
echo
echo "Check it (it will ask for the password you just chose):"
echo "  openssl pkcs12 -in \"$OUT_DIR/upload-keystore.p12\" -nokeys -info"
echo
echo "Then add these repository secrets under"
echo "Settings -> Secrets and variables -> Actions:"
echo "  KEYSTORE_BASE64        = contents of upload-keystore.b64"
echo "  KEYSTORE_PASSWORD      = the password you just chose"
echo "  KEY_ALIAS              = $ALIAS"
echo "  KEY_PASSWORD           = the same password"
echo "  ADMOB_APP_ID           = ca-app-pub-XXXX~YYYY"
echo "  ADMOB_INTERSTITIAL_ID  = ca-app-pub-XXXX/ZZZZ"
echo
echo "The .b64 is one very long line. To copy it on Windows:"
echo "  clip < \"$OUT_DIR/upload-keystore.b64\""
