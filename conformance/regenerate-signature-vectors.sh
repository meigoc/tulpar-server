#!/usr/bin/env bash
# Regenerate the differential signature vectors with the libAPG-compatible signer.
#
# Usage:
#     conformance/regenerate-signature-vectors.sh <apg_sigtool> <resources-dir>
#
# Rebuilds every fixture under <resources-dir>/signatures/ from deterministic
# seeds, signing with apg_sigtool (libAPG's exact libsodium Ed25519ph
# sequence). After running this, regenerate the verdict goldens:
#     python3 conformance/regenerate-signature-goldens.py <apg_oracle> <resources-dir>
set -euo pipefail

if [ "$#" -ne 2 ]; then
    sed -n '2,13p' "$0"
    exit 2
fi
SIG="$1"
S="$2/signatures"

rm -rf "$S"
mkdir -p "$S/keyring-a" "$S/keyring-ab" "$S/keyring-short" "$S/keyring-long" "$S/keyring-empty"

SEED_A=000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f
SEED_B=202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f

TMP="$(mktemp -d)"
"$SIG" keygen "$SEED_A" "$TMP/skA" "$TMP/pkA"
"$SIG" keygen "$SEED_B" "$TMP/skB" "$TMP/pkB"
cp "$TMP/pkA" "$S/keyring-a/pubA.key"
cp "$TMP/pkA" "$S/keyring-ab/pubA.key"
cp "$TMP/pkB" "$S/keyring-ab/pubB.key"

python3 -c "open('$S/payload.bin','wb').write(b'tulpar-server differential signature payload\n'*20)"
python3 -c "open('$S/empty.bin','wb').write(b'')"
python3 -c "p=open('$S/payload.bin','rb').read(); open('$S/payload-tampered.bin','wb').write(p[:-1]+b'X')"

"$SIG" sign "$TMP/skA" "$S/payload.bin" "$S/payload.sig"
"$SIG" sign "$TMP/skB" "$S/payload.bin" "$S/payload.sig.byB"
"$SIG" sign "$TMP/skA" "$S/empty.bin" "$S/empty.sig"

python3 - "$S" <<'PY'
import sys
S = sys.argv[1]
sig = open(f"{S}/payload.sig", "rb").read()
open(f"{S}/payload.sig.short", "wb").write(sig[:63])
L = 2**252 + 27742317777372353535851937790883648493
R = sig[:32]
Sv = int.from_bytes(sig[32:], "little")
S2 = (Sv + L) % (2**255)
open(f"{S}/payload.sig.malleable", "wb").write(R + S2.to_bytes(32, "little"))
PY

python3 -c "open('$S/keyring-short/short.key','wb').write(open('$TMP/pkA','rb').read()[:31])"
python3 -c "open('$S/keyring-long/long.key','wb').write(open('$TMP/pkB','rb').read()+b'\x00'*8)"
touch "$S/keyring-empty/.gitkeep"
rm -rf "$TMP"

echo "signature vectors regenerated under $S"
