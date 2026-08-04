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
# Bash does the password prompting, not openssl, and hands the value over in an
# environment variable. That is deliberate: openssl asks the *Windows console*
# for a hidden password, and Git Bash (MinTTY) is not a Windows console, so
# openssl's own prompt never appears and it blocks forever on a blank line.
# Going through the environment also keeps the password out of the process
# list, which "-passout pass:..." would not.
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
echo "Choose a keystore password."
echo "Put it in your password manager — losing it means losing the listings."
echo "Nothing appears as you type. Press Enter after each one."
echo

printf 'Keystore password: '
read -rs PW1
echo
printf 'Type it again    : '
read -rs PW2
echo
echo

if [ "$PW1" != "$PW2" ]; then
  echo "Those did not match. Nothing was created — just run the script again."
  exit 1
fi
if [ -z "$PW1" ]; then
  echo "An empty password is not usable. Nothing was created."
  exit 1
fi

# MSYS_NO_PATHCONV=1 is essential on Windows: without it Git Bash rewrites the
# "/CN=..." subject into a filesystem path and openssl rejects it.
MSYS_NO_PATHCONV=1 openssl req -x509 \
  -newkey rsa:2048 -sha256 -days 10000 -noenc \
  -keyout tmp.key -out tmp.crt \
  -subj "/CN=$NAME/O=$NAME/C=$COUNTRY" >/dev/null 2>&1

export KS_PW="$PW1"
openssl pkcs12 -export \
  -inkey tmp.key -in tmp.crt \
  -name "$ALIAS" -out upload-keystore.p12 \
  -passout env:KS_PW
unset KS_PW PW1 PW2

# The unencrypted private key must not linger.
shred -u tmp.key tmp.crt 2>/dev/null || rm -f tmp.key tmp.crt

base64 -w 0 upload-keystore.p12 > upload-keystore.b64

echo "Done."
echo "  keystore : $OUT_DIR/upload-keystore.p12   <- BACK THIS UP, off this laptop"
echo "  base64   : $OUT_DIR/upload-keystore.b64   <- goes into the GitHub secret"
echo "  alias    : $ALIAS"
echo
echo "Check it (type the password, press Enter, then Ctrl-D):"
echo "  openssl pkcs12 -in \"$OUT_DIR/upload-keystore.p12\" -nokeys -info -passin stdin"
echo
echo "Next, set the secrets on all three repos:"
echo "  bash store/push-secrets.sh"
