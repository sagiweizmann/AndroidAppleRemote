#!/usr/bin/env bash
# Vendors the Companion Link protocol package into app/src/main/python.
#
# The atvr4samsung protocol code is not checked into this repo; CI
# (.github/workflows/build-apk.yml) clones and copies it at build time. Run this
# once before building locally, and again after any `git clean`.
set -euo pipefail

cd "$(dirname "$0")/.."

SRC=${TMPDIR:-/tmp}/atvr4samsung
DEST=app/src/main/python/atvr4samsung

rm -rf "$SRC"
git clone --depth 1 https://github.com/vb3/atvr4samsung.git "$SRC"

rm -rf "$DEST"
mkdir -p "$DEST/companion"
cp -R "$SRC/src/atvr4samsung/companion/protocol" "$DEST/companion/"
touch "$DEST/__init__.py" "$DEST/companion/__init__.py"

# chacha20poly1305_reuseable isn't available on Android; use cryptography's AEAD.
python3 - "$DEST" <<'PY'
import sys
from pathlib import Path
path = Path(sys.argv[1]) / 'companion/protocol/chacha20.py'
text = path.read_text()
old = 'from chacha20poly1305_reuseable import ChaCha20Poly1305Reusable as ChaCha20Poly1305'
new = 'from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305'
if old not in text and new not in text:
    raise SystemExit(f'chacha20.py import line not found in {path}; upstream changed')
path.write_text(text.replace(old, new))
PY

mkdir -p app/src/main/assets/licenses
cp "$SRC/LICENSE" app/src/main/assets/licenses/atvr4samsung-LICENSE.txt
cp "$SRC/src/atvr4samsung/companion/protocol/LICENSE-companion-base.md" \
   app/src/main/assets/licenses/LICENSE-companion-base.md

echo "Vendored into $DEST"
